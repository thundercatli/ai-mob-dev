# AGENTS.md

Guidance for AI agents working in this repo. An Android app (Kotlin, `app/`) and an iOS app
(Swift, `ios/`) that drive a remote tmux session over an SSH connection tunneled through a bundled
frpc. Read `README.md` for the product overview; this file is the high-signal build/architecture
gotchas. iOS-specific guidance lives in `ios/AGENTS.md`.

## Build prerequisites (will break the first build)

- `app/src/main/jniLibs/arm64-v8a/libfrpc.so` is **gitignored** (`*.so`) and not committed. Run
  `./scripts/build_frpc.sh` before the first build, or you get an APK with no tunneling and no error.
- Requirements: **JDK 17**, Android SDK, Android NDK, and Go. The script locates the NDK via
  `NDK_ROOT` / `ANDROID_NDK_HOME` / `ANDROID_NDK_LATEST_HOME`, or `sdk_root/ndk/<latest>`.
- The `.so` is really a cgo-enabled frpc executable (compiled with the NDK clang), not a shared
  library — it is shipped as a native lib only so Android extracts it with the execute bit. Do not
  "fix" the `.so` naming or `useLegacyPackaging = true` in `app/build.gradle.kts`.

## Commands

```bash
./scripts/build_frpc.sh                 # produce libfrpc.so first (frp v0.70.1 by default; --version vX.Y.Z)
./gradlew assembleDebug                 # debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease               # release APK (falls back to debug signing if no keystore)
./gradlew connectedDebugAndroidTest     # the only real test; needs a running device/emulator
```

Toolchain: AGP 8.13.2, Kotlin 2.2.0, `compileSdk`/`targetSdk` 36, `minSdk` 26, JVM 17.

## Testing

- There is **no JVM unit-test source set** (`src/test`); only `src/androidTest` exists.
- `connectedDebugAndroidTest` is a launch smoke test (`MainActivitySmokeTest`) — it opens the app
  for real precisely to catch a crash in an Activity's own initialisation. It compiles cleanly and
  passes static checks but can still die on every launch.
- `arm64-v8a` is the only shipped ABI. An x86_64 emulator cannot run the frpc tunnel; CI runs the
  smoke test on an x86_64 **google_apis** image (which translates arm64) at API 34.
- R8/minify is intentionally OFF — sshj and BouncyCastle resolve heavily through reflection and
  ServiceLoader; enabling shrinking needs keep rules that do not exist yet.

## Architecture (single module `:app`, id `com.devhc.aidevmob`)

- Entry: `AiDevMobApplication` (swaps in the BouncyCastle provider at startup) → `ui.MainActivity`
  (launcher, hosts the Connections / Credentials / Tunnels / Settings tabs).
- `frp/` — frpc visitor runtime. `FrpcVisitorService` is a foreground service; `FrpcRuntime` runs one
  frpc process per tunnel on its own local port. Start/stop is per-tunnel.
- `ssh/` — sshj-based transport: `SshClientFactory`, `TofuHostKeyVerifier` (TOFU), `SftpSession`,
  `TmuxSessionProbe`, `SshTerminalConnector` (drives the terminal).
- `settings/` — `EnvironmentCheck` (self-diagnostics), `UpdateChecker` + `ApkDownloader` (in-app
  update with `p.all3n.top` proxy fallback), `ConfigBackup`, `AppSettings`.
- `com/termux/**` — **vendored** termux terminal-view/terminal-emulator (Apache-2.0). `TerminalSession`
  is modified: upstream's local JNI pty subprocess is replaced with injected I/O streams so an SSH
  channel drives the terminal. VT100 parsing/rendering is untouched — do not revert it to upstream.

## Release, signing, versioning

- Signing is optional and falls back to the debug key: provide `keystore.properties` (see
  `keystore.example.properties`) or env `STORE_FILE`/`STORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`.
  CI uses `STORE_FILE_BASE64` (decoded to `release.jks`) for `STORE_FILE`.
- `versionCode` / `versionName` come from env `VERSION_CODE` / `VERSION_NAME`; defaults `1` / `0.1.0`.
  For a tag, CI sets `VERSION_NAME` to the tag with the leading `v` stripped (`v1.2.3` → `1.2.3`) and
  `VERSION_CODE` to the CI run number.
- Pushing a `v*` tag builds + publishes a GitHub Release with the APK. **A tag build fails CI if
  release-signing secrets are missing** — it refuses to publish a debug-signed release.
- The smoke test runs on PRs only. A tag pushed straight to `main` is never smoke-tested.
- Every release must be signed with the **same** key (in-app updates install over the running app).

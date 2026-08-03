# ai-mob-dev

**English** · [简体中文](README.zh-CN.md)

An Android client for driving the tmux session on your computer from your phone: a bundled frpc (STCP visitor) opens the path through the network, SSH connects to the remote shell / tmux, and Termux's terminal engine renders it.

## Features

- **Connections** — any number of SSH profiles, each nameable, duplicable and deletable. Every row is labelled with the tunnel it goes through (and whether that tunnel is up); the editor can probe the remote host for existing tmux sessions to pick from, create a new one, or skip tmux. Picking a tunnel derives Host/Port from its local port, so there is nothing to type
- **Credentials** — a username plus a password or private key is stored once as a "credential" and shared by any number of connections. Keys can be picked from local storage (SAF) or pasted from the clipboard. Everything lives in EncryptedSharedPreferences. Secrets stored inline on connections by older versions are lifted into credential entries on first launch
- **Tunnels** — two levels: a *server* holds an frps endpoint (address, port, auth token), and each *tunnel* is an STCP visitor under one of them (proxy name, secret key, local port), so visitors sharing an frps describe it once. Each tunnel runs independently on its own local port and can use either the stable Go executable or the in-process C++ preview core, selected in Settings. Only frpc ever runs on the phone
- **Automatic tunnelling** — a connection can be bound to a tunnel; opening its terminal starts that tunnel if needed and waits for it to come up
- **Terminal** — a full VT100 terminal (Termux's terminal-view / terminal-emulator) plus an extra key row (arrows ←↓↑→ repeat when held, and their encoding follows the foreground program's application-cursor mode, so they work inside vim / less), with automatic reconnection that resumes losslessly when tmux is in play
- **Languages** — English and 简体中文, following the system by default and switchable in settings (on Android 13+ it also plugs into the system's per-app language setting)
- **Settings** — FRPC kernel selection, self-diagnostics (is frpc executable, and does actually running it report a version; did BouncyCastle get swapped in; network, permissions, and any connection missing its credential), global options (terminal font size, keep screen on, startup permission prompt, language), version and signing fingerprint, in-app update check and install, and help

## In-app updates

"Updates" in the settings tab asks GitHub for the latest release. When there is a newer one, the APK can be downloaded and handed to the system installer without leaving the app — it installs over the running build, since every release is signed with the same key.

The repository is public, so **no token is required**. One is only needed if you hit GitHub's anonymous rate limit (60 requests per hour per IP).

Because github.com is unreachable on some networks, both the API query and the download try the direct route first and automatically fall back to the `p.all3n.top` path-prefix proxy:

```
https://github.com/all3n/ai-mob-dev/releases/download/v0.2.3/ai-mob-dev-v0.2.3.apk
        ↓ if the direct download fails
https://p.all3n.top/github.com/all3n/ai-mob-dev/releases/download/v0.2.3/ai-mob-dev-v0.2.3.apk
```

Only transport-level failures fall through (unreachable, timed out, 5xx, or a response that isn't an APK). A definitive answer from GitHub — 404, rate limit — is not retried, because the other route would give the same one. Downloads are checked for a real zip/APK header, so a proxy's HTML error page is never handed to the installer.

## Building

Requires JDK 17, the Android SDK, the Android NDK and Go.

`app/src/main/jniLibs/arm64-v8a/libfrpc.so` is **not in the repository** and has to be produced before the first build:

```bash
./scripts/build_frpc.sh          # builds frp v0.70.1 by default
./scripts/build_frpc.sh --version v0.71.0
./gradlew assembleDebug
```

The script clones the frp source (or uses an existing checkout via `FRP_ROOT`) and cross-compiles it with the NDK's clang under `GOOS=android CGO_ENABLED=1`.

The C++ preview core is compiled automatically by Gradle through CMake. Its platform-neutral C API and implementation live in `native/frpc_core`; Android-specific JNI glue is isolated under `app/src/main/cpp`. See `native/frpc_core/README.md` for the supported FRP subset and the iOS integration boundary.

**Why it must be compiled here**: frp's official `linux_arm64` and `android_arm64` release archives are both pure static Go builds (`CGO_ENABLED=0`). They do not link against Bionic, so they never use Android's own networking and resolver. Rebuilding with the NDK toolchain and cgo enabled is the only way to get those.

**Why the `.so` name**: Android 10+ refuses to execute files in an app's private directory, but anything packaged as a native library is extracted to `nativeLibraryDir` at install time with the execute bit set. Together with `packaging.jniLibs.useLegacyPackaging = true` in `app/build.gradle.kts` (which forces `extractNativeLibs=true`), that is what lets `ProcessBuilder` actually exec it.

Only `arm64-v8a` is built, so tunnelling does not work on the usual x86_64 emulator.

### C++ FRPC preview

Select **Settings → FRPC kernel → C++ (STCP preview)**, then stop and restart a tunnel. Running tunnels keep the kernel they started with.

The C++ kernel implements the FRP v1 STCP visitor flow with control login/run ID, token auth, user/serverUser scoping, outer TLS, configurable yamux or direct TCP, AES-CFB visitor encryption, and Snappy framed compression. The Go kernel remains the stable fallback for non-TCP transports, wire v2, plugins, custom certificate authentication, and automatic control-session reconnect.

## Translations

No UI text is hardcoded; it all lives in resources:

- `app/src/main/res/values/strings.xml` — English, the default
- `app/src/main/res/values-zh/strings.xml` — Chinese

To add a language: add `values-<code>/strings.xml`, list the locale in `app/src/main/res/xml/locales_config.xml`, and add the same BCP 47 tag to `SettingsFragment.SUPPORTED_LANGUAGES` (which drives the in-app picker — the only entry point below Android 13).

## CI and releases

`.github/workflows/android.yml` builds frpc and a release APK on every push / PR, uploading the result as the `app-release` artifact. It can also be triggered by hand from the Actions page (`workflow_dispatch`), optionally with a specific frp version.

Pushing a tag starting with `v` additionally creates a GitHub Release with the APK attached, ready to download:

```bash
git tag v0.1.0
git push origin v0.1.0
```

A tag build aligns the version with the tag (`v0.1.0` → versionName `0.1.0`; versionCode comes from the CI run number so it always increases).

Signing is optional: set the `STORE_FILE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` repository secrets to sign with your own key, otherwise the build falls back to the Android debug key (installable, but not fit for distribution). Produce `STORE_FILE_BASE64` with `base64 -i release.jks`.

Since in-app updates install over the existing app, every release has to be signed with the **same** key or the install is rejected.

## Third-party code

- `app/src/main/java/com/termux/**` — the terminal-view / terminal-emulator modules from [termux/termux-app](https://github.com/termux/termux-app) (Apache-2.0). `TerminalSession.java` is modified: upstream binds the terminal to a local JNI pty subprocess, here that is replaced with injected input/output streams so an SSH channel can drive it. The VT100 parsing and rendering code is untouched.
- frpc comes from [fatedier/frp](https://github.com/fatedier/frp) (Apache-2.0); its license ships in `app/src/main/assets/licenses/`.

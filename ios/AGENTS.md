# ios/AGENTS.md

The iOS port. Same product as the Android app (drive a remote tmux session over an SSH
connection tunneled through a bundled frpc), but the frpc runtime model is fundamentally
different — see the first section. Personal sideload-only app; never submitted to App Store.

## The one architectural difference that matters

The Android app packages frpc as an executable `.so` and **forks it as a child process** via
`ProcessBuilder`. **iOS cannot fork/exec child processes** — apps run in a sandbox. So frpc
runs **in-process** here: `ios/frpcllib` is a thin Go wrapper over frp's `client.Service`
that gomobile binds into `Frpclib.xcframework`, and the Swift app links it and calls it
directly. Same STCP visitor logic, same `127.0.0.1:bindPort` listener that SSH then dials —
just no subprocess.

Everything else mirrors Android 1:1: per-tunnel independent start/stop, status + bounded logs,
TOFU host keys, password/private-key auth, PTY shell + tmux attach.

## Build prerequisites (will break the first build)

- `ios/Frameworks/Frpclib.xcframework` is **gitignored** and not committed. Run
  `./scripts/build_frpc_ios.sh` before the first build, or the Xcode project won't link.
- Requirements: **Go 1.25+** (frp v0.70.1's `go.mod` requires it), a **full Xcode** (not just
  the Command Line Tools — `gomobile bind` needs the iPhoneOS SDK that only ships with Xcode),
  and `gomobile`/`gobind` (the script installs them on demand).
- The script guards both prerequisites and prints the exact fix if missing.
- Xcode 26.6 can deadlock at `ExecuteExternalTool ... clang -v -E -dM`: its build service waits
  for clang to exit before draining a pipe that clang's verbose probe has already filled.
  `scripts/xcode_clang_probe_wrapper.sh` is configured as the project `CC`; it removes only `-v`
  from that exact probe and forwards every real compile unchanged. Keep
  `CLANG_ENABLE_EXPLICIT_MODULES = NO` while the wrapper is configured.

## Commands

```bash
./scripts/build_frpc_ios.sh             # produce Frpclib.xcframework (real device, arm64)
./scripts/build_frpc_ios.sh --simulator # also build the arm64-simulator slice (Apple-silicon Macs)
xcrun swift scripts/generate_ios_app_icon.swift ios/AiDevMob/Assets.xcassets/AppIcon.appiconset/AppIcon.png
```

The app icon is a 1024×1024 opaque PNG rendered from the same geometry and colors as Android's
adaptive icon. Keep `scripts/generate_ios_app_icon.swift` and the Android launcher vector resources
in sync; do not replace `AppIcon.png` with a screenshot or an image containing an alpha channel.

Open `ios/AiDevMob.xcodeproj` in Xcode, pick your device or simulator, Run. Sideload signing
on a free Apple account works (7-day cert); the app is never distributed via App Store.

## The `frpcllib` Go wrapper (`ios/frpcllib/`)

- gomobile exports only funcs whose signatures use primitives (`string`/`int`/`bool`/`error`) —
  the surface is deliberately flat. Don't "enrich" it with struct types; gomobile will reject them.
- Each tunnel is its own `client.Service` (one frps connection), so starting/stopping one never
  cuts another's live session — the same property Android buys with one process per tunnel.
- frp has no public "login succeeded" callback; it only logs `"login to server success"` (at
  `client/service.go:331`). The wrapper captures that line by redirecting frp's package-global
  logger (`frlog.Logger`) through an in-process sink — the same marker Android greps from frpc
  stdout in `FrpcVisitorService.pumpOutput`. If you change the RUNNING heuristic, change both.
- Pinned to **frp v0.70.1**. frp's pre-v0.61 versions had a different package layout
  (`pkg/client` instead of top-level `client/`); do not "simplify" the imports for an old frp.

## iPad / size-class layout (universal, one code path forked at the root)

The app is Universal (`TARGETED_DEVICE_FAMILY = "1,2"`, the xcodegen default). The root SwiftUI
view (`RootView`) forks on `@Environment(\.horizontalSizeClass)` so iPhone and iPad share one
`AppCoordinator` but get different presentation containers:

- **compact (iPhone)** — `CompactShell`: the original 4-tab `MainTabView`, terminal shown via
  `.fullScreenCover(item: $coordinator.activeTerminal)`. Visually identical to the previous
  UIKit push (full-screen terminal with its own back chevron).
- **regular (iPad)** — `IPadShell`: a two-column `NavigationSplitView`. The sidebar is a
  `NavigationStack` whose root is a category menu (连接/凭证/隧道/服务器); selecting one drills
  into the matching list view. The **detail** column shows the live terminal (or a placeholder).
  Opening a connection switches `columnVisibility` to `.detailOnly` so the sidebar does not reduce
  the terminal width; the system control can reopen it without unmounting the live terminal, and
  closing the terminal restores `.all`.

Gotchas:
- The four list views (`ConnectionListView`, `CredentialListView`, `TunnelListView`,
  `ServerListView`) take `embeddedInSplit: Bool = false`. When true they skip their own outer
  `NavigationStack` + `navigationTitle` because the sidebar already provides one. iPhone leaves
  it at the default. Don't remove this flag or the iPad sidebar gets a nested NavigationStack
  (double nav bars, broken sheets).
- `TerminalViewController` is a self-contained UIKit VC bridged into SwiftUI via
  `TerminalHostingView` (`UIViewControllerRepresentable`). The **coordinator owns the VC for the
  whole session**; the bridge's `makeUIViewController` returns the passed-in instance and
  `updateUIViewController` is a no-op. Never recreate the VC on a SwiftUI re-render — that
  tears down the live SSH channel.
- `ExtraKeysAccessoryBar.applyWidthClass()` swaps its horizontal constraints by size class:
  full-width + scrollable on compact, centered + capped at 760pt on regular. All six horizontal
  constraints are stored properties created once in init and toggled via `isActive` (never
  re-created), so size-class transitions don't accumulate constraints.
- Android has **no** tablet adaptation to mirror (it hardcodes an 80×24 terminal and stretches);
  the iPad UX is iOS-only.

## SFTP file browser

- `SftpSession` is an actor and intentionally keeps one `SFTPClient` open for the browser's whole
  lifetime. Keep all SFTP requests serialized through it; reconnecting for each directory is slow
  over frpc and can exhaust Citadel subsystem handles.
- Opening Files from a live terminal reuses that terminal's `SSHClient` and opens only a new SFTP
  subsystem. This is required for STCP visitors that accept one TCP connection at a time. Closing
  the attached SFTP session must not close the parent SSH connection.
- A standalone browser resolves the latest credential, starts/waits for its configured tunnel, and
  redirects SSH to `127.0.0.1:<bindPort>` exactly like the terminal path.
- The browser is presented as a sheet on iPhone and iPad. On iPad, do not replace the detail column
  with it: removing `TerminalHostingView` triggers `viewWillDisappear` and closes the live PTY.
- Text previews are capped at 512 KB and image previews at 12 MB. Downloads stream to a unique
  temporary file and hand that URL to the system share sheet; do not load arbitrary downloads into
  memory.

## Encrypted configuration backup

- `ConfigBackup` reads and writes the same version-1 envelope as Android: PBKDF2-HMAC-SHA256
  (210,000 iterations) and AES-256-GCM with ciphertext followed by the 16-byte tag. Keep enum raw
  values and JSON field names Android-compatible.
- Restore decodes and validates the complete payload before merging records by id. An iOS backup
  explicitly writes null default credential/tunnel ids so restore can clear them; older Android
  backups omit those iOS-only fields and therefore leave existing iOS defaults unchanged.
- Credential secrets are exported from and restored to the Keychain. Simulator tests that cover
  the full round trip must run with normal simulator signing; `CODE_SIGNING_ALLOWED=NO` removes the
  Keychain entitlement and makes secrets read back as nil.
- The backup controls live on a dedicated settings destination. Keep presentation state there:
  attaching `.sheet`/`.fileImporter`/`.fileExporter` to a custom `Section` nested directly in a
  `Form` caused taps to lose state and present nothing on iOS 26.
- After changing backup UI, run `ConfigBackupUITests` on both an iPhone and iPad destination. It
  verifies the settings route, export button, medium passphrase sheet, secure fields, and actions.

## Environment self-check

- iOS checks the same failure domains as Android, adapted to the in-process runtime: Frpclib
  initialization, CryptoKit AES-GCM, Keychain round trip, `NWPathMonitor` network state, Low Power
  Mode/background limits, and configuration references/secrets/ports.
- The network check is a one-shot `NWPathMonitor` probe guarded by a two-second timeout. Both the
  path callback and timeout feed one lock-protected continuation; do not replace it with a task
  group that merely cancels the monitor, because cancellation does not resume its continuation.
- Configuration validation is pure (`EnvironmentCheck.evaluateConfiguration`) so unit tests can
  cover missing references, secrets, illegal ports, and duplicate tunnel bind ports without
  touching user stores.
- `EnvironmentCheckView` owns its async state on the main actor and lives behind the settings
  NavigationLink, which works in both the iPhone NavigationStack and iPad sidebar stack.

## Connection credentials and terminal tmux menu

- `ConnectionEditView` can create or edit credentials without leaving the connection editor. After
  saving, reload the credential list, select the saved credential, and synchronize username/auth
  fields so the connection uses the new value immediately.
- Credential editing and the tmux session picker share one enum-backed `.sheet(item:)`. Do not add
  separate item sheets to the same view; competing sheet modifiers can prevent one presentation
  from appearing.
- `TerminalViewController` exposes its secondary commands through one `UIMenu`: keyboard, SFTP,
  tmux new/previous/next/list/rename, reconnect, and disconnect. Plain-shell connections omit the
  tmux actions.

## In-app update check

- `UpdateChecker` mirrors Android's GitHub release query: numeric component version comparison,
  optional Keychain-backed token, and direct API access followed by the `p.all3n.top` path-prefix
  mirror only for transport, malformed-response, or server failures. Definitive 401/403/404/429
  responses must not retry through the mirror.
- `UpdateCheckView` is a dedicated settings destination and works in both the iPhone stack and iPad
  split view. iOS cannot download and overwrite its own executable like Android installs an APK;
  an available release shows notes and opens the releases page for installation through the same
  signing/sideload channel.
- Keep `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` set in `project.yml`; update comparisons
  read `CFBundleShortVersionString`, so an implicit Xcode default can produce incorrect results.
- `UpdateTokenStore` uses the same Keychain account previously reserved by Android-compatible
  backups. Do not create a second token setting or restored tokens will appear to be lost.
- After changing update UI, run `UpdateCheckerTests` plus `UpdateCheckUITests` on both iPhone and
  iPad. The network tests use a mock URL protocol and must cover mirror fallback and no-retry HTTP
  failures without making live GitHub requests.

## The two iOS-only constraints (both have mitigations)

1. **No foreground service.** iOS suspends the app ~30s after backgrounding, dropping the
   tunnel and SSH connection. frpc reconnects automatically on foreground
   (`loopLoginUntilSuccess`). For longer background life on a sideload, use
   `UIApplication.beginBackgroundTask` (~3 min) or a `UIBackgroundModes` entry. The Android
   equivalent (`FrpcVisitorService` foreground notification) does not exist on iOS.
2. **iOS background sockets get suspended**, so the SSH/tmux session is interrupted on
   backgrounding — but tmux keeps the session server-side, so reconnect + re-attach is lossless.

## Stack

| Layer | Choice | Why |
|-------|--------|-----|
| frpc | `Frpclib.xcframework` (gomobile bind of `ios/frpcllib`) | Only way to run frpc on iOS; proven by duanhai/frpc-IOS-1 (2018), kamoguai/frpc_mobile (2024). |
| SSH | Citadel (SPM, MIT) | Only actively-maintained pure-Swift iOS SSH lib: password/OpenSSH-key auth, TOFU via `SSHHostKeyValidator.custom`, `withPTY` for shell channels. |
| Terminal | SwiftTerm (SPM, MIT) | Mature pure-Swift VT100/xterm emulator; native UIKit `TerminalView`; DECCKM (application-cursor mode) handled natively, so the arrow-key row just works. Same injected-I/O architecture as Android's vendored Termux. |
| Secrets | iOS Keychain | Equivalent of Android's EncryptedSharedPreferences. |

Prior art validated the frpc approach: `duanhai/frpc-IOS-1` and `kamoguai/frpc_mobile` both
ship `Frpclib.framework`/`.xcframework` via gomobile bind.

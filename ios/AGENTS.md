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

## Commands

```bash
./scripts/build_frpc_ios.sh             # produce Frpclib.xcframework (real device, arm64)
./scripts/build_frpc_ios.sh --simulator # also build the arm64-simulator slice (Apple-silicon Macs)
```

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

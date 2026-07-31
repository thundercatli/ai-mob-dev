#!/usr/bin/env bash
#
# Builds the iOS Frpclib.xcframework from ios/frpcllib via gomobile bind.
#
# Unlike the Android app (which packages frpc as an executable .so and forks it as a child
# process), iOS apps run in a sandbox that CANNOT fork/exec child processes. So frpc runs
# *in-process*: this script turns the Go wrapper at ios/frpcllib into an XCFramework that the
# Swift app links against, and calls the same frp client.Service visitor logic the Android
# binary does.
#
# Requirements: Go (1.25+, since frp v0.70.1's go.mod requires it), a full Xcode (NOT just the
# Command Line Tools — gomobile bind needs the iPhoneOS SDK that only ships with Xcode), and
# `gomobile`/`gobind` on PATH (this script installs them if missing).
#
# Usage: scripts/build_frpc_ios.sh
#        scripts/build_frpc_ios.sh --simulator   # also build the arm64 simulator slice
#
# Output: ios/Frameworks/Frpclib.xcframework

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
wrapper_dir="$repo_root/ios/frpcllib"
output="$repo_root/ios/Frameworks/Frpclib.xcframework"

BUILD_SIMULATOR=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --simulator) BUILD_SIMULATOR=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

# --- prerequisites ----------------------------------------------------------
if ! command -v go >/dev/null 2>&1; then
  echo "error: Go is not installed (frp v0.70.1 requires Go 1.25+)." >&2
  echo "  Install with: brew install go" >&2
  exit 1
fi

# gomobile bind needs the full Xcode: the iPhoneOS SDK does not ship with the Command Line
# Tools. xcode-select -p reports /Library/Developer/CommandLineTools when only CLT is installed.
xcode_path="$(xcode-select -p 2>/dev/null || true)"
if [[ "$xcode_path" == *CommandLineTools* || -z "$xcode_path" ]]; then
  echo "error: full Xcode is required (gomobile bind needs the iPhoneOS SDK)." >&2
  echo "  Current developer dir: ${xcode_path:-<none>}" >&2
  echo "  Install Xcode from the App Store, then run: sudo xcode-select -s /Applications/Xcode.app/Contents/Developer" >&2
  exit 1
fi
if ! xcrun --sdk iphoneos --show-sdk-version >/dev/null 2>&1; then
  echo "error: iPhoneOS SDK not found — Xcode may be a partial install. Reinstall Xcode." >&2
  exit 1
fi

# gomobile + gobind: install on demand into $(go env GOPATH)/bin if not already present.
gopath_bin="$(go env GOPATH)/bin"
export PATH="$gopath_bin:$PATH"
if ! command -v gomobile >/dev/null 2>&1; then
  echo "==> installing gomobile"
  go install golang.org/x/mobile/cmd/gomobile@latest
fi
# gobind is invoked via `go tool gobind`, which needs the tool directive in go.mod (it is there).

# --- build ------------------------------------------------------------------
echo "==> Xcode:    $(xcodebuild -version 2>/dev/null | head -1)"
echo "==> Go:       $(go version)"
echo "==> SDK:      iphoneos $(xcrun --sdk iphoneos --show-sdk-version)"
echo "==> wrapper:  $wrapper_dir"

# gomobile init is a no-op on modern gomobile but harmless.
gomobile init >/dev/null 2>&1 || true

# Default target is the real device (arm64). On Apple-silicon Macs add the simulator slice so
# the app runs in the simulator too. Building both at once produces a fat XCFramework.
if [[ "$BUILD_SIMULATOR" -eq 1 ]]; then
  target="ios/arm64,iossimulator/arm64"
else
  target="ios/arm64"
fi

echo "==> target:   $target"
echo "==> output:   $output"

# Build from the wrapper module so gomobile picks up its go.mod (which pins frp v0.70.1 and
# declares the gobind tool dependency).
(
  cd "$wrapper_dir"
  # -trimpath keeps the build reproducible; -ldflags strip the Go symbols table for size.
  gomobile bind -trimpath -ldflags="-s -w" -target="$target" -o "$output" .
)

echo "==> wrote $output"
ls -lhR "$output" | head -20

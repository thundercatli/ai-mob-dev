#!/usr/bin/env bash
#
# Cross-compiles frp's frpc for Android/arm64 and drops it into the app as
# app/src/main/jniLibs/arm64-v8a/libfrpc.so.
#
# The binary is packaged under the .so name on purpose: Android 10+ refuses to execute files from an
# app's private data dir, but files shipped as native libraries are extracted to nativeLibraryDir
# with the execute bit set (see packaging.jniLibs.useLegacyPackaging in app/build.gradle.kts).
#
# CGO_ENABLED=1 with the NDK's clang matters: a pure-Go build (which is what the official
# linux_arm64 and android_arm64 release archives both are) does not link against Bionic, so it does
# not use Android's own resolver/network stack.
#
# Requirements: Go, and an Android NDK. Set FRP_ROOT to reuse an existing frp checkout, otherwise the
# pinned version is cloned into build/frp-src.
#
# Usage: scripts/build_frpc.sh [--version vX.Y.Z]

set -euo pipefail

FRP_VERSION="${FRP_VERSION:-v0.70.1}"
ANDROID_API="${ANDROID_API:-26}"   # keep in sync with minSdk in app/build.gradle.kts

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) FRP_VERSION="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="$repo_root/app/src/main/jniLibs/arm64-v8a/libfrpc.so"

# --- locate the NDK -----------------------------------------------------------
ndk_root=""
for candidate in "${NDK_ROOT:-}" "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_LATEST_HOME:-}"; do
  if [[ -n "$candidate" && -d "$candidate" ]]; then
    ndk_root="$candidate"
    break
  fi
done
if [[ -z "$ndk_root" ]]; then
  sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  if [[ -d "$sdk_root/ndk" ]]; then
    ndk_root="$(find "$sdk_root/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)"
  fi
fi
if [[ -z "$ndk_root" ]]; then
  echo "error: no Android NDK found. Set NDK_ROOT, or install one via sdkmanager." >&2
  exit 1
fi

# The NDK only ships x86_64 host toolchains, including on Apple silicon (run under Rosetta).
case "$(uname -s)" in
  Darwin) host_tag="darwin-x86_64" ;;
  Linux)  host_tag="linux-x86_64" ;;
  *) echo "error: unsupported host $(uname -s)" >&2; exit 1 ;;
esac

cc="$ndk_root/toolchains/llvm/prebuilt/$host_tag/bin/aarch64-linux-android${ANDROID_API}-clang"
if [[ ! -x "$cc" ]]; then
  echo "error: clang wrapper not found: $cc" >&2
  exit 1
fi

# --- get the frp source -------------------------------------------------------
frp_root="${FRP_ROOT:-$repo_root/build/frp-src}"
if [[ ! -d "$frp_root/.git" && ! -f "$frp_root/go.mod" ]]; then
  echo "==> cloning frp $FRP_VERSION into $frp_root"
  mkdir -p "$(dirname "$frp_root")"
  git clone --depth 1 --branch "$FRP_VERSION" https://github.com/fatedier/frp.git "$frp_root"
fi

# --- build --------------------------------------------------------------------
echo "==> NDK:  $ndk_root"
echo "==> CC:   $(basename "$cc")"
echo "==> frp:  $frp_root ($FRP_VERSION)"

mkdir -p "$(dirname "$output")"
(
  cd "$frp_root"
  # `noweb` skips the embedded dashboard, whose assets require a Node build we do not need here.
  CC="$cc" GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
    go build -trimpath -ldflags "-s -w -checklinkname=0" -tags "frpc noweb" \
    -o "$output" ./cmd/frpc
)
chmod 755 "$output"

echo "==> wrote $output"
ls -lh "$output"

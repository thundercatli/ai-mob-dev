#!/bin/bash

developer_dir="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
real_clang="$developer_dir/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"

is_probe=false
has_preprocess=false
has_macros=false
for argument in "$@"; do
    case "$argument" in
        -v) is_probe=true ;;
        -E) has_preprocess=true ;;
        -dM) has_macros=true ;;
    esac
done

# Xcode 26.6's build service waits for this probe to exit before draining its output pipe. Clang's
# verbose output now exceeds that pipe, so the child blocks forever. The macro dump is sufficient
# for Xcode's capability detection; removing only -v keeps it below the pipe limit.
if $is_probe && $has_preprocess && $has_macros; then
    filtered_args=()
    for argument in "$@"; do
        [ "$argument" = "-v" ] && continue
        filtered_args+=("$argument")
    done
    exec "$real_clang" "${filtered_args[@]}"
fi

exec "$real_clang" "$@"

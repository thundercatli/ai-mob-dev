# AiDevMob portable FRPC core

This directory contains the platform-neutral C++17 STCP visitor used by the Android C++ kernel. The public surface is the C ABI in `include/aidevmob/frpc_core.h`; Android-specific JNI and TLS socket bridging remain outside this directory so the same core can be wrapped by Objective-C++ on iOS.

## Supported STCP behavior

- FRP v1 JSON framing compatible with frp v0.70.1
- Persistent control login, token authentication, `run_id`, and 30-second heartbeats
- `user` and visitor `serverUser` proxy-name scoping
- STCP secret authentication with `MD5(secretKey + unixTimestamp)`
- Configurable yamux (`transport.tcpMux = true`) or direct TCP
- Official visitor payload encryption: PBKDF2-HMAC-SHA1 with FRP's runtime `frp` salt, AES-128-CFB, and per-direction IVs
- Official Snappy framed compression, including compressed-chunk decoding and portable uncompressed-chunk encoding
- Multiple simultaneous local connections and full-duplex forwarding
- Platform transport callback for outer TLS; Android supplies an `SSLSocket` bridge and the host test driver can use OpenSSL
- Cooperative stop that closes the listener, control channel, and active visitor sockets

The portable core currently targets TCP STCP only. QUIC, KCP, WebSocket, visitor plugins, FRP wire v2, automatic control-session reconnect, custom CA/client certificates, and custom TLS first-byte mode are not implemented. The Android TLS adapter intentionally matches official frpc's no-CA default by accepting the frps certificate without verification.

## Build and test

```bash
cmake -S native/frpc_core -B build/frpc-core \
  -DAIDEVMOB_FRPC_BUILD_DRIVER=ON \
  -DAIDEVMOB_FRPC_BUILD_TESTS=ON
cmake --build build/frpc-core
ctest --test-dir build/frpc-core --output-on-failure
```

The unit tests cover FRP's PBKDF2/AES-CFB vectors, official Go Snappy framed output, and stream round trips. To run the local official-frp interoperability matrix:

```bash
FRPS_BIN=/path/to/frps \
FRPC_BIN=/path/to/frpc \
DRIVER_BIN=build/frpc-core/aidevmob_frpc_driver \
native/frpc_core/tests/run_official_frp_e2e.sh
```

The matrix covers token login, user/serverUser scope, yamux and direct TCP, encryption, compression, combined encryption+compression, and outer TLS when the driver was built with OpenSSL.

## iOS reuse boundary

Build all files under `native/frpc_core/src` plus `native/frpc_core/include`, then implement `aidevmob_frpc_open_transport_callback` in Objective-C++ using Network.framework, Secure Transport, or another platform TLS stream exposed as a connected file descriptor. No Android or JNI symbol is referenced by the portable target.

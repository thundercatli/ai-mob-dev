#!/bin/sh
set -eu

: "${FRPS_BIN:?set FRPS_BIN to an official frps binary}"
: "${FRPC_BIN:?set FRPC_BIN to an official frpc binary}"
: "${DRIVER_BIN:?set DRIVER_BIN to aidevmob_frpc_driver}"

BASE_PORT=${BASE_PORT:-17300}
ROOT=$(mktemp -d "${TMPDIR:-/tmp}/aidevmob-frpc-e2e.XXXXXX")
PIDS=""

stop_processes() {
    if [ -n "$PIDS" ]; then
        kill $PIDS 2>/dev/null || true
        wait $PIDS 2>/dev/null || true
    fi
    PIDS=""
}
trap stop_processes EXIT INT TERM

run_case() {
    name=$1
    offset=$2
    tcp_mux=$3
    encryption=$4
    compression=$5
    tls=$6
    server_port=$((BASE_PORT + offset))
    echo_port=$((BASE_PORT + 1000 + offset))
    bind_port=$((BASE_PORT + 2000 + offset))
    case_dir="$ROOT/$name"
    mkdir -p "$case_dir"

    cat >"$case_dir/frps.toml" <<EOF
bindAddr = "127.0.0.1"
bindPort = $server_port
auth.method = "token"
auth.token = "matrix-token"
transport.tcpMux = $tcp_mux
EOF
    cat >"$case_dir/publisher.toml" <<EOF
serverAddr = "127.0.0.1"
serverPort = $server_port
user = "publisher"
auth.method = "token"
auth.token = "matrix-token"
transport.tls.enable = $tls
transport.tcpMux = $tcp_mux

[[proxies]]
name = "$name"
type = "stcp"
secretKey = "matrix-secret"
allowUsers = ["visitor"]
localIP = "127.0.0.1"
localPort = $echo_port
EOF
    cat >"$case_dir/echo.py" <<EOF
import socket
s = socket.socket()
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", $echo_port))
s.listen()
while True:
    connection, _ = s.accept()
    while True:
        data = connection.recv(65536)
        if not data:
            break
        connection.sendall(data)
    connection.close()
EOF

    python3 "$case_dir/echo.py" >"$case_dir/echo.log" 2>&1 & echo_pid=$!
    "$FRPS_BIN" -c "$case_dir/frps.toml" >"$case_dir/frps.log" 2>&1 & frps_pid=$!
    sleep 1
    "$FRPC_BIN" -c "$case_dir/publisher.toml" >"$case_dir/frpc.log" 2>&1 & frpc_pid=$!
    sleep 1

    mux_flag=0
    encryption_flag=0
    compression_flag=0
    tls_flag=0
    [ "$tcp_mux" = true ] && mux_flag=1
    [ "$encryption" = true ] && encryption_flag=1
    [ "$compression" = true ] && compression_flag=1
    [ "$tls" = true ] && tls_flag=1
    "$DRIVER_BIN" 127.0.0.1 "$server_port" "$name" matrix-secret 127.0.0.1 "$bind_port" \
        matrix-token visitor publisher "$mux_flag" "$encryption_flag" "$compression_flag" "$tls_flag" \
        >"$case_dir/driver.log" 2>&1 & driver_pid=$!
    PIDS="$driver_pid $frpc_pid $frps_pid $echo_pid"
    sleep 2

    python3 - "$bind_port" "$name" <<'PY'
import socket
import sys
port = int(sys.argv[1])
name = sys.argv[2].encode()
payload = (name + b"-payload-") * 12000
connection = socket.create_connection(("127.0.0.1", port), 5)
connection.settimeout(10)
connection.sendall(payload)
received = b""
while len(received) < len(payload):
    chunk = connection.recv(65536)
    if not chunk:
        break
    received += chunk
assert received == payload, (len(received), len(payload))
connection.close()
PY
    stop_processes
    echo "PASS $name"
}

run_case yamux-plain 0 true false false false
run_case yamux-encryption 10 true true false false
run_case yamux-compression 20 true false true false
run_case yamux-both 30 true true true false
run_case direct-both 40 false true true false
run_case tls-both 50 true true true true

echo "All FRP interoperability cases passed. Logs: $ROOT"

#!/usr/bin/env bash
# End-to-end test of the OpenVPN 3 -> VLESS chain on a Linux host, without
# Android. Brings up, all on this machine:
#   - an OpenVPN 2.6 server on 127.0.0.1:1194/udp (username/password auth,
#     tls-crypt), tunnel network 10.8.0.0/24
#   - dnsmasq on 10.8.0.1 (only reachable inside the tunnel) resolving
#     vless.internal -> 10.8.0.1
#   - an Xray VLESS + REALITY + Vision server listening on 10.8.0.1:10443
#   - an HTTP server on 127.0.0.1:8080 as the final destination
# then runs the real chain::Client (OpenVPN 3 core) and hands its packet
# socket to the Go engine, which fetches files via SOCKS -> VLESS -> OpenVPN.
#
# Needs root (for the server's tun device), openvpn, dnsmasq, openssl,
# python3, go, cmake and ninja.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-$(mktemp -d)}
BUILD=${BUILD:-$WORK/build}
mkdir -p "$WORK/www"
cd "$WORK"
PIDS=()
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

echo "== building (work dir $WORK)"
cmake -S "$ROOT/hosttest" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$BUILD" --target ovpn_hosttest >/dev/null
(cd "$ROOT/engine" && go test -c -tags e2e -o "$WORK/e2e.test" . && go build -o "$WORK/xray" github.com/xtls/xray-core/main)

echo "== PKI"
ec() { openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes "$@" 2>/dev/null; }
ec -x509 -keyout ca.key -out ca.crt -days 2 -subj /CN=e2e-ca
ec -keyout server.key -out server.csr -subj /CN=e2e-server
printf 'extendedKeyUsage=serverAuth\nkeyUsage=digitalSignature\n' > server.ext
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 2 \
    -extfile server.ext -out server.crt 2>/dev/null
openvpn --genkey tls-crypt tc.key
ec -x509 -keyout target.key -out target.crt -days 2 -subj /CN=www.example.test

cat > checkpass.sh <<'EOF'
#!/bin/sh
[ "$(sed -n 1p "$1")" = test ] && [ "$(sed -n 2p "$1")" = secret ]
EOF
chmod +x checkpass.sh

echo "== OpenVPN server"
cat > server.conf <<EOF
local 127.0.0.1
port 1194
proto udp
dev ovpne2e
dev-type tun
ca $WORK/ca.crt
cert $WORK/server.crt
key $WORK/server.key
dh none
tls-crypt $WORK/tc.key
topology subnet
server 10.8.0.0 255.255.255.0
push "dhcp-option DNS 10.8.0.1"
push "redirect-gateway def1"
verify-client-cert none
username-as-common-name
script-security 2
auth-user-pass-verify $WORK/checkpass.sh via-file
keepalive 10 60
verb 3
EOF
openvpn --config server.conf --log "$WORK/openvpn-server.log" &
PIDS+=($!)
for _ in $(seq 50); do grep -q "Initialization Sequence Completed" openvpn-server.log 2>/dev/null && break; sleep 0.2; done
grep -q "Initialization Sequence Completed" openvpn-server.log || { cat openvpn-server.log; exit 1; }

dnsmasq -k --no-resolv --no-hosts --bind-interfaces --listen-address=10.8.0.1 \
    --address=/vless.internal/10.8.0.1 --log-queries --log-facility="$WORK/dnsmasq.log" &
PIDS+=($!)

echo "== targets"
echo chain-ok > www/marker.txt
head -c 8000000 /dev/urandom > www/big.bin
python3 -m http.server 8080 --bind 127.0.0.1 --directory www >/dev/null 2>&1 &
PIDS+=($!)
# REALITY borrows the TLS handshake of a real site; use a local TLS 1.3 server.
python3 - <<'EOF' &
import http.server, ssl
srv = http.server.ThreadingHTTPServer(("127.0.0.1", 8443), http.server.SimpleHTTPRequestHandler)
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain("target.crt", "target.key")
srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
srv.serve_forever()
EOF
PIDS+=($!)

echo "== Xray VLESS server"
UUID=$(./xray uuid)
KEYS=$(./xray x25519)
PRIV=$(echo "$KEYS" | awk -F': ' '/^PrivateKey/{print $2}')
PUB=$(echo "$KEYS" | awk -F': ' '/^Password/{print $2}')
SID=6ba85179e30d4fc2
cat > xray-server.json <<EOF
{
  "log": {"loglevel": "info", "access": "$WORK/xray-access.log"},
  "inbounds": [{
    "listen": "10.8.0.1", "port": 10443, "protocol": "vless",
    "settings": {"clients": [{"id": "$UUID", "flow": "xtls-rprx-vision"}], "decryption": "none"},
    "streamSettings": {"network": "tcp", "security": "reality", "realitySettings": {
      "target": "127.0.0.1:8443", "serverNames": ["www.example.test"],
      "privateKey": "$PRIV", "shortIds": ["$SID"]}}
  }],
  "outbounds": [{"protocol": "freedom",
    "settings": {"finalRules": [{"action": "allow", "ip": ["127.0.0.1/32"]}]}}]
}
EOF
./xray run -c xray-server.json > xray-server.log 2>&1 &
PIDS+=($!)
sleep 1

cat > client.ovpn <<EOF
client
dev tun
proto udp
remote 127.0.0.1 1194
nobind
remote-cert-tls server
auth-user-pass
<ca>
$(cat ca.crt)
</ca>
<tls-crypt>
$(cat tc.key)
</tls-crypt>
EOF

LINK="vless://$UUID@vless.internal:10443?encryption=none&security=reality&sni=www.example.test&fp=chrome&pbk=$PUB&sid=$SID&type=tcp&flow=xtls-rprx-vision#e2e"
echo "== running chain: $LINK"
"$BUILD/ovpn_hosttest" client.ovpn test secret -- env VLESS_LINK="$LINK" \
    TARGET_URL=http://127.0.0.1:8080 BIG_FILE="$WORK/www/big.bin" \
    "$WORK/e2e.test" -test.run TestE2E -test.v 2> hosttest.log

echo "== checks"
grep -q "query\[A\] vless.internal from 10.8.0." dnsmasq.log \
    && echo "ok: vless.internal was resolved by the in-tunnel DNS server"
grep -E "from 10\.8\.0\.[0-9]+:[0-9]+ accepted tcp:127.0.0.1:8080" xray-access.log | head -1 \
    && echo "ok: the VLESS server saw the client at its OpenVPN address"
grep -q "from 10\.8\.0\." xray-access.log
echo "E2E PASSED"

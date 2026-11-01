#!/usr/bin/env bash
# End-to-end test of the OpenVPN-only route (no VLESS) on a Linux host. Here
# OpenVPN 3 gets a real TUN (chain::Mode::Device), set up from the addresses
# and routes the server pushed, as ChainVpnService.onDeviceTun does on Android.
#
# Brings up a network namespace "ovpnsrv" joined to this one by a veth pair
# (192.168.77.1 here, 192.168.77.2 there), with:
#   - an OpenVPN 2.6 server on 192.168.77.2:1194/udp (client certificate,
#     tls-crypt), tunnel network 10.9.0.0/24, pushing a route to 10.77.0.0/16,
#     a DNS server and a search domain
#   - an HTTP server on 10.77.0.1:8080, reachable only through the tunnel
# then runs chain::Client with --device-tun and fetches a file through it.
#
# Needs root, openvpn, iproute2, openssl, python3, curl, cmake and ninja.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${WORK:-$(mktemp -d)}
BUILD=${BUILD:-$WORK/build}
NS=ovpnsrv
mkdir -p "$WORK/www"
cd "$WORK"
PIDS=()
cleanup() {
    for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null || true; done
    ip netns pids "$NS" 2>/dev/null | xargs -r kill 2>/dev/null || true
    ip link del ovpnveth0 2>/dev/null || true
    ip netns del "$NS" 2>/dev/null || true
}
trap cleanup EXIT

echo "== building (work dir $WORK)"
cmake -S "$ROOT/hosttest" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$BUILD" --target ovpn_hosttest >/dev/null

echo "== PKI"
ec() { openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes "$@" 2>/dev/null; }
sign() {  # <name> <extendedKeyUsage>
    ec -keyout "$1.key" -out "$1.csr" -subj "/CN=e2e-$1"
    printf 'extendedKeyUsage=%s\nkeyUsage=digitalSignature\n' "$2" > "$1.ext"
    openssl x509 -req -in "$1.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -days 2 \
        -extfile "$1.ext" -out "$1.crt" 2>/dev/null
}
ec -x509 -keyout ca.key -out ca.crt -days 2 -subj /CN=e2e-ca
sign server serverAuth
sign client clientAuth
openvpn --genkey tls-crypt tc.key

echo "== network namespace"
ip netns add "$NS"
ip link add ovpnveth0 type veth peer name ovpnveth1
ip link set ovpnveth1 netns "$NS"
ip addr add 192.168.77.1/24 dev ovpnveth0
ip link set ovpnveth0 up
ip netns exec "$NS" ip addr add 192.168.77.2/24 dev ovpnveth1
ip netns exec "$NS" ip link set ovpnveth1 up
ip netns exec "$NS" ip link set lo up
ip netns exec "$NS" ip addr add 10.77.0.1/16 dev lo

echo "== OpenVPN server"
cat > server.conf <<EOF
local 192.168.77.2
port 1194
proto udp
dev ovpnsrv0
dev-type tun
ca $WORK/ca.crt
cert $WORK/server.crt
key $WORK/server.key
dh none
tls-crypt $WORK/tc.key
topology subnet
server 10.9.0.0 255.255.255.0
push "route 10.77.0.0 255.255.0.0"
push "dhcp-option DNS 10.9.0.1"
push "dhcp-option DOMAIN corp.test"
keepalive 10 60
verb 3
EOF
ip netns exec "$NS" openvpn --config server.conf --log "$WORK/openvpn-server.log" &
PIDS+=($!)
for _ in $(seq 50); do grep -q "Initialization Sequence Completed" openvpn-server.log 2>/dev/null && break; sleep 0.2; done
grep -q "Initialization Sequence Completed" openvpn-server.log || { cat openvpn-server.log; exit 1; }

echo "== target"
echo device-tun-ok > www/marker.txt
ip netns exec "$NS" python3 -m http.server 8080 --bind 10.77.0.1 --directory www >/dev/null 2>&1 &
PIDS+=($!)

# A certificate-only profile: no auth-user-pass.
cat > client.ovpn <<EOF
client
dev tun
proto udp
remote 192.168.77.2 1194
nobind
remote-cert-tls server
<ca>
$(cat ca.crt)
</ca>
<cert>
$(cat client.crt)
</cert>
<key>
$(cat client.key)
</key>
<tls-crypt>
$(cat tc.key)
</tls-crypt>
EOF

cat > check.sh <<'EOF'
#!/bin/sh
set -e
echo "iface=$OVPN_IFACE addrs=$OVPN_ADDRS routes=$OVPN_ROUTES dns=$OVPN_DNS"
case ",$OVPN_ROUTES," in *,10.77.0.0/16,*) ;; *) echo "pushed route missing"; exit 1;; esac
[ "$OVPN_DNS" = 10.9.0.1 ] || { echo "pushed DNS missing"; exit 1; }
ip route get 10.77.0.1 | grep -q "dev $OVPN_IFACE" || { echo "10.77.0.1 is not routed into the TUN"; exit 1; }
ping -c 2 -W 3 10.9.0.1 >/dev/null
[ "$(curl -sS --noproxy '*' --max-time 10 http://10.77.0.1:8080/marker.txt)" = device-tun-ok ]
EOF
chmod +x check.sh

echo "== running OpenVPN with the device TUN"
"$BUILD/ovpn_hosttest" --device-tun client.ovpn "" "" -- "$WORK/check.sh" 2> hosttest.log \
    || { cat hosttest.log; exit 1; }
grep "device tun:" hosttest.log
grep -q "domains=corp.test" hosttest.log || { echo "pushed search domain missing"; exit 1; }
echo "DEVICE TUN E2E PASSED"

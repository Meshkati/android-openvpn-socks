# OpenVPN → VLESS chain for Android

A minimal Android VPN app that first connects to an **OpenVPN** server with the
official **OpenVPN 3 core** library, then connects to a **VLESS** server
*through* that tunnel using **Xray-core** (the v2ray-core fork where VLESS,
REALITY and Vision are developed). It then gives the whole device VPN access
over that chain.

```
apps ─► VpnService TUN ─► Xray "tun" inbound ─► VLESS outbound
                                                   │  (Xray's system dialer replaced)
                                                   ▼
                      userspace TCP/IP stack (gVisor) on a socketpair
                                                   │  raw IP packets
                                                   ▼
                      OpenVPN 3 core ─► UDP/TCP socket ─► OpenVPN server ─► VLESS server ─► internet
```

## Why it is built this way

Android allows one active `VpnService` TUN per app, and the TUN has to carry the
apps' traffic into Xray. So OpenVPN never gets a real TUN:

1. **OpenVPN 3** talks to the OS only through `TunBuilderBase`. When it calls
   `tun_builder_establish()`, `chain::Client` (`app/src/main/cpp/chain_client.hpp`)
   returns one end of an `AF_UNIX` datagram **socketpair**. OpenVPN 3 reads and writes
   raw IP packets on it exactly as it would on a TUN.
2. The other end goes to the **Go engine** (`engine/`), which attaches a gVisor
   TCP/IP stack to it, configured with the IP and DNS the OpenVPN server pushed.
   Xray's system dialer is replaced (`internet.UseAlternativeSystemDialer`), so the
   VLESS connection, and any DNS lookup of the VLESS hostname, is made *inside* the
   OpenVPN tunnel. The Android routing table never points at OpenVPN.
3. Only then does the service build the device TUN (all IPv4/IPv6, apps' DNS
   `1.1.1.1`). Xray's built-in `tun` inbound reads it, which is the same mechanism
   v2rayNG uses (`xray.tun.fd`).
4. The app excludes itself from its own VPN (`addDisallowedApplication`), so
   OpenVPN's transport socket goes straight to the real network.

## Libraries used

| Need | Library | Version |
|---|---|---|
| OpenVPN client | [OpenVPN 3 core](https://github.com/OpenVPN/openvpn3) (official, OpenVPN Inc.) | `release/3.11.7` + one backported fix |
| TLS for OpenVPN 3 | [mbed TLS](https://github.com/Mbed-TLS/mbedtls) | 3.6.7 (LTS) |
| OpenVPN 3 deps | asio (with openvpn3's patches), lz4, fmt | asio-1-24-0, 1.10.0, 12.2.0 |
| VLESS client | [Xray-core](https://github.com/XTLS/Xray-core) | v26.10.10 (same commit as the official [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)) |
| Userspace TCP/IP | gVisor netstack (Xray-core's pinned version) | |
| Go → Android | gomobile | |

Notes on the choices:

* **"openconnect3" vs OpenVPN 3.** *OpenConnect* is a different VPN protocol (Cisco
  AnyConnect / ocserv) and can't talk to an OpenVPN server. This app uses **OpenVPN 3**,
  the official client library behind OpenVPN Connect and the OpenVPN 3 Linux client.
  If you actually need OpenConnect, the same socketpair design works with libopenconnect,
  but the native side would have to be replaced.
* **Xray-core vs v2fly/v2ray-core.** v2fly v5 only has basic VLESS: no REALITY, no
  `xtls-rprx-vision`, no XHTTP. Most VLESS servers today use those, so the engine
  embeds Xray-core, as v2rayNG does. Its config format is the familiar v2ray JSON.
* **mbed TLS instead of OpenSSL** keeps the NDK build to plain CMake. The release tag
  needs one fix to work with mbed TLS ≥ 3.6.3; it is backported from openvpn3 master
  in `app/src/main/cpp/patches/`.

## Layout

```
app/                      Android app (Kotlin, no AndroidX)
  src/main/cpp/           OpenVPN 3 JNI library (CMake, fetches deps at configure time)
    chain_client.hpp      OpenVPN 3 client with socketpair "tun" (JNI-free)
    ovpn3_jni.cpp         JNI bridge to io.github.meshkati.ovpnvless.OpenVpn3
  src/main/java/.../      MainActivity, ChainVpnService, OpenVpn3, VpnState, Settings
engine/                   Go: Xray-core + gVisor stack, gomobile API, vless:// parser
scripts/build-engine.sh   gomobile bind → app/libs/engine.aar
hosttest/                 Linux end-to-end test of the whole chain (no Android needed)
```

## Building

Requirements: Android SDK with NDK and CMake 3.22.1 (Android Studio's SDK Manager),
JDK 17+, Go ≥ 1.27, git (CMake clones the native dependencies).

```sh
export ANDROID_HOME=~/Android/Sdk          # and ANDROID_NDK_HOME if needed
scripts/build-engine.sh                     # builds app/libs/engine.aar
./gradlew :app:assembleDebug                # builds the APK, including the OpenVPN 3 .so
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Using the app

1. Paste or import your `.ovpn` profile. Inline `<ca>`, `<cert>`, `<key>`,
   `<tls-auth>`, `<tls-crypt>` and `<tls-crypt-v2>` work. A username/password-only
   profile (no client cert) also works.
2. Enter the OpenVPN username/password if the profile has `auth-user-pass`.
3. Paste a VLESS share link, for example
   `vless://UUID@host:443?security=reality&sni=…&pbk=…&sid=…&fp=chrome&type=tcp&flow=xtls-rprx-vision`.
   Supported: `type=tcp|ws|grpc|httpupgrade|xhttp`, `security=none|tls|reality`.
   You can also paste a full Xray JSON config instead; it must contain a `tun` inbound.
   The VLESS host may be a name that only resolves inside the OpenVPN network. It is
   resolved with the DNS servers OpenVPN pushes.
4. Tap **Connect** and accept the VPN prompt. The log shows OpenVPN 3 and Xray output.

## Testing

* `cd engine && go test ./...` checks that Xray-core accepts every config the
  `vless://` parser produces.
* `sudo hosttest/run-e2e.sh` (Linux, needs `openvpn dnsmasq openssl python3 go cmake ninja`)
  runs the full chain on a PC. It starts an OpenVPN 2.6 server (user/pass + tls-crypt),
  a DNS server reachable only inside the tunnel, and an Xray **VLESS + REALITY + Vision**
  server that listens only on the tunnel address. It then runs the real `chain::Client`
  (OpenVPN 3 core) and the Go engine, and downloads files through SOCKS → VLESS → OpenVPN.
  It also checks that the VLESS server saw the client at its OpenVPN address.

## Status and limitations

What was verified while writing this, on Linux x86_64:

* `run-e2e.sh` passes. An 8 MB download arrives byte-identical through the real
  OpenVPN 3 core → socketpair → gVisor → Xray VLESS/REALITY/Vision chain.
* The JNI library builds and links against the JDK. A JVM run under `-Xcheck:jni`
  driving the real OpenVPN 3 session works, using the compiled Kotlin `OpenVpn3`
  class: connect, `onTun`, stop from another thread, clean return.
* The Kotlin sources compile with `-Werror` against the Android 16 (API 36) framework.
* The Go engine cross-compiles for android/arm64, arm and amd64.

**Not yet verified:** the Gradle/APK build and a run on a real device. The build
environment had no Android SDK/NDK. Expect to possibly adjust AGP/NDK versions to your
installation.

Limitations of this minimal version:

* No OpenVPN static/dynamic challenge (OTP), private-key passphrase, or external PKI.
* Credentials are stored unencrypted in app-private SharedPreferences.
* Apps' DNS is fixed to `1.1.1.1` (carried over VLESS). There are no routing rules or
  per-app selection; everything except this app goes through the chain.
* OpenVPN `route`/`redirect-gateway` options are ignored. Only Xray uses the OpenVPN
  tunnel, so routes have no effect.

## Licenses

OpenVPN 3 core: AGPLv3 or MPL-2.0. Xray-core: MPL-2.0. gVisor: Apache-2.0.
mbed TLS: Apache-2.0 or GPL-2.0-or-later. Check that your distribution complies with them.

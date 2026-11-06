# To do on a local machine: Android SDK, NDK, emulator or phone

The security pass (PRs #22–#26) was written in a cloud session that couldn't
download the Android SDK. There, Kotlin was compiled against Robolectric's
`android-all` jar (API 36) and the app's JUnit tests passed. Go tests passed.
The native code was built and tested on Linux with CMake. Nothing was built
with Gradle/AGP or the NDK, and nothing was run on an emulator or phone. This
list is what still needs to be checked. Delete it once it's done.

In the commands below:

- Debug package: `io.github.meshkati.ovpnvless.debug`
- Activity: `io.github.meshkati.ovpnvless.MainActivity`
- `PKG` is the package of the build under test.

## For every PR branch

- [ ] `scripts/build-engine.sh` (gomobile + NDK; PR #22 adds `Engine.fetch`).
- [ ] `./gradlew :app:testDebugUnitTest :app:assembleDebug`
- [ ] `./gradlew :app:lintDebug`, and compare its warnings with `main`.

## #22: traffic leaks (`fix/vpn-traffic-leaks`)

- [ ] With VLESS connected, About → **Check for updates** works. While
      connected, the app's UID must have no socket to `api.github.com` (only
      the one to the OpenVPN server):
      `adb shell cat /proc/net/tcp /proc/net/tcp6`, filtered by the app's UID.
- [ ] While a VLESS session is connecting, the update check doesn't run.
- [ ] OpenVPN server that pushes **no DNS** and a VLESS link with a hostname:
      the log shows `OpenVPN pushed no DNS servers; looking names up at
      [1.1.1.1 8.8.8.8] inside the tunnel`, and VLESS connects. A packet
      capture on the Wi-Fi side shows no DNS query for that name.
- [ ] Paste an Xray JSON config with `{"protocol":"socks","port":10808}` (no
      `listen`). Connecting fails, and the error names the inbound. With
      `"listen":"127.0.0.1"` it connects, and the log has the warning.
- [ ] Reconnect with new settings (restart the OpenVPN server, switch Wi-Fi ↔
      mobile so the tunnel IP changes). Capture on the host or router: no
      apps' traffic leaves outside the tunnel while `onTun` rebuilds the TUN.

## #23: sealed secrets (`fix/encrypt-stored-secrets`)

- [ ] **Upgrade path:** install `main`, set up a profile with a password and a
      VLESS link, then install this branch over it. The profile still connects.
      `adb shell run-as PKG cat shared_prefs/profile_1.xml` shows
      `sealed:v1:` values for ovpn/username/password/vless.
- [ ] New profile, copy, delete + undo, profile switch all still work.
- [ ] **Always-on VPN** (+ "Block connections without VPN"): connects after a
      reboot, before the screen is unlocked once.
- [ ] Screenshot attempt on the OpenVPN, VLESS and `.ovpn` text screens is
      blocked, and they show blank in recent apps. Home and Log screens can
      still be screenshotted.
- [ ] Home screen stays smooth while the log scrolls fast (Keystore cache).
- [ ] Android 12+: `adb shell bmgr backupnow PKG` backs up nothing. A
      device-to-device transfer (setup of a new phone) doesn't bring the
      profiles (`data_extraction_rules.xml`).

## #24: logs and crash reports (`fix/redact-logs-and-reports`)

- [ ] NDK build of `ovpn3_jni.cpp`. A **release** build
      (`./gradlew :app:assembleRelease`, unsigned is fine):
      `adb logcat -s ovpn-vless ovpn3` stays silent while connecting.
      A debug build still logs.
- [ ] Log screen → Copy on Android 13+: the clipboard pop-up shows no
      preview. Pasting still works.
- [ ] A crash report (Report and Share) still opens, and the password, IPv6
      addresses and link `sni`/`host`/`path` are replaced.

## #25: IPC and input (`fix/ipc-and-input-hardening`)

- [ ] Widget **Connect** (toggle widget and chain widget) and the
      notification's **Resume** still connect, including a profile with an
      authenticator code (the screen opens with the code field focused).
- [ ] `adb shell am start -n io.github.meshkati.ovpnvless.debug/io.github.meshkati.ovpnvless.MainActivity --ez connect true`
      only opens the home screen and doesn't connect.
- [ ] Import a normal `.ovpn`. Import a file over 1 MiB: the toast says it's
      too big, and nothing changes.

## #26: OpenVPN certificate checks (`fix/openvpn-tls-hardening`)

- [ ] NDK build with OpenVPN 3 `release/3.11.8` (CMake FetchContent). The
      mbed TLS hostname patch applies. About shows core `3.11.8`.
- [ ] The Access Server profile that was verified on the Pixel (TCP,
      `tls-crypt-v2`, authenticator code) still connects, with and without
      VLESS.
- [ ] A profile without `remote-cert-tls`: the log shows the `added
      remote-cert-tls server` line, and it connects to a server with an
      Easy-RSA/Access Server certificate.
- [ ] A profile with `ns-cert-type server`: the log shows the rewrite, and it
      connects.
- [ ] **Check by name** notice. Use a server certificate without the TLS
      server usage (no `extendedKeyUsage=serverAuth`) and a profile with no
      server check:
  - [ ] Connecting fails with the certificate error. The notice appears
        under it, showing the certificate's name. **Not now** hides it.
  - [ ] **Check by name** shows the toast. The `.ovpn` text screen shows
        `verify-x509-name <name> name` at the end. Connecting again works.
  - [ ] With the same server, a profile that has `remote-cert-tls server`
        itself gets the certificate error and no notice.
  - [ ] Switch to another profile while the notice shows: it goes away
        (the offer belongs to the profile that failed).
  - [ ] Layout in light and dark theme, and with a long name.
- [ ] The **OpenVPN-only route** on a phone. It was never tried on one; see
      the README's leak-check list.

## Not tied to a PR (from the README's "To do: leak checks")

- [ ] DNS leak, WebRTC and HTTP/3 exit-IP checks on a phone (dnsleaktest,
      browserleaks), also with Android Private DNS and Chrome Secure DNS on.
- [ ] Kill switch: Always-on + "Block connections without VPN", with the app
      crashing or OpenVPN dropping for good.

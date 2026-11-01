package io.github.meshkati.ovpnvless

/** JNI binding for the OpenVPN 3 core client (src/main/cpp/ovpn3_jni.cpp). */
object OpenVpn3 {
    init {
        System.loadLibrary("ovpn3jni")
    }

    /** Called on the thread running [run]. */
    interface Callbacks {
        fun onLog(line: String)
        fun onEvent(name: String, info: String, error: Boolean, fatal: Boolean)

        /**
         * The tunnel is up ([Route.VIA_VLESS]). [fd] is the app end of a
         * datagram socketpair that carries the tunnel's raw IP packets. Return
         * true once it has been handed to the engine; on false the native side
         * closes it.
         */
        fun onTun(fd: Int, addresses: Array<String>, dns: Array<String>, mtu: Int): Boolean

        /**
         * The tunnel is up ([Route.OPENVPN_ONLY]). Build the device TUN with
         * these settings and return its fd, detached: OpenVPN 3 reads and
         * writes it and closes it. Return -1 to fail the connection.
         * [addresses] and [routes] are CIDR, IPv4 and IPv6.
         */
        fun onDeviceTun(
            addresses: Array<String>, routes: Array<String>, dns: Array<String>, searchDomains: Array<String>, mtu: Int
        ): Int
    }

    /**
     * Runs one session and blocks until it ends. [response] answers the profile's
     * static-challenge (an authenticator code), or is empty. With [deviceTun]
     * OpenVPN gets the device TUN ([Callbacks.onDeviceTun]), otherwise a
     * socketpair for the VLESS engine ([Callbacks.onTun]). Returns null on a
     * clean exit, else an error.
     */
    external fun run(
        profile: String, username: String, password: String, response: String, deviceTun: Boolean,
        callbacks: Callbacks,
    ): String?

    /** Asks the running session to disconnect; [run] then returns. */
    external fun stop()

    external fun coreVersion(): String
}

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
         * The tunnel is up. [fd] is the app end of a datagram socketpair that
         * carries the tunnel's raw IP packets. Return true once it has been
         * handed to the engine; on false the native side closes it.
         */
        fun onTun(fd: Int, addresses: Array<String>, dns: Array<String>, mtu: Int): Boolean
    }

    /**
     * Runs one session and blocks until it ends. [response] answers the profile's
     * static-challenge (an authenticator code), or is empty. Returns null on a
     * clean exit, else an error.
     */
    external fun run(
        profile: String, username: String, password: String, response: String, callbacks: Callbacks
    ): String?

    /** Asks the running session to disconnect; [run] then returns. */
    external fun stop()

    external fun coreVersion(): String
}

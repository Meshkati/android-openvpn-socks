package io.github.meshkati.ovpnvless

/** Where apps' traffic goes once it is in OpenVPN. */
enum class Route {
    /**
     * apps → OpenVPN → internet. OpenVPN gets the device TUN, with the
     * addresses, routes and DNS its server pushes, like any OpenVPN app.
     */
    OPENVPN_ONLY,

    /** apps → OpenVPN → VLESS → internet. All IPv4 traffic goes to the VLESS server. */
    VIA_VLESS;

    companion object {
        /**
         * VLESS is used only when a VLESS setting is saved and [useVless] (the
         * switch on the home screen) is on. An unreadable setting still counts
         * as saved, so connecting reports what is wrong with it.
         */
        fun choose(vless: VlessConfig?, useVless: Boolean): Route =
            if (vless != null && useVless) VIA_VLESS else OPENVPN_ONLY
    }
}

/** An address or network with its prefix length, as OpenVPN 3 reports them: "10.8.0.6/24", "::/0". */
data class Cidr(val address: String, val prefixLength: Int) {
    companion object {
        /** Throws IllegalArgumentException if [cidr] has no "/<prefix length>". */
        fun parse(cidr: String): Cidr {
            val address = cidr.substringBefore('/')
            val prefix = cidr.substringAfter('/', "").toIntOrNull()
            require(address.isNotEmpty() && prefix != null) { "not a CIDR: $cidr" }
            return Cidr(address, prefix)
        }
    }
}

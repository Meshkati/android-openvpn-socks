package io.github.meshkati.ovpnvless

/** What the home screen shows, derived from [VpnState]. */
enum class Stage { NOT_SET_UP, READY, CONNECTING, CONNECTED, DISCONNECTING, FAILED }

/** Where OpenVPN is while connecting, from its events. */
enum class Step { STARTING, FINDING_SERVER, CONNECTING, GETTING_SETTINGS, SETTING_UP, RECONNECTING }

/** Why the last session ended, for a plain-language message. */
enum class Failure { PROFILE, CODE_NEEDED, CREDENTIALS, SIGN_IN, UNREACHABLE, CERTIFICATE, VLESS, OTHER }

object Status {

    /**
     * [detail] is [VpnState.detail]: after a disconnect it holds the error, or
     * is empty for a clean exit. [ready] means a profile and a VLESS setting
     * are both filled in.
     */
    fun stage(phase: VpnState.Phase, detail: String, ready: Boolean): Stage = when (phase) {
        VpnState.Phase.CONNECTING -> Stage.CONNECTING
        VpnState.Phase.CONNECTED -> Stage.CONNECTED
        VpnState.Phase.DISCONNECTING -> Stage.DISCONNECTING
        VpnState.Phase.DISCONNECTED -> when {
            !ready -> Stage.NOT_SET_UP
            detail.isNotEmpty() -> Stage.FAILED
            else -> Stage.READY
        }
    }

    /** Maps the lower-cased OpenVPN event name the service puts in the detail. */
    fun step(detail: String): Step = when (detail.lowercase()) {
        "resolve" -> Step.FINDING_SERVER
        "wait", "connecting" -> Step.CONNECTING
        "get_config" -> Step.GETTING_SETTINGS
        "assign_ip" -> Step.SETTING_UP
        "reconnecting" -> Step.RECONNECTING
        else -> Step.STARTING
    }

    /**
     * [error] is what the OpenVPN session returned (see chain_client.hpp):
     * "profile error: …", "credentials error: …", "<STATUS>: <message>", or
     * free text. [vlessError] is set when the engine failed to start.
     */
    fun failure(error: String, vlessError: String): Failure {
        if (vlessError.isNotEmpty()) return Failure.VLESS
        if (error.startsWith("profile error")) return Failure.PROFILE
        if (error.startsWith("credentials error")) return Failure.CREDENTIALS
        if (error.contains("enter the one-time code")) return Failure.CODE_NEEDED
        val code = errorCode(error) ?: return Failure.OTHER
        return when {
            code == "AUTH_FAILED" -> Failure.SIGN_IN
            code.startsWith("CERT") -> Failure.CERTIFICATE
            code.contains("TIMEOUT") || code.startsWith("NETWORK") ||
                code.contains("TRANSPORT") || code.contains("RESOLVE") -> Failure.UNREACHABLE
            else -> Failure.OTHER
        }
    }

    /** The OpenVPN 3 status code at the start of [error], e.g. "AUTH_FAILED". */
    fun errorCode(error: String): String? =
        Regex("^([A-Z][A-Z0-9_]+):").find(error)?.groupValues?.get(1)
}

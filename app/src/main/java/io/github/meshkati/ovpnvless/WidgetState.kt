package io.github.meshkati.ovpnvless

/**
 * What the home-screen widgets show ([Widgets]), worked out the way the home
 * screen works out its toggle and route card. Plain Kotlin, so it is tested
 * on the JVM.
 */
data class WidgetState(
    val button: Button,
    /** What tapping the round button does. */
    val action: Action,
    val openVpn: Hop,
    val vless: Hop,
    /** Connected through OpenVPN and VLESS: the line between the two steps turns solid. */
    val chainWhole: Boolean,
    /** The home screen's public IP check, shown as a small badge on the VLESS step. */
    val ip: Ip,
) {
    enum class Button {
        OFF, BUSY,
        /** Connected through OpenVPN and VLESS. */
        ON,
        /** Connected with OpenVPN only (VLESS switched off or not added). */
        HALF,
        ERROR,
        /** The app has to be opened first: nothing set up, or the profile asks for a code. */
        LOCKED,
    }

    enum class Action {
        CONNECT, DISCONNECT,
        /** Opens the home screen, which connects from there (VPN permission, code). */
        OPEN_AND_CONNECT,
        OPEN,
    }

    /** A step's dot. OFF: the step is not part of the route. */
    enum class Dot { IDLE, BUSY, OK, ERROR, OFF }

    enum class Meta {
        NOT_SET_UP, NOT_STARTED, CODE_NEEDED, CONNECTING, RECONNECTING, WAITING,
        CONNECTED, CONNECTED_ALL_APPS, DISCONNECTING, STOPPED,
        /** OpenVPN failed; [Hop.failure] says why. */
        FAILED,
        VLESS_FAILED, VLESS_INVALID, VLESS_OFF, VLESS_NOT_ADDED,
    }

    data class Hop(val dot: Dot, val meta: Meta, val failure: Failure? = null)

    enum class Ip { NONE, CHECKING, OK, FAILED }

    /** What the widgets need to know besides [VpnState]. */
    data class Setup(
        val hasProfile: Boolean,
        /** The profile asks for an authenticator code each time. */
        val needsCode: Boolean,
        val vless: VlessConfig?,
        val useVless: Boolean,
        /** The user has allowed this app to be a VPN ([android.net.VpnService.prepare]). */
        val vpnAllowed: Boolean,
    )

    companion object {
        fun of(
            phase: VpnState.Phase,
            detail: String,
            vlessError: String,
            sessionRoute: Route,
            setup: Setup,
            ipCheck: IpCheck.State,
        ): WidgetState {
            val stage = Status.stage(phase, detail, ready = setup.hasProfile)
            val failure = if (stage == Stage.FAILED) Status.failure(detail, vlessError) else null
            // While a session runs, show the route it was started with.
            val route = if (phase == VpnState.Phase.DISCONNECTED) Route.choose(setup.vless, setup.useVless) else sessionRoute
            val idle = stage == Stage.READY || stage == Stage.FAILED

            val button = when {
                stage == Stage.NOT_SET_UP || (idle && setup.needsCode) -> Button.LOCKED
                stage == Stage.READY -> Button.OFF
                stage == Stage.FAILED -> Button.ERROR
                stage == Stage.CONNECTED -> if (route == Route.VIA_VLESS) Button.ON else Button.HALF
                else -> Button.BUSY
            }
            val action = when (stage) {
                Stage.NOT_SET_UP, Stage.DISCONNECTING -> Action.OPEN
                Stage.READY, Stage.FAILED ->
                    if (setup.needsCode || !setup.vpnAllowed) Action.OPEN_AND_CONNECT else Action.CONNECT
                Stage.CONNECTING, Stage.CONNECTED -> Action.DISCONNECT
            }
            val chainWhole = stage == Stage.CONNECTED && route == Route.VIA_VLESS
            val ip = if (!chainWhole) Ip.NONE else when (ipCheck) {
                IpCheck.State.IDLE -> Ip.NONE
                IpCheck.State.RUNNING -> Ip.CHECKING
                IpCheck.State.DONE -> Ip.OK
                IpCheck.State.FAILED -> Ip.FAILED
            }
            return WidgetState(
                button, action,
                openVpnHop(stage, failure, detail, setup),
                vlessHop(stage, failure, route, setup),
                chainWhole, ip,
            )
        }

        private fun openVpnHop(stage: Stage, failure: Failure?, detail: String, setup: Setup): Hop = when (stage) {
            Stage.NOT_SET_UP -> Hop(Dot.IDLE, Meta.NOT_SET_UP)
            Stage.READY -> Hop(Dot.IDLE, if (setup.needsCode) Meta.CODE_NEEDED else Meta.NOT_STARTED)
            Stage.CONNECTING -> Hop(
                Dot.BUSY, if (Status.step(detail) == Step.RECONNECTING) Meta.RECONNECTING else Meta.CONNECTING,
            )
            Stage.CONNECTED -> Hop(Dot.OK, Meta.CONNECTED)
            Stage.DISCONNECTING -> Hop(Dot.BUSY, Meta.DISCONNECTING)
            Stage.FAILED ->
                if (failure == Failure.VLESS) Hop(Dot.IDLE, Meta.STOPPED)
                else Hop(Dot.ERROR, Meta.FAILED, failure)
        }

        private fun vlessHop(stage: Stage, failure: Failure?, route: Route, setup: Setup): Hop = when {
            setup.vless == null -> Hop(Dot.OFF, Meta.VLESS_NOT_ADDED)
            route == Route.OPENVPN_ONLY -> Hop(Dot.OFF, Meta.VLESS_OFF)
            else -> when (stage) {
                Stage.CONNECTING -> Hop(Dot.IDLE, Meta.WAITING)
                Stage.CONNECTED -> Hop(Dot.OK, Meta.CONNECTED_ALL_APPS)
                Stage.DISCONNECTING -> Hop(Dot.BUSY, Meta.DISCONNECTING)
                Stage.FAILED ->
                    if (failure == Failure.VLESS) Hop(Dot.ERROR, Meta.VLESS_FAILED)
                    else Hop(Dot.IDLE, Meta.NOT_STARTED)
                Stage.NOT_SET_UP, Stage.READY ->
                    if (setup.vless == VlessConfig.Invalid) Hop(Dot.ERROR, Meta.VLESS_INVALID)
                    else Hop(Dot.IDLE, Meta.NOT_STARTED)
            }
        }
    }
}

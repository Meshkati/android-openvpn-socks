package io.github.meshkati.ovpnvless

import io.github.meshkati.ovpnvless.WidgetState.Action
import io.github.meshkati.ovpnvless.WidgetState.Button
import io.github.meshkati.ovpnvless.WidgetState.Dot
import io.github.meshkati.ovpnvless.WidgetState.Hop
import io.github.meshkati.ovpnvless.WidgetState.Ip
import io.github.meshkati.ovpnvless.WidgetState.Meta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateTest {

    private val link = VlessConfig.parse("vless://id@edge.example.net:443?type=ws&security=tls")

    private val setUp = WidgetState.Setup(
        hasProfile = true, needsCode = false, vless = link, useVless = true, vpnAllowed = true,
    )

    private fun state(
        phase: VpnState.Phase = VpnState.Phase.DISCONNECTED,
        detail: String = "",
        vlessError: String = "",
        route: Route = Route.VIA_VLESS,
        setup: WidgetState.Setup = setUp,
        ip: IpCheck.State = IpCheck.State.IDLE,
    ) = WidgetState.of(phase, detail, vlessError, route, setup, ip)

    @Test
    fun connectedThroughBothIsTheWholeChain() {
        // when
        val s = state(VpnState.Phase.CONNECTED, "10.8.0.6/24")

        // then
        assertEquals(Button.ON, s.button)
        assertEquals(Action.DISCONNECT, s.action)
        assertEquals(Hop(Dot.OK, Meta.CONNECTED), s.openVpn)
        assertEquals(Hop(Dot.OK, Meta.CONNECTED_ALL_APPS), s.vless)
        assertTrue(s.chainWhole)
    }

    @Test
    fun connectedWithOpenVpnOnlyIsHalf() {
        // when
        val s = state(VpnState.Phase.CONNECTED, route = Route.OPENVPN_ONLY)

        // then
        assertEquals(Button.HALF, s.button)
        assertEquals(Hop(Dot.OFF, Meta.VLESS_OFF), s.vless)
        assertFalse(s.chainWhole)
    }

    @Test
    fun aRunningSessionKeepsItsRouteWhenTheSwitchChanges() {
        // given: connected through VLESS, then the switch was turned off
        val setup = setUp.copy(useVless = false)

        // when
        val s = state(VpnState.Phase.CONNECTED, route = Route.VIA_VLESS, setup = setup)

        // then
        assertEquals(Button.ON, s.button)
    }

    @Test
    fun withoutVlessTheVlessStepSaysSo() {
        // when
        val s = state(setup = setUp.copy(vless = null))

        // then
        assertEquals(Hop(Dot.OFF, Meta.VLESS_NOT_ADDED), s.vless)
    }

    @Test
    fun readyConnectsFromTheWidget() {
        // when
        val s = state()

        // then
        assertEquals(Button.OFF, s.button)
        assertEquals(Action.CONNECT, s.action)
        assertEquals(Hop(Dot.IDLE, Meta.NOT_STARTED), s.openVpn)
        assertEquals(Hop(Dot.IDLE, Meta.NOT_STARTED), s.vless)
    }

    @Test
    fun withoutVpnPermissionTheAppAsksForIt() {
        // when
        val s = state(setup = setUp.copy(vpnAllowed = false))

        // then
        assertEquals(Button.OFF, s.button)
        assertEquals(Action.OPEN_AND_CONNECT, s.action)
    }

    @Test
    fun aProfileThatAsksForACodeIsLocked() {
        // when
        val s = state(setup = setUp.copy(needsCode = true))

        // then
        assertEquals(Button.LOCKED, s.button)
        assertEquals(Action.OPEN_AND_CONNECT, s.action)
        assertEquals(Hop(Dot.IDLE, Meta.CODE_NEEDED), s.openVpn)
    }

    @Test
    fun aConnectedSessionWithACodeCanStillBeStopped() {
        // when
        val s = state(VpnState.Phase.CONNECTED, setup = setUp.copy(needsCode = true))

        // then
        assertEquals(Button.ON, s.button)
        assertEquals(Action.DISCONNECT, s.action)
    }

    @Test
    fun nothingSetUpOpensTheApp() {
        // when
        val s = state(setup = setUp.copy(hasProfile = false, vless = null))

        // then
        assertEquals(Button.LOCKED, s.button)
        assertEquals(Action.OPEN, s.action)
        assertEquals(Hop(Dot.IDLE, Meta.NOT_SET_UP), s.openVpn)
    }

    @Test
    fun connectingShowsOpenVpnBusyAndVlessWaiting() {
        // when
        val s = state(VpnState.Phase.CONNECTING, "get_config")

        // then
        assertEquals(Button.BUSY, s.button)
        assertEquals(Action.DISCONNECT, s.action)
        assertEquals(Hop(Dot.BUSY, Meta.CONNECTING), s.openVpn)
        assertEquals(Hop(Dot.IDLE, Meta.WAITING), s.vless)
        assertFalse(s.chainWhole)
    }

    @Test
    fun reconnectingIsShownAsSuch() {
        // when
        val s = state(VpnState.Phase.CONNECTING, "reconnecting")

        // then
        assertEquals(Meta.RECONNECTING, s.openVpn.meta)
    }

    @Test
    fun disconnectingCanOnlyOpenTheApp() {
        // when
        val s = state(VpnState.Phase.DISCONNECTING)

        // then
        assertEquals(Button.BUSY, s.button)
        assertEquals(Action.OPEN, s.action)
    }

    @Test
    fun aVlessFailureMarksTheVlessStep() {
        // when
        val s = state(detail = "OpenVPN error", vlessError = "bad link")

        // then
        assertEquals(Button.ERROR, s.button)
        assertEquals(Action.CONNECT, s.action)
        assertEquals(Hop(Dot.IDLE, Meta.STOPPED), s.openVpn)
        assertEquals(Hop(Dot.ERROR, Meta.VLESS_FAILED), s.vless)
    }

    @Test
    fun anOpenVpnFailureMarksTheOpenVpnStepWithTheReason() {
        // when
        val s = state(detail = "AUTH_FAILED: bad password")

        // then
        assertEquals(Hop(Dot.ERROR, Meta.FAILED, Failure.SIGN_IN), s.openVpn)
        assertEquals(Hop(Dot.IDLE, Meta.NOT_STARTED), s.vless)
    }

    @Test
    fun anUnreadableLinkIsAnErrorBeforeConnecting() {
        // when
        val s = state(setup = setUp.copy(vless = VlessConfig.Invalid))

        // then
        assertEquals(Hop(Dot.ERROR, Meta.VLESS_INVALID), s.vless)
    }

    @Test
    fun theIpCheckShowsOnlyOnTheWholeChain() {
        // given
        val checks = mapOf(
            IpCheck.State.IDLE to Ip.NONE,
            IpCheck.State.RUNNING to Ip.CHECKING,
            IpCheck.State.DONE to Ip.OK,
            IpCheck.State.FAILED to Ip.FAILED,
        )

        for ((check, expected) in checks) {
            // when
            val whole = state(VpnState.Phase.CONNECTED, ip = check)
            val openVpnOnly = state(VpnState.Phase.CONNECTED, route = Route.OPENVPN_ONLY, ip = check)

            // then
            assertEquals(expected, whole.ip)
            assertEquals(Ip.NONE, openVpnOnly.ip)
        }
    }

    @Test
    fun anOldIpCheckIsNotShownOnceDisconnected() {
        // when
        val s = state(ip = IpCheck.State.DONE)

        // then
        assertEquals(Ip.NONE, s.ip)
    }
}

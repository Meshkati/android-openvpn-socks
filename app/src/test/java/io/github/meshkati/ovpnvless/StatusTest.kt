package io.github.meshkati.ovpnvless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusTest {

    @Test
    fun disconnectedWithoutSetupIsNotSetUp() {
        assertEquals(Stage.NOT_SET_UP, Status.stage(VpnState.Phase.DISCONNECTED, "", ready = false))
    }

    @Test
    fun disconnectedCleanlyIsReady() {
        assertEquals(Stage.READY, Status.stage(VpnState.Phase.DISCONNECTED, "", ready = true))
    }

    @Test
    fun disconnectedWithAnErrorIsFailed() {
        assertEquals(Stage.FAILED, Status.stage(VpnState.Phase.DISCONNECTED, "AUTH_FAILED: x", ready = true))
    }

    @Test
    fun connectingStaysConnectingWhateverTheDetail() {
        assertEquals(Stage.CONNECTING, Status.stage(VpnState.Phase.CONNECTING, "get_config", ready = true))
    }

    @Test
    fun openVpnEventsMapToSteps() {
        // given
        val events = mapOf(
            "starting OpenVPN" to Step.STARTING,
            "resolve" to Step.FINDING_SERVER,
            "wait" to Step.CONNECTING,
            "connecting" to Step.CONNECTING,
            "get_config" to Step.GETTING_SETTINGS,
            "assign_ip" to Step.SETTING_UP,
            "reconnecting" to Step.RECONNECTING,
        )

        // when
        val wrong = events.filter { (event, step) -> Status.step(event) != step }

        // then
        assertEquals(emptyMap<String, Step>(), wrong)
    }

    @Test
    fun authFailedIsASignInFailure() {
        assertEquals(Failure.SIGN_IN, Status.failure("AUTH_FAILED: auth failure", ""))
    }

    @Test
    fun timeoutIsUnreachable() {
        assertEquals(Failure.UNREACHABLE, Status.failure("CONNECTION_TIMEOUT: timed out", ""))
    }

    @Test
    fun certificateErrorIsACertificateFailure() {
        assertEquals(Failure.CERTIFICATE, Status.failure("CERT_VERIFY_FAIL: bad cert", ""))
    }

    @Test
    fun profileErrorIsAProfileFailure() {
        assertEquals(Failure.PROFILE, Status.failure("profile error: option_error: remote", ""))
    }

    @Test
    fun credentialsErrorIsACredentialsFailure() {
        assertEquals(Failure.CREDENTIALS, Status.failure("credentials error: missing", ""))
    }

    @Test
    fun missingChallengeResponseAsksForTheCode() {
        // given
        val error = "the profile asks for \"Enter code\"; enter the one-time code"

        // then
        assertEquals(Failure.CODE_NEEDED, Status.failure(error, ""))
    }

    @Test
    fun vlessErrorWinsOverTheOpenVpnError() {
        assertEquals(Failure.VLESS, Status.failure("TUN_SETUP_FAILED: x", "bad config"))
    }

    @Test
    fun unknownTextIsOther() {
        assertEquals(Failure.OTHER, Status.failure("java.lang.IllegalStateException: boom", ""))
    }

    @Test
    fun errorCodeNeedsAnUpperCasePrefix() {
        assertNull(Status.errorCode("profile error: x"))
    }
}

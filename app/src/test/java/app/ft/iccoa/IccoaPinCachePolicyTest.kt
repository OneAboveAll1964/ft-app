package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.ConnectException

class IccoaPinCachePolicyTest {
    @Test fun realPinMacMismatchInvalidatesRememberedSecret() {
        assertTrue(IccoaPinCachePolicy.shouldClearAfterAuthFailure(
            IccoaHandshakeException(IccoaHandshakeException.Reason.PIN_MAC)))
    }

    @Test fun receiverExplicitNewCodeRequestInvalidatesSecret() {
        assertTrue(IccoaPinCachePolicy.shouldClearAfterAuthFailure(
            IccoaHandshakeException(IccoaHandshakeException.Reason.RESPONSE_RESULT, 1L)))
    }

    @Test fun networkErrorsNeverInvalidateValidRememberedPin() {
        assertFalse(IccoaPinCachePolicy.shouldClearAfterAuthFailure(SocketTimeoutException()))
        assertFalse(IccoaPinCachePolicy.shouldClearAfterAuthFailure(ConnectException()))
    }

    @Test fun unrelatedHandshakeFailuresDoNotImplyThePinWasRejected() {
        assertFalse(IccoaPinCachePolicy.shouldClearAfterAuthFailure(
            IccoaHandshakeException(IccoaHandshakeException.Reason.RESPONSE_LANE)))
        assertFalse(IccoaPinCachePolicy.shouldClearAfterAuthFailure(
            IccoaHandshakeException(IccoaHandshakeException.Reason.AGREEMENT_SIGNATURE)))
    }

    @Test fun receiverInternalErrorAndConfirmPromptKeepStoredCredential() {
        assertFalse(IccoaPinCachePolicy.shouldClearAfterAuthFailure(
            IccoaHandshakeException(IccoaHandshakeException.Reason.RESPONSE_RESULT, 3L)))
        assertFalse(IccoaPinCachePolicy.shouldClearAfterAuthFailure(
            IccoaHandshakeException(IccoaHandshakeException.Reason.RESPONSE_RESULT, 2L)))
    }
}

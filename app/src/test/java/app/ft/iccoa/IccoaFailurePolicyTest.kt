package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.crypto.AEADBadTagException

class IccoaFailurePolicyTest {
    @Test fun onlyIoFailuresAllowTransportRecovery() {
        listOf(EOFException(), SocketException(), SocketTimeoutException()).forEach {
            assertEquals(IccoaCloseReason.TRANSPORT_FAILURE, IccoaFailurePolicy.channel(it))
        }
        listOf(AEADBadTagException(), IllegalArgumentException(), IllegalStateException(),
            IccoaHandshakeException(IccoaHandshakeException.Reason.HEADER_CRC)).forEach {
            assertEquals(IccoaCloseReason.FAILED, IccoaFailurePolicy.channel(it))
        }
    }

    @Test fun quickResultOneOrTwoRequiresNewNormalPinAndOtherAuthFailuresStop() {
        for (quick in listOf(false, true)) for (result in 0L..4L) {
            val error = IccoaHandshakeException(IccoaHandshakeException.Reason.RESPONSE_RESULT, result)
            assertEquals(if (quick && result in 1L..2L) IccoaCloseReason.NORMAL_REQUIRED else IccoaCloseReason.FAILED,
                IccoaFailurePolicy.authentication("read-response", quick, error))
        }
        assertEquals(IccoaCloseReason.FAILED, IccoaFailurePolicy.authentication("read-response", true,
            IccoaHandshakeException(IccoaHandshakeException.Reason.AGREEMENT_SIGNATURE)))
    }

    @Test fun ambiguousAuthDeliveryCannotAutomaticallyReplayTheRequest() {
        assertEquals(IccoaCloseReason.TRANSPORT_FAILURE,
            IccoaFailurePolicy.authentication("tcp-connect", true, SocketTimeoutException()))
        for (stage in listOf("send-request", "read-response", "send-confirm")) {
            assertEquals(IccoaCloseReason.FAILED, IccoaFailurePolicy.authentication(stage, true, EOFException()))
        }
        assertEquals(IccoaCloseReason.FAILED,
            IccoaFailurePolicy.authentication("tcp-bind", true, SecurityException()))
    }
}

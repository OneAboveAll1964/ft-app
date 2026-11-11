package app.ft.aa

import org.junit.Assert.*
import org.junit.Test

class AaDisconnectHelpTest {
    @Test fun handshakeAndIntentionalDisconnectsDoNotOfferHelp() {
        val help = AaDisconnectHelp()
        help.start()
        help.closed(false, "read EOFException: null", true)
        help.closed(true, "bye-bye", true)
        help.closed(true, "write IOException: closed while answering BYEBYE", true, remoteBye = true)
        help.closed(true, "service stopped", true)
        help.closed(true, "write IOException: closed", false)
        assertFalse(help.pending.value)
        help.closed(true, "read EOFException: null", true)
        assertTrue(help.pending.value)
    }

    @Test fun reconnectDoesNotRepeatDismissedSuggestion() {
        val help = AaDisconnectHelp()
        help.start()
        help.closed(true, "write IOException: closed", true)
        assertTrue(help.pending.value)
        assertTrue(help.pending.value)
        help.dismiss()
        help.closed(true, "read EOFException: null", true)
        assertFalse(help.pending.value)
    }

    @Test fun stopSuppressesFailuresEvenBeforeServiceShutdownAndNextRunCanOfferAgain() {
        val help = AaDisconnectHelp()
        help.start()
        help.closed(true, "read EOFException: null", true)
        help.stop()
        assertFalse(help.pending.value)
        help.closed(true, "write IOException: closed", true)
        assertFalse(help.pending.value)
        help.start()
        help.closed(true, "read EOFException: null", true)
        assertTrue(help.pending.value)
    }
}

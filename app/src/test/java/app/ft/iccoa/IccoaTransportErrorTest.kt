package app.ft.iccoa

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException

class IccoaTransportErrorTest {
    @Test fun distinguishesKnownSocketFailuresWithoutPublishingExceptionMessages() {
        val cases = listOf(
            SocketException("Connection reset; private-peer") to "connection-reset",
            SocketException("Broken pipe; private-peer") to "broken-pipe",
            SocketException("Socket closed; private-peer") to "socket-closed",
            SocketTimeoutException("private-peer") to "timeout",
            EOFException("private-peer") to "eof",
            IllegalArgumentException("private-peer PIN=123456 psk=secret") to "other")
        for ((error, detail) in cases) {
            error.stackTrace = Array(8) { StackTraceElement("app.ft.iccoa.Reader", "read", "Reader.kt", it + 1) }
            val diagnostic = IccoaTransportError.describe(error)
            assertTrue(diagnostic.contains("detail=$detail"))
            assertTrue(diagnostic.contains("exception=${error.javaClass.simpleName}"))
            assertTrue(diagnostic.contains("Reader.read:4"))
            assertFalse(diagnostic.contains("Reader.read:5"))
            assertFalse(diagnostic.contains("private-peer"))
            assertFalse(diagnostic.contains("123456"))
            assertFalse(diagnostic.contains("secret"))
        }
    }
}

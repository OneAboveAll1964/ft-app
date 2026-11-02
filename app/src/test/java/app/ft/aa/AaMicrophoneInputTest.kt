package app.ft.aa

import org.junit.Assert.*
import org.junit.Test

class AaMicrophoneInputTest {
    @Test fun carSelectionRejectsPhoneFramesAlreadyWaitingInTheCallback() {
        val sent = mutableListOf<ByteArray>()
        var stops = 0
        val input = AaMicrophoneInput(sent::add, { stops++ }, {})
        val token = input.open()
        val phone = byteArrayOf(1, 0)
        val car = byteArrayOf(2, 0)
        input.phone(token, phone)
        input.car(car)
        input.phone(token, phone)
        input.car(car)
        assertEquals(listOf(phone, car, car), sent)
        assertEquals(1, stops)
    }

    @Test fun closingDropsBothSourcesAndReopeningRejectsTheOldPhoneCallback() {
        val sent = mutableListOf<ByteArray>()
        val input = AaMicrophoneInput(sent::add, {}, {})
        val old = input.open()
        input.close()
        val pcm = byteArrayOf(1, 0)
        input.phone(old, pcm)
        input.car(pcm)
        assertTrue(sent.isEmpty())
        val current = input.open()
        input.phone(old, pcm)
        input.phone(current, pcm)
        assertEquals(listOf(pcm), sent)
    }

    @Test fun anInvalidCarPacketDoesNotDisableThePhoneFallback() {
        val sent = mutableListOf<ByteArray>()
        val input = AaMicrophoneInput(sent::add, { fail("invalid PCM selected the car") }, {})
        val token = input.open()
        input.car(byteArrayOf())
        input.car(byteArrayOf(1))
        val pcm = byteArrayOf(1, 0)
        input.phone(token, pcm)
        assertEquals(listOf(pcm), sent)
    }

    @Test fun summariesCountSendAttemptsAndResetForEachVoiceRequest() {
        val logs = mutableListOf<String>()
        val input = AaMicrophoneInput({}, {}, logs::add)
        val token = input.open()
        input.phone(token, byteArrayOf(0, 0))
        input.car(byteArrayOf(0, -128, 1, 0))
        input.phone(token, byteArrayOf(1, 0))
        input.close()
        assertTrue(logs.last().startsWith("microphone send attempts: phonePackets=1 phoneBytes=2 carPackets=1 carBytes=4 peak=32768 "))
        val second = input.open()
        assertEquals(second, input.open())
        input.phone(second, byteArrayOf(2, 0))
        input.close()
        assertTrue(logs.last().startsWith("microphone send attempts: phonePackets=1 phoneBytes=2 carPackets=0 carBytes=0 peak=2 "))
    }

    @Test fun aRepeatedOpenDoesNotAllowRestartingThePhoneAfterSelectingTheCar() {
        val input = AaMicrophoneInput({}, {}, {})
        assertFalse(input.phoneAllowed)
        val token = input.open()
        assertTrue(input.phoneAllowed)
        input.car(byteArrayOf(1, 0))
        assertFalse(input.phoneAllowed)
        assertEquals(token, input.open())
        assertFalse(input.phoneAllowed)
        input.close()
        assertFalse(input.phoneAllowed)
        input.open()
        assertTrue(input.phoneAllowed)
    }
}

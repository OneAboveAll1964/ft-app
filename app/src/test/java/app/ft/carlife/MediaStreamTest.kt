package app.ft.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStreamTest {
    private val out = ArrayList<String>()
    private var now = 10_000L
    private val stream = MediaStream(
        send = { id, _ -> out += CarLifeProtocol.name(id) },
        status = { module, state -> out += "status $module:$state" },
        clock = { now }
    )

    @Test
    fun soundOpensTheStreamTheWayBaiduDoesForOlderCars() {
        stream.data(ByteArray(4))
        stream.data(ByteArray(4))
        assertEquals(listOf("MEDIA_INIT", "MEDIA_STOP", "MEDIA_INIT", "status 3:1", "MEDIA_DATA", "MEDIA_DATA"), out)
    }

    @Test
    fun newerCarsGetASingleInit() {
        stream.oldVehicle = false
        stream.data(ByteArray(4))
        assertEquals(listOf("MEDIA_INIT", "status 3:1", "MEDIA_DATA"), out)
    }

    @Test
    fun quietForThreeQuartersOfASecondPausesTheMusic() {
        stream.data(ByteArray(4))
        out.clear()
        now += 700
        assertFalse(stream.idle())
        now += 60
        assertTrue(stream.idle())
        assertEquals(listOf("MEDIA_PAUSE", "status 3:0"), out)
        assertFalse(stream.open)
        out.clear()
        stream.data(ByteArray(4))
        assertEquals(listOf("MEDIA_INIT", "MEDIA_STOP", "MEDIA_INIT", "status 3:1", "MEDIA_DATA"), out)
    }

    @Test
    fun comingBackToCarLifeStartsTheStreamAfreshAtMostOnceInFiveSeconds() {
        stream.data(ByteArray(4))
        out.clear()
        stream.launchedAgain()
        stream.data(ByteArray(4))
        assertEquals(listOf("MEDIA_PAUSE", "status 3:0", "MEDIA_INIT", "MEDIA_STOP", "MEDIA_INIT", "status 3:1", "MEDIA_DATA"), out)
        out.clear()
        now += 2000
        stream.launchedAgain()
        stream.data(ByteArray(4))
        assertEquals(listOf("MEDIA_DATA"), out)
        out.clear()
        now += 6000
        stream.launchedAgain()
        assertTrue(stream.idle())
        assertEquals(listOf("MEDIA_PAUSE", "status 3:0"), out)
    }

    @Test
    fun nothingIsSentForSilenceThatNeverStarted() {
        assertFalse(stream.idle())
        stream.launchedAgain()
        assertFalse(stream.idle())
        stream.data(ByteArray(0))
        assertTrue(out.isEmpty())
    }

    @Test
    fun forgettingStartsOverWithInit() {
        stream.data(ByteArray(4))
        stream.forget()
        out.clear()
        stream.data(ByteArray(4))
        assertEquals("MEDIA_INIT", out.first())
        assertEquals(2, stream.opened)
    }
}

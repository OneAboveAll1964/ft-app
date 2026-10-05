package app.ft.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFeedTest {
    private val sent = ArrayList<String>()
    private var now = 1_000L
    private val frames = ArrayList<ByteArray>()
    private val feed = VideoFeed(
        sendFrame = { f -> frames += f; sent += "frame:" + f.toString(Charsets.US_ASCII) },
        sendEmpty = { beat -> sent += if (beat) "beat" else "empty" },
        clock = { now }
    )

    private fun f(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun nothingGoesOutBeforeTheCarStarts() {
        feed.open()
        feed.config(f("C"))
        feed.frame(f("I1"), true)
        feed.frame(f("P1"), false)
        assertTrue(sent.isEmpty())
        assertFalse(feed.idle(0))
    }

    @Test
    fun startSendsHeaderWithTheLatestFullPictureAndWhatFollowed() {
        feed.open()
        feed.config(f("C"))
        feed.frame(f("P0"), false)
        feed.frame(f("I1"), true)
        feed.frame(f("P1"), false)
        feed.frame(f("I2"), true)
        feed.frame(f("P2"), false)
        assertEquals(VideoFeed.Mode.STARTING, feed.start())
        feed.frame(f("P3"), false)
        assertEquals(listOf("frame:CI2", "frame:P2", "frame:P3"), sent)
        assertEquals(VideoFeed.Mode.LIVE, feed.mode)
        sent.clear()
        feed.frame(f("I3"), true)
        assertEquals(listOf("frame:I3"), sent)
    }

    @Test
    fun startWithoutAFullPictureWaitsForOne() {
        feed.open()
        feed.config(f("C"))
        feed.start()
        feed.frame(f("P1"), false)
        feed.frame(f("P2"), false)
        feed.frame(f("I1"), true)
        assertEquals(listOf("empty", "empty", "frame:CI1"), sent)
    }

    @Test
    fun pauseTurnsEveryPictureIntoAHeartbeat() {
        live()
        sent.clear()
        assertEquals(VideoFeed.Mode.PAUSED, feed.pause())
        feed.frame(f("P"), false)
        feed.frame(f("I"), true)
        assertEquals(listOf("beat", "beat"), sent)
    }

    @Test
    fun resumeWaitsForANaturalFullPictureAfterTenDrops() {
        live()
        feed.pause()
        sent.clear()
        assertEquals(VideoFeed.Mode.RESUMING, feed.start())
        repeat(5) { feed.frame(f("P"), false) }
        feed.frame(f("Iearly"), true)
        repeat(5) { feed.frame(f("P"), false) }
        assertEquals(VideoFeed.Mode.RESUMING, feed.mode)
        assertEquals(listOf("beat", "beat", "beat"), sent)
        feed.frame(f("Ilate"), true)
        assertEquals(VideoFeed.Mode.LIVE, feed.mode)
        assertEquals("frame:Ilate", sent.last())
        assertEquals(11, feed.resumedAfter)
    }

    @Test
    fun headerRidesWithTheFirstFullPictureOnly() {
        feed.open()
        feed.config(f("C"))
        feed.frame(f("I1"), true)
        feed.start()
        feed.frame(f("P1"), false)
        feed.pause()
        feed.start()
        repeat(11) { feed.frame(f("P"), false) }
        feed.frame(f("I2"), true)
        assertArrayEquals(f("CI1"), frames.first())
        assertArrayEquals(f("I2"), frames.last())
    }

    @Test
    fun aNewHeaderIsSentWithTheNextFullPicture() {
        live()
        sent.clear()
        feed.config(f("D"))
        feed.frame(f("P"), false)
        feed.frame(f("I"), true)
        assertEquals(listOf("frame:P", "frame:DI"), sent)
        sent.clear()
        feed.config(f("D"))
        feed.frame(f("I"), true)
        assertEquals(listOf("frame:I"), sent)
    }

    @Test
    fun quietLinksGetHeartbeats() {
        live()
        sent.clear()
        now += 100
        assertFalse(feed.idle(300))
        now += 300
        assertTrue(feed.idle(300))
        assertEquals(listOf("beat"), sent)
        assertFalse(feed.idle(300))
    }

    @Test
    fun pauseBeforeTheFirstStartKeepsWaiting() {
        feed.open()
        feed.config(f("C"))
        feed.frame(f("I1"), true)
        feed.pause()
        assertEquals(VideoFeed.Mode.HELD, feed.mode)
        feed.start()
        feed.frame(f("P1"), false)
        assertEquals(listOf("frame:CI1", "frame:P1"), sent)
    }

    @Test
    fun closeStopsEverything() {
        live()
        feed.close()
        sent.clear()
        feed.frame(f("I"), true)
        feed.start()
        assertTrue(sent.isEmpty())
        assertFalse(feed.idle(0))
    }

    private fun live() {
        feed.open()
        feed.config(f("C"))
        feed.frame(f("I0"), true)
        feed.start()
        feed.frame(f("P0"), false)
    }
}

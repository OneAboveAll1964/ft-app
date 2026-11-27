package app.ft.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AaFramingTest {
    @Test
    fun singleFrameAtPayloadLimitRoundTrips() {
        val payload = ByteArray(AaProtocol.MAX_FRAME_PAYLOAD) { it.toByte() }
        val frame = encodedFrames(AaProtocol.CH_VIDEO, payload).single()

        assertEquals(AaProtocol.FRAME_BULK, frame.frameType)
        assertArrayEquals(payload, AaFraming.Assembler { it }.feed(frame)?.payload)
    }

    @Test
    fun receivesLegacyFullFirstFrameWithAnEmptyLastFrame() {
        val payload = ByteArray(AaProtocol.MAX_FRAME_PAYLOAD) { it.toByte() }
        val channel = AaProtocol.CH_VIDEO
        val assembler = AaFraming.Assembler { it }

        assertNull(assembler.feed(AaFraming.Frame(channel, AaProtocol.FRAME_FIRST, payload, payload.size)))
        val message = assembler.feed(AaFraming.Frame(channel, AaProtocol.FRAME_LAST, byteArrayOf()))

        assertArrayEquals(payload, message?.payload)
    }

    @Test
    fun fragmentedMessagesCanOverlapOnSeparateChannels() {
        val picture = ByteArray(AaProtocol.MAX_FRAME_PAYLOAD + 9) { it.toByte() }
        val audio = ByteArray(AaProtocol.MAX_FRAME_PAYLOAD + 23) { (it * 2).toByte() }
        val (pictureFirst, pictureLast) = encodedFrames(AaProtocol.CH_VIDEO, picture)
        val (audioFirst, audioLast) = encodedFrames(AaProtocol.CH_MEDIA_AUDIO, audio)
        val assembler = AaFraming.Assembler { it }

        assertNull(assembler.feed(pictureFirst))
        assertNull(assembler.feed(audioFirst))
        assertArrayEquals(audio, assembler.feed(audioLast)?.payload)
        assertArrayEquals(picture, assembler.feed(pictureLast)?.payload)
    }

    @Test
    fun serviceMessagesFollowDiscoveryAndChannelOpening() {
        val video = AaProtocol.CH_VIDEO
        val discovery = message(AaProtocol.CH_CONTROL, AaProtocol.SERVICE_DISCOVERY_REQUEST)
        val open = message(video, AaProtocol.CHANNEL_OPEN_REQUEST, control = true)
        val setup = message(video, AaProtocol.AV_SETUP_REQUEST)

        assertTrue(AaSession.acceptsMessage(AaSession.Phase.AUTHENTICATED, discovery, emptySet()))
        assertFalse(AaSession.acceptsMessage(AaSession.Phase.AUTHENTICATED, open, emptySet()))
        assertTrue(AaSession.acceptsMessage(AaSession.Phase.DISCOVERED, open, emptySet()))
        assertFalse(AaSession.acceptsMessage(AaSession.Phase.DISCOVERED, setup, emptySet()))
        assertTrue(AaSession.acceptsMessage(AaSession.Phase.DISCOVERED, setup, setOf(video)))
    }

    private fun encodedFrames(channel: Int, payload: ByteArray): List<AaFraming.Frame> =
        AaFraming.encode(channel, payload, encrypted = false, control = false) { it }
            .map { AaFraming.readFrame(it.inputStream()) }

    private fun message(channel: Int, id: Int, control: Boolean = false) =
        AaFraming.Message(channel, control, true, byteArrayOf((id ushr 8).toByte(), id.toByte()))
}

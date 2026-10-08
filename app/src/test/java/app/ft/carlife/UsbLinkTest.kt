package app.ft.carlife

import app.ft.core.Bytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class UsbLinkTest {
    private data class Got(val channel: Int, val serviceId: Int, val body: ByteArray)

    private val got = ArrayList<Got>()
    private val demux = AoaFraming.Demux { ch, head, body -> got += Got(ch, CarLifeFraming.serviceId(ch, head), body) }

    private fun packet(channel: Int, inner: ByteArray) = AoaFraming.head(channel, inner.size) + inner

    private val hello = CarLifeFraming.cmd(0x00018001, byteArrayOf(8, 1, 16, 0))
    private val touch = CarLifeFraming.cmd(0x00068001, byteArrayOf(8, 0, 16, 100, 24, 50))
    private val frame = CarLifeFraming.stream(0x00020001, ByteArray(40_000) { it.toByte() }, 7)

    @Test
    fun theHeaderIsChannelThenLengthBigEndian() {
        val h = AoaFraming.head(2, 70_000)
        assertEquals(8, h.size)
        assertEquals(2, AoaFraming.channel(h))
        assertEquals(70_000, AoaFraming.length(h))
        assertEquals(2, Bytes.u32(h, 0))
    }

    @Test(expected = IllegalStateException::class)
    fun aNonsenseLengthClosesTheLink() {
        AoaFraming.length(AoaFraming.head(1, -5))
    }

    @Test
    fun eachPacketIsHandedToItsChannel() {
        demux.packet(1, hello)
        demux.packet(6, touch)
        assertEquals(listOf(1 to 0x00018001, 6 to 0x00068001), got.map { it.channel to it.serviceId })
    }

    @Test
    fun aMessageSpreadOverTwoPacketsIsJoined() {
        demux.packet(1, hello.copyOfRange(0, 6))
        assertTrue(got.isEmpty())
        demux.packet(1, hello.copyOfRange(6, hello.size))
        assertEquals(listOf(0x00018001), got.map { it.serviceId })
        assertArrayEquals(byteArrayOf(8, 1, 16, 0), got[0].body)
    }

    @Test
    fun twoMessagesInOnePacketAreBothHandled() {
        demux.packet(6, touch + touch)
        assertEquals(2, got.size)
    }

    @Test
    fun channelsDoNotMixTheirHalves() {
        demux.packet(1, hello.copyOfRange(0, 3))
        demux.packet(6, touch)
        demux.packet(1, hello.copyOfRange(3, hello.size))
        assertEquals(listOf(6, 1), got.map { it.channel })
    }

    @Test
    fun aBigVideoFrameComesThroughWhole() {
        demux.packet(2, frame)
        assertEquals(1, got.size)
        assertEquals(40_000, got[0].body.size)
        assertEquals((39_999).toByte(), got[0].body.last())
    }

    @Test
    fun theLinkTalksToTheCarOverOnePipe() {
        val toPhone = PipedOutputStream()
        val phoneIn = PipedInputStream(toPhone, 1 shl 20)
        val toCar = PipedInputStream(1 shl 20)
        val phoneOut = PipedOutputStream(toCar)
        val connected = CountDownLatch(1)
        val closed = LinkedBlockingQueue<String>()
        val heard = LinkedBlockingQueue<Got>()
        val link = AccessoryLink("USB", "a test car") { AccessoryLink.Pipe(phoneIn, phoneOut) { phoneIn.close() } }
        link.start(
            CoroutineScope(Dispatchers.IO),
            { ch, head, body -> heard.put(Got(ch, CarLifeFraming.serviceId(ch, head), body)) },
            { },
            { connected.countDown() },
            { why -> closed.put(why) }
        )
        assertTrue("never connected", connected.await(5, TimeUnit.SECONDS))
        assertTrue(link.connected)

        val incoming = packet(1, hello) + packet(9, ByteArray(12)) + packet(2, frame) + packet(6, touch)
        var at = 0
        for (size in listOf(3, 9, 1, 700, 16_384, 5, 40_000)) {
            if (at >= incoming.size) break
            val end = minOf(incoming.size, at + size)
            toPhone.write(incoming, at, end - at)
            toPhone.flush()
            at = end
        }
        if (at < incoming.size) toPhone.write(incoming, at, incoming.size - at)
        toPhone.flush()
        val order = (1..3).mapNotNull { heard.poll(5, TimeUnit.SECONDS) }
        assertEquals(listOf(1 to 0x00018001, 2 to 0x00020001, 6 to 0x00068001), order.map { it.channel to it.serviceId })
        assertEquals(40_000, order[1].body.size)

        assertTrue(link.send(2, frame))
        val car = DataInputStream(toCar)
        val head = ByteArray(8).also { car.readFully(it) }
        assertEquals(2, Bytes.u32(head, 0))
        assertEquals(frame.size, Bytes.u32(head, 4))
        val inner = ByteArray(frame.size).also { car.readFully(it) }
        assertArrayEquals(frame, inner)

        toPhone.close()
        val why = closed.poll(5, TimeUnit.SECONDS)
        assertTrue("closing was not reported: $why", why != null)
        link.stop()
    }
}

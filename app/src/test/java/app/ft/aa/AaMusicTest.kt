package app.ft.aa

import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter
import org.junit.Assert.*
import org.junit.Test

class AaMusicTest {
    private fun metadata(title: String, art: ByteArray? = null): ByteArray = ProtoWriter()
        .string(1, title).string(2, "Artist").string(3, "Album").int32(6, 220)
        .apply { if (art != null) bytes(4, art) }.toByteArray()

    @Test fun playbackBeforeMetadataKeepsItsPositionAndUsesMilliseconds() {
        val music = AaMusic()
        val status = music.accept(AaProtocol.MEDIA_PLAYBACK_STATUS,
            ProtoWriter().int32(1, 2).int32(3, 42).toByteArray())!!
        assertTrue(status.playing)
        assertNull(status.track)
        val info = music.accept(AaProtocol.MEDIA_PLAYBACK_METADATA, metadata("Song"))!!
        assertEquals(42_000, info.positionMs)
        assertEquals(220, info.track!!.durationSeconds)
        assertEquals("Artist", info.track.artist)
    }

    @Test fun metadataBeforePlaybackDoesNotStartMusicAndPauseKeepsTheTrack() {
        val music = AaMusic()
        assertFalse(music.accept(AaProtocol.MEDIA_PLAYBACK_METADATA, metadata("Song"))!!.playing)
        music.accept(AaProtocol.MEDIA_PLAYBACK_STATUS, ProtoWriter().int32(1, 2).toByteArray())
        val paused = music.accept(AaProtocol.MEDIA_PLAYBACK_STATUS, ProtoWriter().int32(1, 3).toByteArray())!!
        assertFalse(paused.playing)
        assertEquals("Song", paused.track!!.title)
    }

    @Test fun repeatedMetadataIsIgnoredAndANewCoverKeepsTheTrackIdentity() {
        val music = AaMusic()
        val first = music.accept(AaProtocol.MEDIA_PLAYBACK_METADATA, metadata("Song", byteArrayOf(1)))!!
        assertNull(music.accept(AaProtocol.MEDIA_PLAYBACK_METADATA, metadata("Song", byteArrayOf(1))))
        val cover = music.accept(AaProtocol.MEDIA_PLAYBACK_METADATA, metadata("Song", byteArrayOf(2)))!!
        assertEquals(first.track!!.id, cover.track!!.id)
        assertArrayEquals(byteArrayOf(2), cover.track.art)
    }

    @Test fun newTrackDoesNotInheritOldPositionAndOmittedFields() {
        val music = AaMusic()
        val first = music.accept(AaProtocol.MEDIA_PLAYBACK_METADATA, metadata("First", byteArrayOf(1)))!!
        music.accept(AaProtocol.MEDIA_PLAYBACK_STATUS, ProtoWriter().int32(1, 2).int32(3, 80).toByteArray())
        val next = music.accept(AaProtocol.MEDIA_PLAYBACK_METADATA, ProtoWriter().string(1, "Next").toByteArray())!!
        assertNotEquals(first.track!!.id, next.track!!.id)
        assertNull(next.positionMs)
        assertEquals("", next.track.artist)
        assertNull(next.track.art)
        assertEquals(0, next.track.durationSeconds)
    }

    @Test fun resetAndEmptyTrackClearMetadataWithoutReusingIds() {
        val music = AaMusic()
        val first = music.accept(AaProtocol.MEDIA_PLAYBACK_METADATA, metadata("Song"))!!
        assertNull(music.accept(AaProtocol.MEDIA_PLAYBACK_METADATA, ProtoWriter().string(1, "").toByteArray())!!.track)
        assertNull(music.reset().track)
        val next = music.accept(AaProtocol.MEDIA_PLAYBACK_METADATA, metadata("Song"))!!
        assertNotEquals(first.track!!.id, next.track!!.id)
        assertFalse(next.playing)
    }

    @Test fun invalidOrUnrelatedDataDoesNotChangeTheTrackAndPositionCannotOverflow() {
        val music = AaMusic()
        assertNull(music.accept(0x8002, metadata("Song")))
        assertNull(music.accept(AaProtocol.MEDIA_PLAYBACK_METADATA, byteArrayOf(0x0a, 0x7f)))
        val status = music.accept(AaProtocol.MEDIA_PLAYBACK_STATUS,
            ProtoWriter().int32(1, 2).int64(3, Long.MAX_VALUE).toByteArray())!!
        assertEquals(2_147_483_000, status.positionMs)
    }

    @Test fun playbackServiceIsAdvertisedEvenWhenPcmAudioIsDisabled() {
        val response = AaMessages.serviceDiscoveryResponse(1280, 720, 30, 160, false, "FT")
        val service = ProtoReader(response).messages(1).single { it.int(1) == AaProtocol.CH_MEDIA_STATUS }
        assertTrue(service.has(9))
    }
}

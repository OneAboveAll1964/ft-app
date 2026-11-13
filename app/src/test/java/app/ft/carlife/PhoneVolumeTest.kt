package app.ft.carlife

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*

class PhoneVolumeTest {
    private class Fixture {
        var durable: Int? = null
        var volume = 7
        var commitSucceeds = true
        var setFails = false
        val writes = mutableListOf<Int>()
        val errors = mutableListOf<Exception>()

        // Each instance models a new process loading only the committed checkpoint.
        fun recreate(): PhoneVolume {
            val prefs = mock(SharedPreferences::class.java)
            val editor = mock(SharedPreferences.Editor::class.java)
            var pending: Int? = durable
            `when`(prefs.getInt(anyString(), anyInt())).thenAnswer { durable ?: it.getArgument<Int>(1) }
            `when`(prefs.edit()).thenReturn(editor)
            `when`(editor.putInt(anyString(), anyInt())).thenAnswer {
                pending = it.getArgument<Int>(1)
                editor
            }
            `when`(editor.remove(anyString())).thenAnswer { pending = null; editor }
            `when`(editor.commit()).thenAnswer {
                if (commitSucceeds) durable = pending
                commitSucceeds
            }
            return PhoneVolume(prefs, { volume }, {
                if (it == 0) assertNotNull("checkpoint must precede mute", durable)
                if (setFails) throw SecurityException("volume unavailable")
                writes += it
                volume = it
            }, { 15 }, { errors += it })
        }
    }

    @Test fun processRecreationRestoresCommittedVolume() {
        val f = Fixture()
        assertTrue(f.recreate().mute())
        assertEquals(0, f.volume)
        assertEquals(7, f.durable)
        assertTrue(f.recreate().restore())
        assertEquals(7, f.volume)
        assertNull(f.durable)
    }

    @Test fun subsequentUserVolumeIsNotOverwritten() {
        val f = Fixture()
        f.recreate().mute()
        f.volume = 4
        assertTrue(f.recreate().restore())
        assertEquals(4, f.volume)
        assertEquals(listOf(0), f.writes)
        assertNull(f.durable)
    }

    @Test fun failedCheckpointNeverMutesPhone() {
        val f = Fixture()
        f.commitSucceeds = false
        val helper = f.recreate()
        assertFalse(helper.mute())
        assertEquals(7, f.volume)
        assertEquals(-1, helper.saved)
        assertTrue(f.writes.isEmpty())
        assertNull(f.durable)
        assertEquals(1, f.errors.size)
    }

    @Test fun failedRestoreRetainsCheckpointForNextProcess() {
        val f = Fixture()
        f.recreate().mute()
        f.setFails = true
        val helper = f.recreate()
        assertFalse(helper.restore())
        assertEquals(7, helper.saved)
        assertEquals(7, f.durable)
        f.setFails = false
        assertTrue(f.recreate().restore())
        assertEquals(7, f.volume)
        assertNull(f.durable)
    }

    @Test fun mutedVolumeButtonsPersistAcrossProcessDeath() {
        val f = Fixture()
        val helper = f.recreate()
        helper.mute()
        assertTrue(helper.adjust(true))
        assertEquals(0, f.volume)
        assertEquals(8, f.durable)
        assertTrue(f.recreate().restore())
        assertEquals(8, f.volume)
    }

    @Test fun failedButtonCheckpointKeepsPreviousRecoveryLevel() {
        val f = Fixture()
        val helper = f.recreate()
        helper.mute()
        f.commitSucceeds = false
        assertFalse(helper.adjust(true))
        assertEquals(7, helper.saved)
        assertEquals(7, f.durable)
        assertEquals(0, f.volume)
        f.commitSucceeds = true
        assertTrue(f.recreate().restore())
        assertEquals(7, f.volume)
    }

    @Test fun failedClearCanRetryWithoutOverwritingLaterUserChoice() {
        val f = Fixture()
        val helper = f.recreate()
        helper.mute()
        f.commitSucceeds = false
        assertFalse(helper.restore())
        assertEquals(7, helper.saved)
        assertEquals(7, f.durable)
        f.volume = 3
        f.commitSucceeds = true
        assertTrue(helper.restore())
        assertEquals(3, f.volume)
        assertNull(f.durable)
    }

    @Test fun alreadySilentPhoneNeedsNoRecovery() {
        val f = Fixture()
        f.volume = 0
        val helper = f.recreate()
        assertFalse(helper.mute())
        assertFalse(helper.restore())
        assertNull(f.durable)
        assertTrue(f.writes.isEmpty())
    }
}

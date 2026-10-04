package app.ft.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryTest {
    @Test
    fun whatsAppAudioIsChatAudio() {
        assertTrue(MediaLibrary.chatAudio("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/"))
        assertTrue(MediaLibrary.chatAudio("WhatsApp/Media/WhatsApp Audio/"))
        assertTrue(MediaLibrary.chatAudio("Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Audio/"))
    }

    @Test
    fun telegramAudioIsChatAudio() {
        assertTrue(MediaLibrary.chatAudio("Telegram/Telegram Audio/"))
        assertTrue(MediaLibrary.chatAudio("Android/media/org.telegram.messenger/Telegram/Telegram Audio/"))
    }

    @Test
    fun musicFoldersStay() {
        assertFalse(MediaLibrary.chatAudio("Music/"))
        assertFalse(MediaLibrary.chatAudio("Download/"))
        assertFalse(MediaLibrary.chatAudio("Music/Bokan Hawrami/"))
        assertFalse(MediaLibrary.chatAudio(null))
    }
}

package app.ft.core

import org.junit.Assert.*
import org.junit.Test

class VoiceButtonTest {
    private var choice = VoiceAssistant.DEFAULT
    private var aaVisible = true
    private var time = 0L
    private val events = mutableListOf<String>()
    private val button = VoiceButton({ choice }, { aaVisible }, { events += "aa" }, { events += it.name }, { time })

    @Test fun defaultUsesAndroidAutoWhileItsScreenIsVisible() {
        button.press()
        assertEquals(listOf("aa"), events)
    }

    @Test fun defaultUsesPhoneAssistantOutsideAndroidAuto() {
        aaVisible = false
        button.press()
        assertEquals(listOf("DEFAULT"), events)
    }

    @Test fun aliceBypassesAndroidAutoAndTracksLivePreferenceChanges() {
        choice = VoiceAssistant.ALICE
        button.press()
        time += 500
        choice = VoiceAssistant.DEFAULT
        button.press()
        assertEquals(listOf("ALICE", "aa"), events)
    }

    @Test fun duplicateSignalsDoNotLaunchTwiceOrExtendTheDebounceWindow() {
        choice = VoiceAssistant.ALICE
        button.press()
        time = 100
        button.press()
        time = 499
        button.press()
        time = 500
        button.press()
        assertEquals(listOf("ALICE", "ALICE"), events)
    }

    @Test fun aliceIsAlsoUsedOutsideAndroidAuto() {
        choice = VoiceAssistant.ALICE
        aaVisible = false
        button.press()
        assertEquals(listOf("ALICE"), events)
    }

    @Test fun unknownSavedValueKeepsTheDefaultAssistant() {
        assertEquals(VoiceAssistant.DEFAULT, VoiceAssistant.fromStored(0))
        assertEquals(VoiceAssistant.ALICE, VoiceAssistant.fromStored(1))
        assertEquals(VoiceAssistant.DEFAULT, VoiceAssistant.fromStored(-1))
        assertEquals(VoiceAssistant.DEFAULT, VoiceAssistant.fromStored(123))
    }
}

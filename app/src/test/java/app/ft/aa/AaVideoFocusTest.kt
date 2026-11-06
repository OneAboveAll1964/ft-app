package app.ft.aa

import app.ft.core.ProtoReader
import app.ft.core.ProtoWriter
import org.junit.Assert.*
import org.junit.Test

class AaVideoFocusTest {
    private class Receiver(owner: AaVideoFocusOwner = AaVideoFocusOwner()) {
        var phase = AaSession.Phase.STREAMING
        var channelOpen = true
        var running = true
        var writeSucceeds = true
        val packets = mutableListOf<ByteArray>()
        val focus = AaVideoFocusControl(
            available = { running && channelOpen && phase >= AaSession.Phase.DISCOVERED },
            indicate = { mode, unrequested ->
                packets += AaMessages.videoFocusIndication(mode.mode, unrequested)
                writeSucceeds
            },
            owner = owner
        )
        fun modes() = packets.map { ProtoReader(it).int(1) }
        fun unsolicited() = packets.map { ProtoReader(it).int(2) }
    }

    @Test fun nativeExitAndLateVideoStartKeepNativeFocusUntilExplicitReturn() {
        val receiver = Receiver()
        assertTrue(receiver.focus.phoneRequest(AaProtocol.VIDEO_FOCUS_NATIVE))
        assertEquals(AaVideoFocus.NATIVE, receiver.focus.state.value)
        assertTrue(receiver.focus.acknowledge()) // Late AV_START is not permission to reopen AA.
        assertFalse(receiver.focus.freshPicture()) // A delayed codec callback cannot reopen AA either.
        receiver.phase = AaSession.Phase.DISCOVERED // Phone stopped video while FT was visible.
        assertTrue(receiver.focus.request(AaVideoFocus.PROJECTED, true))
        assertTrue(receiver.focus.freshPicture())
        assertEquals(listOf(2, 2, 1, 1), receiver.modes())
        assertEquals(listOf(0, 0, 1, 1), receiver.unsolicited())
        assertEquals(AaVideoFocus.PROJECTED, receiver.focus.state.value)
    }

    @Test fun floatingButtonRequestsNativeWithoutStoppingTheReceiver() {
        val receiver = Receiver()
        assertTrue(receiver.focus.request(AaVideoFocus.NATIVE, true))
        assertTrue(receiver.running)
        assertTrue(receiver.channelOpen)
        assertEquals(listOf(2), receiver.modes())
        assertEquals(listOf(1), receiver.unsolicited())
    }

    @Test fun unknownOrMissingModeDoesNotChangeFocusOrSendAcknowledgement() {
        val receiver = Receiver()
        assertFalse(receiver.focus.phoneRequest(0))
        assertFalse(receiver.focus.phoneRequest(99))
        val missing = AaMessages.videoFocusRequestMode(ByteArray(0))
        assertFalse(receiver.focus.phoneRequest(missing))
        val known = AaMessages.videoFocusRequestMode(ProtoWriter().enum(2, 2).toByteArray())
        assertTrue(receiver.focus.phoneRequest(known))
        assertEquals(listOf(2), receiver.modes())
    }

    @Test fun unavailableChannelAndFailedWriteNeverPublishNewFocus() {
        val receiver = Receiver()
        receiver.phase = AaSession.Phase.AUTHENTICATED
        assertFalse(receiver.focus.phoneRequest(2))
        receiver.phase = AaSession.Phase.DISCOVERED
        receiver.channelOpen = false
        assertFalse(receiver.focus.request(AaVideoFocus.NATIVE, true))
        receiver.channelOpen = true
        receiver.running = false
        assertFalse(receiver.focus.phoneRequest(2))
        assertTrue(receiver.packets.isEmpty())
        receiver.running = true
        receiver.writeSucceeds = false
        assertFalse(receiver.focus.phoneRequest(2))
        assertEquals(AaVideoFocus.PROJECTED, receiver.focus.state.value)
    }

    @Test fun localReturnAndNewSessionHaveIndependentFocus() {
        val receiver = Receiver()
        assertTrue(receiver.focus.request(AaVideoFocus.NATIVE, true))
        assertEquals(AaVideoFocus.PROJECTED, Receiver().focus.state.value)
        assertTrue(receiver.focus.phoneRequest(1))
        assertEquals(AaVideoFocus.PROJECTED, receiver.focus.state.value)
        assertEquals(listOf(1, 0), receiver.unsolicited())
    }

    @Test fun nativeFocusSurvivesTransportReplacementAndLateVideoStart() {
        val owner = AaVideoFocusOwner()
        val old = Receiver(owner)
        assertTrue(old.focus.phoneRequest(AaProtocol.VIDEO_FOCUS_NATIVE))
        old.running = false

        val replacement = Receiver(owner)
        assertEquals(AaVideoFocus.NATIVE, replacement.focus.state.value)
        assertTrue(replacement.focus.acknowledge()) // Setup/AV_START of the new transport.
        assertFalse(replacement.focus.freshPicture())
        assertEquals(listOf(2), replacement.modes())
        assertFalse(AaState(connected = true, videoFocus = replacement.focus.state.value).showProjection)

        assertFalse(old.focus.phoneRequest(AaProtocol.VIDEO_FOCUS_PROJECTED))
        assertEquals(AaVideoFocus.NATIVE, replacement.focus.state.value)
    }

    @Test fun explicitReturnBeforeVideoChannelReadyIsAppliedByNextSetup() {
        val owner = AaVideoFocusOwner()
        owner.select(AaVideoFocus.NATIVE)
        val replacement = Receiver(owner).apply {
            phase = AaSession.Phase.CONNECTED
            channelOpen = false
        }

        owner.select(AaVideoFocus.PROJECTED) // Explicit start while no usable transport exists.
        assertFalse(replacement.focus.request(AaVideoFocus.PROJECTED, true))
        assertTrue(replacement.packets.isEmpty())
        replacement.phase = AaSession.Phase.DISCOVERED
        replacement.channelOpen = true
        assertTrue(replacement.focus.acknowledge())
        assertEquals(listOf(1), replacement.modes())
        assertTrue(AaState(connected = true, videoFocus = replacement.focus.state.value).showProjection)
        assertEquals(AaVideoFocus.PROJECTED, Receiver(owner).focus.state.value)
    }

    @Test fun bothCarBackendsCanKeepConnectionWhileHidingProjection() {
        val connected = AaState(connected = true, phase = AaSession.Phase.STREAMING)
        assertTrue(connected.showProjection)
        assertFalse(connected.copy(videoFocus = AaVideoFocus.NATIVE).showProjection)
        assertTrue(connected.copy(videoFocus = AaVideoFocus.NATIVE).connected)
        assertTrue(connected.copy(phase = AaSession.Phase.DISCOVERED).showProjection)
        assertFalse(connected.copy(connected = false).showProjection)
    }
}

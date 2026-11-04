package app.ft.aa

/** State of one bounded request; all access belongs to the service's main thread. */
internal class AaStarterAttempt(private val deadline: Long) {
    enum class Action { WAIT, OPEN_MENU, START, ON, LOCKED, FAILED }

    var observedAa = false
        private set
    private var openedMenu = false
    private var clickedStart = false

    fun expired(now: Long): Boolean = now >= deadline

    fun next(
        now: Long,
        locked: Boolean,
        aaWindow: Boolean?,
        serverOn: Boolean = false,
        start: Boolean = false,
        stop: Boolean = false,
        overflow: Boolean = false
    ): Action {
        if (locked) return Action.LOCKED
        if (expired(now)) return Action.FAILED
        if (aaWindow == false && observedAa) return Action.FAILED
        if (aaWindow == true) observedAa = true
        if (serverOn || (aaWindow == true && stop)) return Action.ON
        if (aaWindow != true || clickedStart) return Action.WAIT
        if (start) {
            clickedStart = true
            return Action.START
        }
        // One click per request: repeated events must never toggle an open menu closed.
        if (overflow && !openedMenu) {
            openedMenu = true
            return Action.OPEN_MENU
        }
        return Action.WAIT
    }
}

package app.ft.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A panel belongs to one car connection; stale callbacks cannot close its replacement. */
open class AssistantPanelController {
    class Request internal constructor(val id: Long, internal val owner: Any) {
        val paneName: String get() = "alice-$id"
    }
    private val mutable = MutableStateFlow<Request?>(null)
    val state = mutable.asStateFlow()
    private var sequence = 0L
    private var touchOwner: Any? = null

    @Synchronized fun toggle(owner: Any) {
        mutable.value = if (isOpen(owner)) null else Request(++sequence, owner)
    }

    fun isOpen(owner: Any): Boolean = mutable.value?.owner === owner
    fun isCurrent(request: Request): Boolean = mutable.value === request

    @Synchronized fun close(owner: Any): Boolean {
        if (!isOpen(owner)) return false
        mutable.value = null
        return true
    }

    @Synchronized fun dismiss(request: Request) {
        if (isCurrent(request)) mutable.value = null
    }

    /** Keep the entire gesture in FT even if its UP closes the panel. */
    @Synchronized fun routesTouch(owner: Any, action: Int): Boolean {
        if (action == 0) touchOwner = if (isOpen(owner)) owner else null
        val routed = touchOwner === owner
        if (action == 1 || action == 3) touchOwner = null
        return routed
    }
}

object CarAssistantPanel : AssistantPanelController()

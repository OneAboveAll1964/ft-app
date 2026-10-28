package app.ft.aa

internal class AaConnectionOwner<T : Any> {
    private var enabled = false
    private var generation = 0L
    private var epoch = 0L
    private var current: T? = null

    @Synchronized fun start(): Long { enabled = true; generation++; return ++epoch }

    @Synchronized fun idleGeneration(run: Long): Long? =
        generation.takeIf { enabled && epoch == run && current == null }

    @Synchronized fun withRun(run: Long, action: () -> Unit): Boolean {
        if (!enabled || epoch != run) return false
        action()
        return true
    }

    @Synchronized fun attach(token: Long, create: () -> T, attached: (T) -> Unit): Boolean {
        if (!enabled || current != null || token != generation) return false
        val connection = create()
        current = connection
        generation++
        attached(connection)
        return true
    }

    @Synchronized fun withCurrent(connection: T, action: () -> Unit) {
        if (enabled && current === connection) action()
    }

    @Synchronized fun release(connection: T, released: (Boolean) -> Unit) {
        if (current !== connection) return
        current = null
        released(enabled)
    }

    @Synchronized fun stop(): T? {
        enabled = false
        generation++
        return current
    }
}

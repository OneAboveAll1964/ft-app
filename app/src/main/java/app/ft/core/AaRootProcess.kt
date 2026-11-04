package app.ft.core

import java.util.concurrent.TimeUnit

internal object AaRootProcess {
    fun await(process: Process): Boolean = try {
        process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0
    } finally {
        // Includes timeout and coroutine interruption. Output is redirected before launch.
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
    }
}

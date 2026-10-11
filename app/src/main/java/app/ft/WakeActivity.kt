package app.ft

import android.app.Activity
import android.os.Bundle

class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.decorView.postDelayed({ finish() }, 1200)
    }
}

package app.ft.ui.components

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.ft.FTApp
import app.ft.core.CarProtocol

@Composable
fun rememberCarProtocol(): CarProtocol {
    val prefs = FTApp.instance.prefs
    var protocol by remember { mutableStateOf(prefs.carProtocol) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "carProtocol") protocol = prefs.carProtocol
        }
        prefs.registerListener(listener)
        protocol = prefs.carProtocol
        onDispose { prefs.unregisterListener(listener) }
    }
    return protocol
}

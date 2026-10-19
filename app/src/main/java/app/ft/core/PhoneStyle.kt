package app.ft.core

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

val LocalModernPhoneUi = compositionLocalOf { false }

object PhoneStyle {
    private lateinit var prefs: Prefs
    private val _modern = MutableStateFlow(false)
    val modern = _modern.asStateFlow()

    fun init(prefs: Prefs) {
        this.prefs = prefs
        _modern.value = prefs.modernPhoneUi
    }

    fun setModern(value: Boolean) {
        prefs.modernPhoneUi = value
        _modern.value = value
    }
}

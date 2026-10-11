package app.ft.core

import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.text.TextUtils
import android.view.View
import androidx.annotation.StringRes
import app.ft.FTApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

enum class Language(val key: String, val tag: String?) {
    SYSTEM("", null),
    ENGLISH("en", "en"),
    ARABIC("ar", "ar-u-nu-latn"),
    SORANI("ckb", "ckb-u-nu-latn"),
    BADINI("kmr", "kmr-Arab-u-nu-latn");

    companion object {
        fun of(key: String): Language = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

object Lang {
    private val _current = MutableStateFlow(Language.SYSTEM)
    val current: StateFlow<Language> = _current

    fun init(prefs: Prefs) {
        _current.value = Language.of(prefs.language)
    }

    fun set(language: Language) {
        FTApp.instance.prefs.language = language.key
        _current.value = language
    }

    fun locale(language: Language = current.value): Locale =
        language.tag?.let { Locale.forLanguageTag(it) } ?: Resources.getSystem().configuration.locales[0]

    fun rtl(language: Language = current.value): Boolean = when (language) {
        Language.ARABIC, Language.SORANI, Language.BADINI -> true
        Language.ENGLISH -> false
        Language.SYSTEM -> TextUtils.getLayoutDirectionFromLocale(locale(language)) == View.LAYOUT_DIRECTION_RTL
    }

    fun wrap(base: Context, language: Language = current.value, config: Configuration = base.resources.configuration): Context {
        if (language == Language.SYSTEM) return base
        val c = Configuration(config)
        val loc = locale(language)
        c.setLocales(LocaleList(loc))
        c.setLayoutDirection(if (rtl(language)) Locale("ar") else Locale.ENGLISH)
        return Localized(base, base.createConfigurationContext(c).resources)
    }

    fun str(@StringRes id: Int, vararg args: Any): String = wrap(FTApp.instance).getString(id, *args)

    private class Localized(base: Context, private val res: Resources) : ContextWrapper(base) {
        override fun getResources(): Resources = res
    }
}

object Looks {
    const val AUTO = 0
    const val LIGHT = 1
    const val DARK = 2

    private val _mode = MutableStateFlow(AUTO)
    val mode: StateFlow<Int> = _mode

    fun init(prefs: Prefs) {
        _mode.value = prefs.themeMode.coerceIn(AUTO, DARK)
    }

    fun set(mode: Int) {
        FTApp.instance.prefs.themeMode = mode
        _mode.value = mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching {
            FTApp.instance.getSystemService(UiModeManager::class.java).setApplicationNightMode(
                when (mode) {
                    LIGHT -> UiModeManager.MODE_NIGHT_NO
                    DARK -> UiModeManager.MODE_NIGHT_YES
                    else -> UiModeManager.MODE_NIGHT_AUTO
                }
            )
        }
    }

    fun dark(mode: Int, systemDark: Boolean): Boolean = when (mode) {
        LIGHT -> false
        DARK -> true
        else -> systemDark
    }
}

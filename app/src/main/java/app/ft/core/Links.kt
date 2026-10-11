package app.ft.core

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Display

object Links {
    const val GITHUB = "https://github.com/OneAboveAll1964"

    fun openOnPhone(context: Context, url: String) {
        val app = context.applicationContext
        runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
            )
        }.onFailure { DiagLog.w("App", "could not open $url: ${it.message}") }
    }
}

package app.ft

import android.content.Context
import android.content.Intent
import app.ft.aa.AaHeadUnitService
import app.ft.carlife.CarLifeService
import app.ft.core.CarAudioBus
import app.ft.core.CarConnection
import app.ft.core.Lang
import app.ft.core.Language
import app.ft.projection.CarControls
import app.ft.projection.VideoPlans
import app.ft.supercast.SuperCast
import app.ft.ui.car.CarStyles

class DevelopmentActivity : MainActivity() {
    override fun handleDevelopmentIntent(intent: Intent) {
        val app = application as FTApp
        if (intent.hasExtra("aaCorner")) {
            app.prefs.aaCorner = intent.getIntExtra("aaCorner", 0)
            CarStyles.reload()
        }
        if (intent.hasExtra("linkMode")) app.prefs.linkMode = intent.getIntExtra("linkMode", 0)
        if (intent.hasExtra("pictureSize")) app.prefs.videoSize = intent.getIntExtra("pictureSize", VideoPlans.SIZE_CAR)
        if (intent.getBooleanExtra("off", false)) CarConnection.switchOff(this)
        if (intent.getBooleanExtra("wifi", false) || intent.getBooleanExtra("auto", false)) CarConnection.switchOn(this)
        if (intent.getBooleanExtra("aa", false)) AaHeadUnitService.start(this, false)
        if (intent.getBooleanExtra("mirror", false)) requestMirror()
        intent.getStringExtra("aaPkg")?.let { app.prefs.aaPackage = it }
        if (intent.hasExtra("aaAuto")) app.prefs.aaAutoStart = intent.getBooleanExtra("aaAuto", false)
        if (intent.hasExtra("fakeCall")) {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.mode = if (intent.getBooleanExtra("fakeCall", false)) android.media.AudioManager.MODE_IN_COMMUNICATION else android.media.AudioManager.MODE_NORMAL
        }
        if (intent.getBooleanExtra("aaSetup", false)) pickAndroidAuto()
        if (intent.getBooleanExtra("startAa", false)) CarControls.startAa()
        if (intent.getBooleanExtra("aaWireless", false)) CarControls.startAaWireless()
        intent.getStringExtra("btSend")?.let { CarLifeService.btSend(it) }
        if (intent.hasExtra("btEcho")) CarLifeService.btEcho(intent.getBooleanExtra("btEcho", false))
        intent.getStringExtra("pkgMaps")?.let { app.prefs.mapsPackage = it }
        intent.getStringExtra("pkgVideo")?.let { app.prefs.videoPackage = it }
        intent.getStringExtra("carTiles")?.let { app.prefs.carTiles = it; CarStyles.reload() }
        intent.getStringExtra("carBackground")?.let { app.prefs.carBackground = it; CarStyles.reload() }
        if (intent.hasExtra("carAccent")) {
            app.prefs.carAccent = intent.getIntExtra("carAccent", app.prefs.carAccent)
            CarStyles.reload()
        }
        if (intent.hasExtra("carSongInfo")) app.prefs.carSongInfo = intent.getBooleanExtra("carSongInfo", true)
        if (intent.hasExtra("guidance")) {
            app.prefs.guidanceMode = intent.getIntExtra("guidance", CarAudioBus.GUIDANCE_UNTOUCHED)
            CarAudioBus.guidance = app.prefs.guidanceMode
        }
        intent.getStringExtra("language")?.let { Lang.set(Language.of(it)) }
        if (intent.getBooleanExtra("supercast", false)) {
            SuperCast.startAsync(applicationContext)
        }
        if (intent.getBooleanExtra("supercastBridge", false)) {
            SuperCast.startAsync(applicationContext, bridge = true)
        }
        intent.getStringExtra("openOnConnect")?.let { app.prefs.openOnConnect = if (it == "none") "" else it }
        if (intent.hasExtra("wheelNext")) app.prefs.wheelLongNext = intent.getIntExtra("wheelNext", 0)
        if (intent.hasExtra("wheelPrev")) app.prefs.wheelLongPrev = intent.getIntExtra("wheelPrev", 0)
        if (intent.hasExtra("carInfoRight")) { app.prefs.carInfoRight = intent.getBooleanExtra("carInfoRight", false); CarStyles.reload() }
        if (intent.hasExtra("carColumns")) { app.prefs.carColumns = intent.getIntExtra("carColumns", 0); CarStyles.reload() }
        if (intent.hasExtra("carShowClock")) { app.prefs.carShowClock = intent.getBooleanExtra("carShowClock", true); CarStyles.reload() }
        if (intent.hasExtra("carShowMusic")) { app.prefs.carShowMusic = intent.getBooleanExtra("carShowMusic", true); CarStyles.reload() }
        if (intent.hasExtra("carShowAa")) { app.prefs.carShowAa = intent.getBooleanExtra("carShowAa", true); CarStyles.reload() }
        if (intent.hasExtra("carShowCarHome")) { app.prefs.carShowCarHome = intent.getBooleanExtra("carShowCarHome", true); CarStyles.reload() }
        intent.getStringExtra("carWidgets")?.let { app.prefs.carWidgets = it; CarStyles.reload() }
        if (intent.hasExtra("carDayNight")) { app.prefs.carDayNight = intent.getBooleanExtra("carDayNight", false); CarStyles.reload() }
        intent.getStringExtra("carNightBackground")?.let { app.prefs.carNightBackground = it; CarStyles.reload() }
        if (intent.hasExtra("carClock24")) {
            app.prefs.carClock24 = intent.getBooleanExtra("carClock24", true)
            CarStyles.reload()
        }
        intent.getStringExtra("pkgMusic")?.let { app.prefs.musicPackage = it }
    }
}

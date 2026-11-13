package app.ft.projection

import android.content.Context
import android.content.SharedPreferences
import app.ft.FTApp
import app.ft.carlife.CarLifeService
import app.ft.carlife.CarLifeSession
import app.ft.core.CarProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** Commands and screen state shared by independent projection transports. */
@OptIn(ExperimentalCoroutinesApi::class)
object CarControls {
    enum class Phase { IDLE, LINKED, NEGOTIATED, PROJECTING }

    data class ScreenState(
        val phase: Phase = Phase.IDLE,
        val via: String = "",
        val width: Int = 0,
        val height: Int = 0,
        val fps: Int = 0,
        val aaOverlay: Boolean = false,
        val mirroring: Boolean = false,
        val mirrorPackage: String = "",
        val waitingForShare: Boolean = false,
        val shareForSound: Boolean = false,
        val notice: Int = 0
    )

    interface Backend {
        val state: Flow<ScreenState>
        val screenKeys: Flow<Int>
        val openView: Flow<String>
        fun soundSoon()
        fun startAudio(context: Context)
        fun shareDeclined(context: Context, quiet: Boolean)
        fun cancelShareWait()
        fun startAa()
        fun startAaWireless()
        fun navBack()
        fun navHome()
        fun navRecents()
        fun navNotifications()
        fun rotatePhone()
        fun showPhone()
        fun launchApp(pkg: String)
        fun stopMirror()
        fun goToCarHome()
        fun goHome()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val iccoa = MutableStateFlow<Backend?>(null)
    private val protocol = callbackFlow {
        val prefs = FTApp.instance.prefs
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "carProtocol") trySend(prefs.carProtocol)
        }
        prefs.registerListener(listener)
        trySend(prefs.carProtocol)
        awaitClose { prefs.unregisterListener(listener) }
    }.distinctUntilChanged()
    private val selected = combine(protocol, iccoa) { mode, backend ->
        if (mode == CarProtocol.ICCOA) backend else CarLifeBackend
    }
    val state: StateFlow<ScreenState> = selected.flatMapLatest { it?.state ?: flowOf(ScreenState()) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), ScreenState())
    val screenKeys: Flow<Int> = selected.flatMapLatest { it?.screenKeys ?: emptyFlow() }
    val openView: Flow<String> = selected.flatMapLatest { it?.openView ?: emptyFlow() }

    fun attach(backend: Backend) { iccoa.value = backend }
    fun detach(backend: Backend) { iccoa.compareAndSet(backend, null) }
    private fun backend(): Backend? =
        if (FTApp.instance.prefs.carProtocol == CarProtocol.ICCOA) iccoa.value else CarLifeBackend
    fun soundSoon() { backend()?.soundSoon() }
    fun startAudio(context: Context) { backend()?.startAudio(context) }
    fun shareDeclined(context: Context, quiet: Boolean = false) { backend()?.shareDeclined(context, quiet) }
    fun cancelShareWait() { backend()?.cancelShareWait() }
    fun startAa() { backend()?.startAa() }
    fun startAaWireless() { backend()?.startAaWireless() }
    fun navBack() { backend()?.navBack() }
    fun navHome() { backend()?.navHome() }
    fun navRecents() { backend()?.navRecents() }
    fun navNotifications() { backend()?.navNotifications() }
    fun rotatePhone() { backend()?.rotatePhone() }
    fun showPhone() { backend()?.showPhone() }
    fun launchApp(pkg: String) { backend()?.launchApp(pkg) }
    fun stopMirror() { backend()?.stopMirror() }
    fun goToCarHome() { backend()?.goToCarHome() }
    fun goHome() { backend()?.goHome() }

    private object CarLifeBackend : Backend {
        override val state = CarLifeService.state.map { s ->
            val session = s.session
            val base = when (session) {
                is CarLifeSession.State.Projecting -> ScreenState(Phase.PROJECTING, session.via, session.streamWidth, session.streamHeight, session.fps)
                is CarLifeSession.State.Negotiated -> ScreenState(Phase.NEGOTIATED, session.via)
                is CarLifeSession.State.Linked -> ScreenState(Phase.LINKED, session.via)
                else -> ScreenState()
            }
            base.copy(aaOverlay = s.aaOverlay, mirroring = s.mirroring, mirrorPackage = s.mirrorPackage,
                waitingForShare = s.waitingForShare, shareForSound = s.shareForSound, notice = s.notice)
        }
        override val screenKeys get() = CarLifeService.screenKeys
        override val openView get() = CarLifeService.openView
        override fun soundSoon() { CarLifeService.soundSoon() }
        override fun startAudio(context: Context) { CarLifeService.startAudio(context) }
        override fun shareDeclined(context: Context, quiet: Boolean) { CarLifeService.shareDeclined(context, quiet) }
        override fun cancelShareWait() { CarLifeService.cancelShareWait() }
        override fun startAa() { CarLifeService.startAa() }
        override fun startAaWireless() { CarLifeService.startAaWireless() }
        override fun navBack() { CarLifeService.navBack() }
        override fun navHome() { CarLifeService.navHome() }
        override fun navRecents() { CarLifeService.navRecents() }
        override fun navNotifications() { CarLifeService.navNotifications() }
        override fun rotatePhone() { CarLifeService.rotatePhone() }
        override fun showPhone() { CarLifeService.showPhone() }
        override fun launchApp(pkg: String) { CarLifeService.launchApp(pkg) }
        override fun stopMirror() { CarLifeService.stopMirror() }
        override fun goToCarHome() { CarLifeService.goToCarHome() }
        override fun goHome() { CarLifeService.goHome() }
    }
}

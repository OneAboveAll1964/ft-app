package app.ft.aa

import android.content.Context
import app.ft.FTApp
import app.ft.R
import app.ft.core.DiagLog
import app.ft.core.Prefs
import app.ft.core.Root
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

object AaConnectionPreparation {
    enum class Status(val message: Int) {
        CHECKING(R.string.aa_prepare_checking),
        READY(R.string.aa_prepare_ready),
        LOCKED(R.string.aa_prepare_locked),
        NO_HELPER(R.string.aa_prepare_no_helper),
        NOT_INSTALLED(R.string.aa_prepare_not_installed),
        FAILED(R.string.aa_prepare_failed)
    }

    suspend fun prepare(context: Context, prefs: Prefs, report: (Status) -> Unit) {
        fun checkProfile() {
            if (FTApp.instance.prefs.profileId != prefs.profileId) throw CancellationException("Vehicle changed")
        }
        checkProfile()
        AaKeep.preparing()
        report(Status.CHECKING)
        val result = try {
            runInterruptible(Dispatchers.IO) {
                when {
                    AaInstaller.serverRunning(prefs.aaSelfPort) -> AaSwitch.Result.ON
                    !AaInstaller.installed(context) || AaInstaller.stub(context) -> AaSwitch.Result.NOT_INSTALLED
                    Root.granted && AaServer.start(context) -> AaSwitch.Result.ON
                    AaSwitch.locked(context) -> AaSwitch.Result.LOCKED
                    else -> AaSwitch.start(context)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagLog.w("AA", "Pre-connection preparation failed (${e.javaClass.simpleName})")
            AaSwitch.Result.FAILED
        }
        checkProfile()
        AaKeep.prepared(prefs.profileId, result)
        val status = when (result) {
            AaSwitch.Result.ON -> Status.READY
            AaSwitch.Result.LOCKED -> Status.LOCKED
            AaSwitch.Result.NO_HELPER -> Status.NO_HELPER
            AaSwitch.Result.NOT_INSTALLED -> Status.NOT_INSTALLED
            AaSwitch.Result.FAILED -> Status.FAILED
        }
        DiagLog.i("AA", "Pre-connection preparation: $result; continuing vehicle connection")
        report(status)
    }
}

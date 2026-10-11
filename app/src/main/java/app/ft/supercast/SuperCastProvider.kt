package app.ft.supercast

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import app.ft.core.DiagLog

class SuperCastProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != Process.ROOT_UID) {
            DiagLog.w(TAG, "a process at uid $uid asked for the SuperCast bridge and was refused")
            return null
        }
        if (method != GET_CLIENT) return null
        val token = extras?.getString("token")
        if (token.isNullOrEmpty() || token != SuperCast.token) {
            DiagLog.w(TAG, "the SuperCast bridge was asked for with the wrong token")
            return null
        }
        DiagLog.i(TAG, "handing the SuperCast bridge to the helper at uid $uid")
        return Bundle().apply { putBinder("client", SuperCast.client.asBinder()) }
    }

    companion object {
        private const val TAG = "SuperCast"
        const val AUTHORITY = "app.ft.supercast"
        const val GET_CLIENT = "getClient"
        private const val SHELL_UID = 2000
    }
}

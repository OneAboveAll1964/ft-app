package app.ft.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.ft.FTApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Date

object AutoBackup {
    const val DIR_NAME = "backups"
    const val LATEST_FILE = "ft-backup-latest.json"
    const val PREV_FILE = "ft-backup-previous.json"

    enum class Trigger {
        MANUAL,
        DRIVE_ENDED,
        APP_START
    }

    data class BackupInfo(
        val file: File,
        val timestamp: Long,
        val carCount: Int,
        val sizeBytes: Long
    ) {
        fun formattedDate(context: Context): String {
            val df = android.text.format.DateFormat.getMediumDateFormat(context)
            val tf = android.text.format.DateFormat.getTimeFormat(context)
            val d = Date(timestamp)
            return "${df.format(d)} ${tf.format(d)}"
        }
    }

    fun backupDir(context: Context): File {
        val dir = context.getExternalFilesDir(DIR_NAME) ?: File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun latestFile(context: Context): File = File(backupDir(context), LATEST_FILE)
    fun previousFile(context: Context): File = File(backupDir(context), PREV_FILE)

    fun getFolderDisplayName(context: Context, treeUri: Uri?): String {
        if (treeUri == null) return "Folder"
        val fallback = runCatching { treeUri.lastPathSegment.orEmpty() }.getOrNull().orEmpty()
        return runCatching {
            val resolver = context.contentResolver ?: return fallback.ifEmpty { "Folder" }
            val docId = DocumentsContract.getTreeDocumentId(treeUri) ?: return fallback.ifEmpty { "Folder" }
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId) ?: return fallback.ifEmpty { "Folder" }
            resolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback.ifEmpty { "Folder" }
    }

    fun syncToSafFolder(context: Context, treeUri: Uri, json: String): Boolean {
        return runCatching {
            val resolver = context.contentResolver
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

            var targetUri: Uri? = null
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                    if (name == LATEST_FILE) {
                        val docId = cursor.getString(idCol)
                        targetUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        break
                    }
                }
            }

            if (targetUri == null) {
                targetUri = DocumentsContract.createDocument(resolver, parentUri, "application/json", LATEST_FILE)
            }

            if (targetUri != null) {
                resolver.openOutputStream(targetUri, "wt")?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                DiagLog.i("Backup", "Auto-backup synced to SAF folder: $treeUri")
                true
            } else {
                DiagLog.w("Backup", "Could not create document in SAF folder: $treeUri")
                false
            }
        }.onFailure {
            DiagLog.w("Backup", "Failed to sync auto-backup to SAF folder: ${it.message}")
        }.getOrDefault(false)
    }

    fun info(context: Context, file: File = latestFile(context)): BackupInfo? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            val text = file.readText()
            val json = JSONObject(text)
            val ts = json.optLong("timestamp", file.lastModified())
            val cars = json.optJSONArray("profiles")?.length() ?: 1
            BackupInfo(file, ts, cars, file.length())
        }.getOrNull()
    }

    fun performBackup(
        context: Context,
        profiles: CarProfiles = FTApp.instance.cars,
        prefs: Prefs = FTApp.instance.prefs
    ): File? = synchronized(this) {
        val json = Backup.everythingAllCars(profiles, prefs)
        val dir = backupDir(context)
        val latest = File(dir, LATEST_FILE)
        val prev = File(dir, PREV_FILE)
        val tmp = File(dir, "ft-backup-tmp.json")

        return runCatching {
            FileOutputStream(tmp).use { it.write(json.toByteArray(Charsets.UTF_8)) }
            if (latest.exists()) {
                if (prev.exists()) prev.delete()
                latest.renameTo(prev)
            }
            if (tmp.renameTo(latest)) {
                prefs.lastAutoBackupTime = System.currentTimeMillis()
                DiagLog.i("Backup", "Auto-backup saved to ${latest.absolutePath} (${profiles.state.value.profiles.size} cars)")
                val folderUriStr = prefs.autoBackupFolderUri
                if (folderUriStr.isNotEmpty()) {
                    runCatching {
                        syncToSafFolder(context, Uri.parse(folderUriStr), json)
                    }
                }
                latest
            } else {
                null
            }
        }.onFailure {
            DiagLog.e("Backup", "Auto-backup failed", it)
        }.getOrNull()
    }

    fun trigger(context: Context, trigger: Trigger) {
        val prefs = runCatching { FTApp.instance.prefs }.getOrNull() ?: return
        val interval = prefs.autoBackupInterval
        if (interval == Prefs.AUTO_BACKUP_OFF && trigger != Trigger.MANUAL) return

        val now = System.currentTimeMillis()
        val last = prefs.lastAutoBackupTime

        val shouldRun = when (trigger) {
            Trigger.MANUAL -> true
            Trigger.DRIVE_ENDED -> {
                when (interval) {
                    Prefs.AUTO_BACKUP_AFTER_DRIVE -> true
                    Prefs.AUTO_BACKUP_DAILY -> now - last >= 24 * 3600 * 1000L
                    Prefs.AUTO_BACKUP_WEEKLY -> now - last >= 7 * 24 * 3600 * 1000L
                    else -> false
                }
            }
            Trigger.APP_START -> {
                when (interval) {
                    Prefs.AUTO_BACKUP_DAILY -> now - last >= 24 * 3600 * 1000L
                    Prefs.AUTO_BACKUP_WEEKLY -> now - last >= 7 * 24 * 3600 * 1000L
                    else -> false
                }
            }
        }

        if (shouldRun) {
            val app = context.applicationContext as? FTApp ?: FTApp.instance
            app.scope.launch(Dispatchers.IO) {
                performBackup(app)
            }
        }
    }

    fun restoreLatest(
        context: Context,
        profiles: CarProfiles = FTApp.instance.cars,
        prefs: Prefs = FTApp.instance.prefs
    ): Int {
        val file = latestFile(context)
        if (!file.exists()) return 0
        val text = runCatching { file.readText() }.getOrNull() ?: return 0
        return Backup.restore(prefs, text, profiles = profiles)
    }
}

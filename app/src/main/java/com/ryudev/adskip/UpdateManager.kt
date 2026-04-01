package com.ryudev.adskip

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.net.toUri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

class UpdateManager(private val context: Context) {

    private val client = OkHttpClient()
    private val githubApiUrl = "https://api.github.com/repos/romiyusnandar/AdSkip/releases/latest"
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class UpdateInfo(
        val newVersion: String,
        val downloadUrl: String,
        val fileName: String
    )

    data class CompletedDownload(
        val apkUri: Uri,
        val versionName: String
    )

    fun checkForUpdates(
        currentVersion: String,
        onUpdateAvailable: (UpdateInfo) -> Unit,
        onUpToDate: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        thread {
            try {
                val request = Request.Builder().url(githubApiUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        onError("Failed to fetch update information")
                        return@thread
                    }

                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        onError("Failed to read update information")
                        return@thread
                    }

                    val json = JSONObject(body)
                    val latestVersion = json.getString("tag_name").removePrefix("v")
                    val apkAsset = findApkAsset(json)

                    if (apkAsset == null) {
                        onError("No APK file found in latest release")
                        return@thread
                    }

                    if (isNewerVersion(currentVersion, latestVersion)) {
                        onUpdateAvailable(
                            UpdateInfo(
                                newVersion = latestVersion,
                                downloadUrl = apkAsset.first,
                                fileName = apkAsset.second
                            )
                        )
                    } else {
                        onUpToDate()
                    }
                }
            } catch (e: Exception) {
                onError(e.message ?: "Failed to fetch update information")
            }
        }
    }

    fun downloadUpdate(updateInfo: UpdateInfo): Long {
        val request = DownloadManager.Request(updateInfo.downloadUrl.toUri())
            .setTitle("Downloading AdSkip ${updateInfo.newVersion}")
            .setDescription("Preparing update package")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                updateInfo.fileName
            )
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)
        prefs.edit()
            .putLong(KEY_PENDING_DOWNLOAD_ID, downloadId)
            .putString(KEY_PENDING_VERSION_NAME, updateInfo.newVersion)
            .remove(KEY_PENDING_APK_URI)
            .apply()
        return downloadId
    }

    fun consumeCompletedDownload(downloadId: Long): CompletedDownload? {
        val expectedId = prefs.getLong(KEY_PENDING_DOWNLOAD_ID, -1L)
        if (downloadId <= 0L || expectedId != downloadId) return null

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return null

            val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusColumn == -1) return null
            val status = cursor.getInt(statusColumn)
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                clearPendingDownload()
                return null
            }

            val uri = downloadManager.getUriForDownloadedFile(downloadId) ?: run {
                val localUriColumn = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                if (localUriColumn == -1) return null
                val localUri = cursor.getString(localUriColumn) ?: return null
                localUri.toUri()
            }

            val versionName = prefs.getString(KEY_PENDING_VERSION_NAME, "0") ?: "0"
            clearPendingDownload()
            return CompletedDownload(apkUri = uri, versionName = versionName)
        }
    }

    fun canInstallPackages(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    }

    fun installApk(apkUri: Uri) {
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }

    fun openUnknownSourcesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri()
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun savePendingInstallUri(apkUri: Uri) {
        prefs.edit().putString(KEY_PENDING_APK_URI, apkUri.toString()).apply()
    }

    fun consumePendingInstallUri(): Uri? {
        val pending = prefs.getString(KEY_PENDING_APK_URI, null) ?: return null
        prefs.edit().remove(KEY_PENDING_APK_URI).apply()
        return pending.toUri()
    }

    private fun clearPendingDownload() {
        prefs.edit()
            .remove(KEY_PENDING_DOWNLOAD_ID)
            .remove(KEY_PENDING_VERSION_NAME)
            .apply()
    }

    private fun findApkAsset(json: JSONObject): Pair<String, String>? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name").orEmpty()
            if (!name.lowercase(Locale.US).endsWith(".apk")) continue
            val url = asset.optString("browser_download_url").orEmpty()
            if (url.isBlank()) continue
            return url to name
        }
        return null
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = current.split('.').mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split('.').mapNotNull { it.toIntOrNull() }
        val size = maxOf(currentParts.size, latestParts.size)

        for (i in 0 until size) {
            val currentPart = currentParts.getOrElse(i) { 0 }
            val latestPart = latestParts.getOrElse(i) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }

        return latest != current
    }

    companion object {
        private const val PREFS_NAME = "update_prefs"
        private const val KEY_PENDING_DOWNLOAD_ID = "pending_download_id"
        private const val KEY_PENDING_VERSION_NAME = "pending_version_name"
        private const val KEY_PENDING_APK_URI = "pending_apk_uri"
    }
}

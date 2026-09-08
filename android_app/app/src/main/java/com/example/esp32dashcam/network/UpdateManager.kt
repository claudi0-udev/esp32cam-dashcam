package com.example.esp32dashcam.network

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseAsset(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String
)

data class GitHubRelease(
    val tagName: String,
    val version: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val apkAsset: ReleaseAsset?,
    val firmwareAsset: ReleaseAsset?
)

object UpdateManager {

    private const val GITHUB_REPO = "claudi0-udev/esp32cam-dashcam"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private const val PREFS_NAME = "dashcam_updates_pref"
    private const val PREF_CACHED_FW_VER = "cached_firmware_version"
    private const val CACHED_FW_FILENAME = "cached_firmware.bin"

    /**
     * Queries the latest GitHub release.
     */
    suspend fun fetchLatestRelease(): Result<GitHubRelease> = withContext(Dispatchers.IO) {
        try {
            val url = URL(LATEST_RELEASE_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "ESP32Dashcam-AndroidApp")
            }

            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                return@withContext Result.failure(Exception("GitHub API HTTP $code: $err"))
            }

            val responseText = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()

            val json = JSONObject(responseText)
            val tagName = json.optString("tag_name", "")
            val cleanVersion = tagName.trimStart('v', 'V')
            val title = json.optString("name", tagName)
            val body = json.optString("body", "")
            val publishedAt = json.optString("published_at", "")

            val assetsArray = json.optJSONArray("assets")
            var apkAsset: ReleaseAsset? = null
            var firmwareAsset: ReleaseAsset? = null

            if (assetsArray != null) {
                for (i in 0 until assetsArray.length()) {
                    val a = assetsArray.getJSONObject(i)
                    val aName = a.optString("name", "")
                    val aSize = a.optLong("size", 0L)
                    val aUrl = a.optString("browser_download_url", "")
                    val asset = ReleaseAsset(name = aName, sizeBytes = aSize, downloadUrl = aUrl)

                    if (aName.endsWith(".apk", ignoreCase = true)) {
                        apkAsset = asset
                    } else if (aName.equals("firmware.bin", ignoreCase = true) ||
                        (aName.endsWith(".bin", ignoreCase = true) && !aName.contains("merged", ignoreCase = true))) {
                        firmwareAsset = asset
                    }
                }
            }

            Result.success(
                GitHubRelease(
                    tagName = tagName,
                    version = cleanVersion,
                    title = title,
                    notes = body,
                    publishedAt = publishedAt,
                    apkAsset = apkAsset,
                    firmwareAsset = firmwareAsset
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Compares version numbers (e.g. "1.1.0" vs "1.0.0").
     * Returns true if remoteVersion is strictly greater than localVersion.
     */
    fun isNewerVersion(remoteVersion: String, localVersion: String): Boolean {
        val cleanRemote = remoteVersion.trimStart('v', 'V').trim()
        val cleanLocal = localVersion.trimStart('v', 'V').trim()
        if (cleanRemote.isBlank() || cleanLocal.isBlank()) return false

        val remoteParts = cleanRemote.split(".").mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }
        val localParts = cleanLocal.split(".").mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    /**
     * Downloads APK asset with progress reporting.
     */
    suspend fun downloadApk(
        context: Context,
        asset: ReleaseAsset,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()

            val apkFile = File(updatesDir, "esp32cam_dashcam.apk")
            if (apkFile.exists()) apkFile.delete()

            val url = URL(asset.downloadUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ESP32Dashcam-AndroidApp")
            }

            val totalBytes = if (asset.sizeBytes > 0) asset.sizeBytes else conn.contentLength.toLong()
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            onProgress(downloadedBytes.toFloat() / totalBytes)
                        }
                    }
                    output.flush()
                }
            }
            conn.disconnect()

            Result.success(apkFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Triggers Android package installer to update this application.
     */
    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Downloads firmware binary to app internal files directory for offline OTA flashing.
     */
    suspend fun cacheFirmware(
        context: Context,
        asset: ReleaseAsset,
        version: String,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val fwFile = File(context.filesDir, CACHED_FW_FILENAME)
            if (fwFile.exists()) fwFile.delete()

            val url = URL(asset.downloadUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ESP32Dashcam-AndroidApp")
            }

            val totalBytes = if (asset.sizeBytes > 0) asset.sizeBytes else conn.contentLength.toLong()
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(fwFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0 && onProgress != null) {
                            onProgress(downloadedBytes.toFloat() / totalBytes)
                        }
                    }
                    output.flush()
                }
            }
            conn.disconnect()

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(PREF_CACHED_FW_VER, version).apply()

            Result.success(fwFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Returns the cached firmware version, or null if none cached.
     */
    fun getCachedFirmwareVersion(context: Context): String? {
        val fwFile = File(context.filesDir, CACHED_FW_FILENAME)
        if (!fwFile.exists() || fwFile.length() == 0L) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_CACHED_FW_VER, null)
    }

    /**
     * Reads cached firmware bytes for OTA upload.
     */
    fun getCachedFirmwareBytes(context: Context): ByteArray? {
        val fwFile = File(context.filesDir, CACHED_FW_FILENAME)
        return if (fwFile.exists() && fwFile.length() > 0) fwFile.readBytes() else null
    }

    /**
     * Clears cached firmware.
     */
    fun clearCachedFirmware(context: Context) {
        val fwFile = File(context.filesDir, CACHED_FW_FILENAME)
        if (fwFile.exists()) fwFile.delete()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(PREF_CACHED_FW_VER).apply()
    }
}

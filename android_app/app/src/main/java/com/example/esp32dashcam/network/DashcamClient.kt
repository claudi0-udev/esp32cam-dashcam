package com.example.esp32dashcam.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

data class RemoteVideo(
    val name: String,
    val sizeBytes: Long,
    val sizeFormatted: String
)

object DashcamClient {
    private const val DEFAULT_BASE_URL = "http://192.168.4.1"

    /**
     * Checks whether the Dashcam Wi-Fi Web Server is reachable.
     */
    suspend fun checkConnection(baseUrl: String = DEFAULT_BASE_URL): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(baseUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fetches video list from Dashcam HTML portal.
     */
    suspend fun fetchVideoList(baseUrl: String = DEFAULT_BASE_URL): Result<List<RemoteVideo>> = withContext(Dispatchers.IO) {
        try {
            val url = URL(baseUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 5000
                requestMethod = "GET"
            }
            if (conn.responseCode != 200) {
                return@withContext Result.failure(Exception("HTTP Error: ${conn.responseCode}"))
            }

            val html = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()

            val videos = mutableListOf<RemoteVideo>()

            val itemRegex = Pattern.compile(
                "value=['\"]([^'\"]+\\.avi)['\"][^>]*>.*?filesize['\"]>\\(([^)]+)\\)",
                Pattern.DOTALL or Pattern.CASE_INSENSITIVE
            )
            val matcher = itemRegex.matcher(html)
            while (matcher.find()) {
                val name = matcher.group(1)?.trim() ?: ""
                val sizeText = matcher.group(2)?.trim() ?: ""
                var sizeBytes = 0L
                try {
                    val num = sizeText.replace("MB", "").trim().toDouble()
                    sizeBytes = (num * 1024 * 1024).toLong()
                } catch (_: Exception) {}
                if (name.isNotEmpty()) {
                    videos.add(RemoteVideo(name = name, sizeBytes = sizeBytes, sizeFormatted = sizeText))
                }
            }

            if (videos.isEmpty()) {
                val fallbackRegex = Pattern.compile("href=['\"]/download\\?file=([^'\"]+\\.avi)['\"]", Pattern.CASE_INSENSITIVE)
                val fbMatcher = fallbackRegex.matcher(html)
                val seen = mutableSetOf<String>()
                while (fbMatcher.find()) {
                    val name = fbMatcher.group(1)?.trim() ?: ""
                    if (name.isNotEmpty() && seen.add(name)) {
                        videos.add(RemoteVideo(name = name, sizeBytes = 0L, sizeFormatted = ""))
                    }
                }
            }

            videos.sortBy { it.name }
            Result.success(videos)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads a single video file from Dashcam with progress tracking.
     */
    suspend fun downloadVideo(
        baseUrl: String = DEFAULT_BASE_URL,
        filename: String,
        destinationFile: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val fileUrl = "$baseUrl/download?file=$filename"
            val conn = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 10000
                requestMethod = "GET"
            }
            if (conn.responseCode != 200) {
                return@withContext Result.failure(Exception("Error al descargar $filename: HTTP ${conn.responseCode}"))
            }

            val totalBytes = conn.contentLengthLong
            destinationFile.parentFile?.mkdirs()

            conn.inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    var currentProgress = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        currentProgress += bytesRead
                        onProgress?.invoke(currentProgress, totalBytes)
                    }
                    output.flush()
                }
            }
            conn.disconnect()
            Result.success(destinationFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Retrieves camera configuration from ESP32 MicroSD (/config endpoint).
     */
    suspend fun fetchConfig(baseUrl: String = DEFAULT_BASE_URL): Result<DashcamConfig> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/config")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
            }
            if (conn.responseCode != 200) {
                return@withContext Result.failure(Exception("HTTP ${conn.responseCode}"))
            }
            val jsonStr = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()

            val json = JSONObject(jsonStr)
            val config = DashcamConfig(
                resolution = json.optString("resolution", "VGA"),
                fps = json.optInt("fps", 3),
                clipDuration = json.optInt("clip_duration", 60),
                quality = json.optInt("quality", 12),
                vflip = json.optInt("vflip", 0) == 1,
                hmirror = json.optInt("hmirror", 0) == 1,
                brightness = json.optInt("brightness", 0),
                contrast = json.optInt("contrast", 0),
                saturation = json.optInt("saturation", 0),
                wbMode = json.optInt("wb_mode", 0)
            )
            Result.success(config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Saves new camera configuration directly into dashcam.cfg via HTTP POST.
     */
    suspend fun saveConfig(config: DashcamConfig, baseUrl: String = DEFAULT_BASE_URL): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/config")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            val params = listOf(
                "resolution" to config.resolution,
                "fps" to config.fps.toString(),
                "clip_duration" to config.clipDuration.toString(),
                "quality" to config.quality.toString(),
                "vflip" to (if (config.vflip) "1" else "0"),
                "hmirror" to (if (config.hmirror) "1" else "0"),
                "brightness" to config.brightness.toString(),
                "contrast" to config.contrast.toString(),
                "saturation" to config.saturation.toString(),
                "wb_mode" to config.wbMode.toString()
            ).joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }

            conn.outputStream.use { os ->
                os.write(params.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            conn.disconnect()
            if (code == 200) {
                Result.success(true)
            } else {
                Result.failure(Exception("Error HTTP $code guardando configuración"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes a video file from the ESP32 MicroSD card.
     */
    suspend fun deleteRemoteFile(filename: String, baseUrl: String = DEFAULT_BASE_URL): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/delete")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val body = "file=${URLEncoder.encode(filename, "UTF-8")}"
            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            conn.disconnect()
            if (code == 200) Result.success(true)
            else Result.failure(Exception("Error al borrar en MicroSD: HTTP $code"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Renames a video file on the ESP32 MicroSD card.
     */
    suspend fun renameRemoteFile(oldName: String, newName: String, baseUrl: String = DEFAULT_BASE_URL): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/rename")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val body = "old=${URLEncoder.encode(oldName, "UTF-8")}&new=${URLEncoder.encode(newName, "UTF-8")}"
            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            conn.disconnect()
            if (code == 200) Result.success(true)
            else if (code == 409) Result.failure(Exception("Ya existe un archivo con ese nombre"))
            else Result.failure(Exception("Error al renombrar: HTTP $code"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads the latest official firmware binary directly from GitHub.
     */
    suspend fun downloadLatestFirmwareFromGitHub(): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://raw.githubusercontent.com/claudi0-udev/esp32cam-dashcam/main/bin/firmware.bin")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 20000
                requestMethod = "GET"
            }
            if (conn.responseCode != 200) {
                return@withContext Result.failure(Exception("Error HTTP ${conn.responseCode} al descargar de GitHub"))
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            Result.success(bytes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Uploads firmware binary to ESP32 /update endpoint with progress reporting.
     */
    suspend fun uploadFirmware(
        firmwareBytes: ByteArray,
        baseUrl: String = DEFAULT_BASE_URL,
        onProgress: (progressFraction: Float) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val boundary = "==DashcamFirmwareBoundary=="
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            val url = URL("$baseUrl/update")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 60000
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                setRequestProperty("Connection", "Keep-Alive")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            val head = "$twoHyphens$boundary$lineEnd" +
                    "Content-Disposition: form-data; name=\"update\"; filename=\"firmware.bin\"$lineEnd" +
                    "Content-Type: application/octet-stream$lineEnd$lineEnd"
            val tail = "$lineEnd$twoHyphens$boundary$twoHyphens$lineEnd"

            val headBytes = head.toByteArray(Charsets.UTF_8)
            val tailBytes = tail.toByteArray(Charsets.UTF_8)
            val totalBytes = headBytes.size + firmwareBytes.size + tailBytes.size

            conn.setFixedLengthStreamingMode(totalBytes)

            conn.outputStream.use { os ->
                os.write(headBytes)
                val bufferSize = 4096
                var offset = 0
                val totalLength = firmwareBytes.size
                while (offset < totalLength) {
                    val count = Math.min(bufferSize, totalLength - offset)
                    os.write(firmwareBytes, offset, count)
                    offset += count
                    onProgress(offset.toFloat() / totalLength)
                }
                os.write(tailBytes)
                os.flush()
            }

            val code = conn.responseCode
            val responseMsg = if (code == 200) {
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } else {
                BufferedReader(InputStreamReader(conn.errorStream ?: conn.inputStream)).use { it.readText() }
            }
            conn.disconnect()

            if (code == 200) {
                Result.success(responseMsg)
            } else {
                Result.failure(Exception("HTTP $code: $responseMsg"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Queries current camera firmware version via /version or /config.
     */
    suspend fun getFirmwareVersion(baseUrl: String = DEFAULT_BASE_URL): Result<String> = withContext(Dispatchers.IO) {
        try {
            val versionUrl = URL("$baseUrl/version")
            val conn = (versionUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code == 200) {
                val txt = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                conn.disconnect()
                val json = JSONObject(txt)
                val ver = json.optString("version", "")
                if (ver.isNotBlank()) return@withContext Result.success(ver)
            } else {
                conn.disconnect()
            }

            val cfgUrl = URL("$baseUrl/config")
            val cfgConn = (cfgUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
            }
            if (cfgConn.responseCode == 200) {
                val txt = BufferedReader(InputStreamReader(cfgConn.inputStream)).use { it.readText() }
                cfgConn.disconnect()
                val json = JSONObject(txt)
                val ver = json.optString("firmware_version", "1.0.0")
                return@withContext Result.success(ver)
            }
            cfgConn.disconnect()
            Result.success("1.0.0")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

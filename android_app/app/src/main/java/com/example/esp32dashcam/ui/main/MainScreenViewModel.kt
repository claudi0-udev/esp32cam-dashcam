package com.example.esp32dashcam.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.esp32dashcam.network.DashcamClient
import com.example.esp32dashcam.network.DashcamConfig
import com.example.esp32dashcam.network.RemoteVideo
import com.example.esp32dashcam.utils.AviMerger
import com.example.esp32dashcam.wifi.WifiManagerHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocalVideo(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val sizeFormatted: String,
    val lastModified: Long
)

data class MainUiState(
    val isConnected: Boolean = false,
    val isRefreshing: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadStatus: String = "",
    val downloadProgress: Float = 0f,
    val remoteVideos: List<RemoteVideo> = emptyList(),
    val selectedRemoteVideos: Set<String> = emptySet(),
    val localVideos: List<LocalVideo> = emptyList(),
    val selectedLocalVideos: Set<String> = emptySet(),
    val config: DashcamConfig = DashcamConfig(),
    val isLoadingConfig: Boolean = false,
    val isSavingConfig: Boolean = false,
    val isConnectingWifi: Boolean = false,
    val isUpdatingFirmware: Boolean = false,
    val otaProgress: Float = 0f,
    val otaStatus: String = "",
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val wifiHelper = WifiManagerHelper(application)

    private val videosDir: File
        get() {
            val dir = File(getApplication<Application>().getExternalFilesDir(null), "videos")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    init {
        loadLocalVideos()
        checkConnectionAndRefresh()
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }

    fun connectDirectToDashcam() {
        _uiState.value = _uiState.value.copy(
            isConnectingWifi = true,
            downloadStatus = "Conectando a Dashcam-WiFi..."
        )
        wifiHelper.connectDirectly(
            ssid = "Dashcam-WiFi",
            passphrase = "12345678",
            onConnected = {
                viewModelScope.launch {
                    delay(1200)
                    _uiState.value = _uiState.value.copy(
                        isConnectingWifi = false,
                        successMessage = "¡Conectado a Dashcam-WiFi!"
                    )
                    checkConnectionAndRefresh()
                }
            },
            onFailed = { reason ->
                _uiState.value = _uiState.value.copy(
                    isConnectingWifi = false,
                    errorMessage = reason
                )
            }
        )
    }

    fun openWifiPanel() {
        wifiHelper.openWifiSettings()
    }

    fun checkConnectionAndRefresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null)
            val connected = DashcamClient.checkConnection()
            if (connected) {
                val listResult = DashcamClient.fetchVideoList()
                listResult.fold(
                    onSuccess = { list ->
                        _uiState.value = _uiState.value.copy(
                            isConnected = true,
                            isRefreshing = false,
                            remoteVideos = list
                        )
                    },
                    onFailure = { err ->
                        _uiState.value = _uiState.value.copy(
                            isConnected = true,
                            isRefreshing = false,
                            errorMessage = "Conectado al ESP32 pero falló la lista: ${err.message}"
                        )
                    }
                )
                fetchCameraConfig()
            } else {
                _uiState.value = _uiState.value.copy(
                    isConnected = false,
                    isRefreshing = false
                )
            }
            loadLocalVideos()
        }
    }

    fun fetchCameraConfig() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingConfig = true)
            val res = DashcamClient.fetchConfig()
            res.fold(
                onSuccess = { cfg ->
                    _uiState.value = _uiState.value.copy(
                        config = cfg,
                        isLoadingConfig = false
                    )
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(isLoadingConfig = false)
                }
            )
        }
    }

    fun saveCameraConfig(configToSave: DashcamConfig) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSavingConfig = true, errorMessage = null)
            val res = DashcamClient.saveConfig(configToSave)
            res.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isSavingConfig = false,
                        config = configToSave,
                        successMessage = "¡Configuración guardada en la MicroSD con éxito!"
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isSavingConfig = false,
                        errorMessage = "Error al guardar configuración: ${err.message}"
                    )
                }
            )
        }
    }

    fun toggleSelectRemoteVideo(name: String) {
        val current = _uiState.value.selectedRemoteVideos.toMutableSet()
        if (current.contains(name)) current.remove(name) else current.add(name)
        _uiState.value = _uiState.value.copy(selectedRemoteVideos = current)
    }

    fun toggleSelectAllRemote() {
        val all = _uiState.value.remoteVideos.map { it.name }.toSet()
        val current = _uiState.value.selectedRemoteVideos
        val updated = if (current.size == all.size) emptySet() else all
        _uiState.value = _uiState.value.copy(selectedRemoteVideos = updated)
    }

    fun toggleSelectLocalVideo(filePath: String) {
        val current = _uiState.value.selectedLocalVideos.toMutableSet()
        if (current.contains(filePath)) current.remove(filePath) else current.add(filePath)
        _uiState.value = _uiState.value.copy(selectedLocalVideos = current)
    }

    fun toggleSelectAllLocal() {
        val all = _uiState.value.localVideos.map { it.file.absolutePath }.toSet()
        val current = _uiState.value.selectedLocalVideos
        val updated = if (current.size == all.size) emptySet() else all
        _uiState.value = _uiState.value.copy(selectedLocalVideos = updated)
    }

    fun loadLocalVideos() {
        val files = videosDir.listFiles { _, name -> name.endsWith(".avi") } ?: emptyArray()
        val list = files.map { f ->
            val sizeMb = f.length() / (1024.0 * 1024.0)
            LocalVideo(
                file = f,
                name = f.name,
                sizeBytes = f.length(),
                sizeFormatted = String.format(Locale.US, "%.2f MB", sizeMb),
                lastModified = f.lastModified()
            )
        }.sortedByDescending { it.lastModified }

        _uiState.value = _uiState.value.copy(localVideos = list)
    }

    /**
     * Download selected remote videos as separate files or merged into one.
     * When merged into one, successfully downloaded individual chunks are deleted to avoid duplicates.
     */
    fun downloadSelectedRemote(andMerge: Boolean = false) {
        val selected = _uiState.value.selectedRemoteVideos.toList().sorted()
        if (selected.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDownloading = true,
                downloadStatus = "Iniciando descarga...",
                downloadProgress = 0f,
                errorMessage = null
            )

            val downloadedFiles = mutableListOf<File>()
            var hadErrors = false

            for ((index, filename) in selected.withIndex()) {
                _uiState.value = _uiState.value.copy(
                    downloadStatus = "Descargando clip ${index + 1} de ${selected.size}: $filename",
                    downloadProgress = index.toFloat() / selected.size
                )

                val dest = File(videosDir, filename)
                val result = DashcamClient.downloadVideo(
                    filename = filename,
                    destinationFile = dest,
                    onProgress = { bytesRead, totalBytes ->
                        if (totalBytes > 0) {
                            val fileFraction = bytesRead.toFloat() / totalBytes
                            val overall = (index + fileFraction) / selected.size
                            _uiState.value = _uiState.value.copy(downloadProgress = overall)
                        }
                    }
                )

                result.fold(
                    onSuccess = { downloadedFiles.add(it) },
                    onFailure = {
                        hadErrors = true
                    }
                )
            }

            if (andMerge && downloadedFiles.size > 1) {
                _uiState.value = _uiState.value.copy(
                    downloadStatus = "⚡ Ensamblando video continuo...",
                    downloadProgress = 0.95f
                )

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val mergedFile = File(videosDir, "viaje_unido_$timestamp.avi")

                try {
                    val mergeOk = AviMerger.mergeAviFiles(downloadedFiles, mergedFile)
                    if (mergeOk && mergedFile.exists() && mergedFile.length() > 224) {
                        // REQUISITO: Borrar los videos individuales que fueron unidos con éxito
                        downloadedFiles.forEach { file ->
                            try { file.delete() } catch (_: Exception) {}
                        }
                        _uiState.value = _uiState.value.copy(
                            successMessage = "¡${downloadedFiles.size} clips unidos con éxito en ${mergedFile.name}! (Se borraron los fragmentos separados)"
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            errorMessage = "Fallo al generar el archivo unido."
                        )
                    }
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Descargados pero falló la unión: ${e.message}"
                    )
                }
            } else if (andMerge && downloadedFiles.size == 1) {
                _uiState.value = _uiState.value.copy(
                    successMessage = "Video descargado: ${downloadedFiles[0].name}"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    successMessage = if (hadErrors) "Descarga terminada con algunas alertas" else "¡${downloadedFiles.size} videos descargados!"
                )
            }

            _uiState.value = _uiState.value.copy(
                isDownloading = false,
                downloadStatus = "",
                downloadProgress = 1f,
                selectedRemoteVideos = emptySet()
            )
            loadLocalVideos()
        }
    }

    /**
     * Merge already downloaded local videos into one.
     * Deletes the original separated local files once merged.
     */
    fun mergeSelectedLocal() {
        val selectedPaths = _uiState.value.selectedLocalVideos.toList()
        if (selectedPaths.size < 2) return

        val files = selectedPaths.map { File(it) }.sortedBy { it.name }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDownloading = true,
                downloadStatus = "⚡ Ensamblando videos seleccionados...",
                downloadProgress = 0.5f
            )

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val mergedFile = File(videosDir, "viaje_unido_$timestamp.avi")

            try {
                val ok = AviMerger.mergeAviFiles(files, mergedFile)
                if (ok && mergedFile.exists() && mergedFile.length() > 224) {
                    // Borrar los originales locales
                    files.forEach { f ->
                        try { f.delete() } catch (_: Exception) {}
                    }
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        downloadStatus = "",
                        downloadProgress = 1f,
                        selectedLocalVideos = emptySet(),
                        successMessage = "¡Videos unidos con éxito en ${mergedFile.name}! (Fragmentos individuales eliminados)"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        downloadStatus = "",
                        errorMessage = "Fallo al unir videos locales."
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDownloading = false,
                    downloadStatus = "",
                    errorMessage = "Error al unir videos: ${e.message}"
                )
            }
            loadLocalVideos()
        }
    }

    fun deleteLocalVideo(file: File) {
        if (file.exists()) {
            file.delete()
            loadLocalVideos()
        }
    }

    override fun onCleared() {
        super.onCleared()
        wifiHelper.releaseNetworkCallback()
    }

    /**
     * Delete video from ESP32 MicroSD card.
     */
    fun deleteRemoteVideo(filename: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDownloading = true,
                downloadStatus = "Borrando $filename de la MicroSD..."
            )
            val res = DashcamClient.deleteRemoteFile(filename)
            res.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        downloadStatus = "",
                        successMessage = "Archivo $filename borrado de la MicroSD"
                    )
                    // Quitar de seleccionados si estaba
                    val sel = _uiState.value.selectedRemoteVideos.toMutableSet()
                    sel.remove(filename)
                    _uiState.value = _uiState.value.copy(selectedRemoteVideos = sel)
                    checkConnectionAndRefresh()
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        downloadStatus = "",
                        errorMessage = "Error al borrar: ${err.message}"
                    )
                }
            )
        }
    }

    /**
     * Rename video on ESP32 MicroSD card.
     */
    fun renameRemoteVideo(oldName: String, newName: String) {
        if (newName.isBlank() || oldName == newName) return
        val finalNewName = if (newName.endsWith(".avi", ignoreCase = true)) newName else "$newName.avi"

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isDownloading = true,
                downloadStatus = "Renombrando a $finalNewName..."
            )
            val res = DashcamClient.renameRemoteFile(oldName, finalNewName)
            res.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        downloadStatus = "",
                        successMessage = "Archivo renombrado a $finalNewName"
                    )
                    val sel = _uiState.value.selectedRemoteVideos.toMutableSet()
                    if (sel.remove(oldName)) {
                        sel.add(finalNewName)
                    }
                    _uiState.value = _uiState.value.copy(selectedRemoteVideos = sel)
                    checkConnectionAndRefresh()
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        downloadStatus = "",
                        errorMessage = "Error al renombrar: ${err.message}"
                    )
                }
            )
        }
    }

    /**
     * Uploads firmware binary to ESP32 via OTA.
     */
    fun updateFirmware(firmwareBytes: ByteArray) {
        if (firmwareBytes.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = "El archivo de firmware está vacío")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isUpdatingFirmware = true,
                otaProgress = 0f,
                otaStatus = "Iniciando transferencia de firmware (${firmwareBytes.size / 1024} KB)..."
            )
            val res = DashcamClient.uploadFirmware(firmwareBytes) { progress ->
                _uiState.value = _uiState.value.copy(
                    otaProgress = progress,
                    otaStatus = "Subiendo firmware: ${(progress * 100).toInt()}%"
                )
            }
            res.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        otaProgress = 1f,
                        otaStatus = "¡Firmware instalado con éxito! La cámara se está reiniciando..."
                    )
                    delay(5000)
                    _uiState.value = _uiState.value.copy(
                        isUpdatingFirmware = false,
                        otaStatus = "",
                        successMessage = "Firmware actualizado correctamente. Cámara reiniciada."
                    )
                    checkConnectionAndRefresh()
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isUpdatingFirmware = false,
                        otaStatus = "",
                        errorMessage = "Fallo al actualizar firmware: ${err.message}"
                    )
                }
            )
        }
    }

    /**
     * Reads a selected .bin file from Uri and triggers OTA update.
     */
    fun updateFirmwareFromUri(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                    it.readBytes()
                } ?: throw Exception("No se pudo leer el archivo seleccionado")
                updateFirmware(bytes)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = "Error leyendo archivo: ${e.message}")
            }
        }
    }

    /**
     * Downloads the latest official firmware from GitHub repository and sends it to ESP32.
     */
    fun downloadAndInstallFirmwareFromGitHub() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isUpdatingFirmware = true,
                otaProgress = 0f,
                otaStatus = "Descargando última versión oficial de GitHub..."
            )
            val downloadRes = DashcamClient.downloadLatestFirmwareFromGitHub()
            downloadRes.fold(
                onSuccess = { bytes ->
                    _uiState.value = _uiState.value.copy(
                        otaStatus = "Firmware descargado (${bytes.size / 1024} KB). Enviando a la cámara..."
                    )
                    updateFirmware(bytes)
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isUpdatingFirmware = false,
                        otaStatus = "",
                        errorMessage = "No se pudo descargar de GitHub: ${err.message}. Asegúrate de tener Internet (datos móviles)."
                    )
                }
            )
        }
    }
}

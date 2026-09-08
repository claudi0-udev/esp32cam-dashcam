package com.example.esp32dashcam.ui.config

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.esp32dashcam.BuildConfig
import com.example.esp32dashcam.network.DashcamConfig
import com.example.esp32dashcam.network.GitHubRelease
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigTabContent(
    config: DashcamConfig,
    isConnected: Boolean,
    isSaving: Boolean,
    isUpdatingFirmware: Boolean = false,
    otaProgress: Float = 0f,
    otaStatus: String = "",
    cameraFirmwareVersion: String = "1.0.0",
    isCheckingUpdates: Boolean = false,
    latestRelease: GitHubRelease? = null,
    isAppUpdateAvailable: Boolean = false,
    isFirmwareUpdateAvailable: Boolean = false,
    cachedFirmwareVersion: String? = null,
    isDownloadingAppUpdate: Boolean = false,
    appUpdateProgress: Float = 0f,
    isCachingFirmware: Boolean = false,
    onSaveConfig: (DashcamConfig) -> Unit,
    onRefreshConfig: () -> Unit,
    onCheckForUpdates: () -> Unit = {},
    onUpdateApp: () -> Unit = {},
    onInstallCachedFirmware: () -> Unit = {},
    onUpdateFirmwareUri: (Uri) -> Unit = {},
    onUpdateFirmwareFromGitHub: () -> Unit = {}
) {
    var resolution by remember(config.resolution) { mutableStateOf(config.resolution) }
    var fps by remember(config.fps) { mutableIntStateOf(config.fps) }
    var clipDuration by remember(config.clipDuration) { mutableIntStateOf(config.clipDuration) }
    var quality by remember(config.quality) { mutableIntStateOf(config.quality) }
    var vflip by remember(config.vflip) { mutableStateOf(config.vflip) }
    var hmirror by remember(config.hmirror) { mutableStateOf(config.hmirror) }
    var brightness by remember(config.brightness) { mutableIntStateOf(config.brightness) }
    var contrast by remember(config.contrast) { mutableIntStateOf(config.contrast) }
    var saturation by remember(config.saturation) { mutableIntStateOf(config.saturation) }
    var wbMode by remember(config.wbMode) { mutableIntStateOf(config.wbMode) }

    var pendingFirmwareUri by remember { mutableStateOf<Uri?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showGitHubConfirmDialog by remember { mutableStateOf(false) }

    val resolutions = listOf("QVGA", "CIF", "VGA", "SVGA", "HD")
    val wbOptions = listOf(
        0 to "Automático",
        1 to "Soleado",
        2 to "Nublado",
        3 to "Oficina",
        4 to "Hogar"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (!isConnected) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Text(
                    text = "⚠️ Para leer y aplicar ajustes, conéctate a la red Wi-Fi de la cámara (Dashcam-WiFi).",
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 14.sp
                )
            }
        }

        // Section 1: Video & Grabación
        Text(
            "📹 Grabación de Video",
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Resolución
                Text("Resolución de Video", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    resolutions.forEach { res ->
                        FilterChip(
                            selected = resolution.equals(res, ignoreCase = true),
                            onClick = { resolution = res },
                            label = { Text(res, fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // FPS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tasa de Cuadros (FPS)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("$fps FPS", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = fps.toFloat(),
                    onValueChange = { fps = it.roundToInt() },
                    valueRange = 1f..10f,
                    steps = 8
                )
                Text("Recomendado: 3 FPS para evitar calor y consumo.", fontSize = 11.sp, color = Color.Gray)

                Spacer(modifier = Modifier.height(16.dp))

                // Duración del Clip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Duración de cada clip", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("$clipDuration seg", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = clipDuration.toFloat(),
                    onValueChange = { clipDuration = it.roundToInt() },
                    valueRange = 15f..300f,
                    steps = 18
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Calidad JPEG
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Calidad JPEG (menor número = más nitidez)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("$quality", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = quality.toFloat(),
                    onValueChange = { quality = it.roundToInt() },
                    valueRange = 10f..40f,
                    steps = 29
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 2: Orientación y Cámara
        Text(
            "🔄 Orientación del Parabrisas",
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Invertir Verticalmente (V-Flip)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Actívalo si la cámara está montada boca abajo.", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(checked = vflip, onCheckedChange = { vflip = it })
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Espejo Horizontal (H-Mirror)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Invierte imagen izquierda/derecha.", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(checked = hmirror, onCheckedChange = { hmirror = it })
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 3: Ajustes de Imagen y Balance de Blancos
        Text(
            "🎨 Imagen y Exposición",
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Brillo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Brillo", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("$brightness", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = brightness.toFloat(),
                    onValueChange = { brightness = it.roundToInt() },
                    valueRange = -2f..2f,
                    steps = 3
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Contraste
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Contraste", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("$contrast", fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = contrast.toFloat(),
                    onValueChange = { contrast = it.roundToInt() },
                    valueRange = -2f..2f,
                    steps = 3
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Balance de blancos
                Text("Balance de Blancos", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    wbOptions.forEach { (mode, label) ->
                        FilterChip(
                            selected = wbMode == mode,
                            onClick = { wbMode = mode },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Save Button
        Button(
            onClick = {
                val newConfig = DashcamConfig(
                    resolution = resolution,
                    fps = fps,
                    clipDuration = clipDuration,
                    quality = quality,
                    vflip = vflip,
                    hmirror = hmirror,
                    brightness = brightness,
                    contrast = contrast,
                    saturation = saturation,
                    wbMode = wbMode
                )
                onSaveConfig(newConfig)
            },
            enabled = isConnected && !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
        ) {
            if (isSaving) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Guardando en MicroSD...")
            } else {
                Text("💾 Guardar Configuración en Cámara", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Section 4: Software and Firmware Updates
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "🚀 Actualizaciones",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = onCheckForUpdates, enabled = !isCheckingUpdates) {
                if (isCheckingUpdates) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("🔄", fontSize = 16.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))

        // Estado de versiones
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("📱 App Android: v${BuildConfig.VERSION_NAME}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = if (isConnected) "📷 Firmware Dashcam: v$cameraFirmwareVersion" else "📷 Firmware Dashcam: (Conecta a Dashcam-WiFi para ver)",
                    fontSize = 13.sp,
                    color = if (isConnected) MaterialTheme.colorScheme.onSurface else Color.Gray
                )
                if (cachedFirmwareVersion != null) {
                    Text("💾 Firmware en memoria del teléfono: v$cachedFirmwareVersion", fontSize = 12.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Actualización de App Android
        if (isAppUpdateAvailable) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                border = BorderStroke(1.dp, Color(0xFFFFA000)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("✨ Nueva versión de la App disponible: ${latestRelease?.tagName}", fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                    if (!latestRelease?.notes.isNullOrBlank()) {
                        Text(
                            text = latestRelease!!.notes.take(160) + if (latestRelease.notes.length > 160) "..." else "",
                            fontSize = 12.sp,
                            color = Color.DarkGray,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isDownloadingAppUpdate) {
                        LinearProgressIndicator(progress = { appUpdateProgress }, modifier = Modifier.fillMaxWidth().height(6.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Descargando actualización: ${(appUpdateProgress * 100).toInt()}%", fontSize = 12.sp)
                    } else {
                        Button(
                            onClick = onUpdateApp,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA000)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📲 Descargar e Instalar App", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Actualización de Firmware
        if (isFirmwareUpdateAvailable || (cachedFirmwareVersion != null && isConnected && cameraFirmwareVersion != cachedFirmwareVersion)) {
            val fwVersionTarget = latestRelease?.tagName ?: "v$cachedFirmwareVersion"
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                border = BorderStroke(1.dp, Color(0xFF43A047)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("⚡ Nuevo Firmware para la Dashcam ($fwVersionTarget)", fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                    Text(
                        text = if (cachedFirmwareVersion != null) "El binario ya está descargado en tu teléfono. Puedes flashearlo por Wi-Fi de inmediato."
                               else "Se descargará de GitHub y se instalará por Wi-Fi en la Dashcam.",
                        fontSize = 12.sp,
                        color = Color.DarkGray,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (cachedFirmwareVersion != null) {
                                onInstallCachedFirmware()
                            } else {
                                onUpdateFirmwareFromGitHub()
                            }
                        },
                        enabled = isConnected && !isUpdatingFirmware,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🚀 Instalar Firmware en Dashcam (OTA)", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Card general de flasheo OTA / opciones manuales
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (isUpdatingFirmware) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { otaProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = otaStatus,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "⚠️ NO apagues la cámara ni desconectes la alimentación durante el proceso.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text("Opciones Manuales de Firmware", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Puedes flashear un archivo .bin guardado en tu móvil o descargar directamente de GitHub.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val filePickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent()
                    ) { uri: Uri? ->
                        if (uri != null) {
                            pendingFirmwareUri = uri
                            showConfirmDialog = true
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { filePickerLauncher.launch("*/*") },
                            enabled = isConnected,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📁 Archivo .bin", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { showGitHubConfirmDialog = true },
                            enabled = isConnected,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                        ) {
                            Text("🌐 Desde GitHub", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (showConfirmDialog && pendingFirmwareUri != null) {
            AlertDialog(
                onDismissRequest = {
                    showConfirmDialog = false
                    pendingFirmwareUri = null
                },
                title = { Text("🚀 Actualizar Firmware OTA") },
                text = {
                    Text("¿Deseas flashear el archivo binario seleccionado en la Dashcam?\n\nLa cámara recibirá el binario por Wi-Fi, lo instalará en la partición OTA y se reiniciará automáticamente.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val uri = pendingFirmwareUri
                            showConfirmDialog = false
                            pendingFirmwareUri = null
                            if (uri != null) onUpdateFirmwareUri(uri)
                        }
                    ) {
                        Text("Sí, Actualizar")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showConfirmDialog = false
                            pendingFirmwareUri = null
                        }
                    ) {
                        Text("Cancelar")
                    }
                }
            )
        }

        if (showGitHubConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showGitHubConfirmDialog = false },
                title = { Text("🌐 Actualizar desde GitHub") },
                text = {
                    Text("¿Deseas descargar la versión oficial más reciente directamente de GitHub e instalarla en la Dashcam?\n\nNota: Se requiere conexión a Internet (datos móviles si estás conectado a Dashcam-WiFi).")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showGitHubConfirmDialog = false
                            onUpdateFirmwareFromGitHub()
                        }
                    ) {
                        Text("Descargar e Instalar")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showGitHubConfirmDialog = false }) {
                        Text("Cancelar")
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

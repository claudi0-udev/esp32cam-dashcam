package com.example.esp32dashcam.ui.main

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.esp32dashcam.ui.config.ConfigTabContent
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenPlayer: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    val snackbarHostState = remember { SnackbarHostState() }

    // Dialog state for renaming remote file
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var newFileNameInput by remember { mutableStateOf("") }

    // Dialog state for deleting remote file confirmation
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.errorMessage, uiState.successMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    // Modal para renombrar video en MicroSD
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Renombrar en MicroSD", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column {
                    Text("Introduce el nuevo nombre para este clip:", fontSize = 13.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newFileNameInput,
                        onValueChange = { newFileNameInput = it },
                        label = { Text("Nombre del archivo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Nota: Se mantendrá la extensión .avi", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                }
            },
            confirmButton = {
                Button(onClick = {
                    val old = renameTarget
                    if (old != null && newFileNameInput.isNotBlank()) {
                        viewModel.renameRemoteVideo(old, newFileNameInput.trim())
                    }
                    renameTarget = null
                }) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Modal para confirmar borrado de video en MicroSD
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("¿Borrar de la MicroSD?", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Text("¿Estás seguro de que deseas eliminar permanentemente '${deleteTarget}' de la tarjeta de memoria?", fontSize = 14.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        val fileToDelete = deleteTarget
                        if (fileToDelete != null) {
                            viewModel.deleteRemoteVideo(fileToDelete)
                        }
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Borrar")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("🚗 ESP32 Dashcam", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (uiState.isConnected) Color(0xFF4CAF50) else Color(0xFFE53935),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.isConnected) "Conectado a Dashcam-WiFi" else "Desconectado",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.connectDirectToDashcam() },
                        enabled = !uiState.isConnectingWifi
                    ) {
                        Text("📶", fontSize = 18.sp)
                    }
                    IconButton(
                        onClick = { viewModel.checkConnectionAndRefresh() },
                        enabled = !uiState.isRefreshing && !uiState.isDownloading
                    ) {
                        Text("🔄", fontSize = 18.sp)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Connecting or Downloading indicator bar
            if (uiState.isDownloading || uiState.isConnectingWifi) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = uiState.downloadStatus,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (uiState.isConnectingWifi) 0.5f else uiState.downloadProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Tabs: 1. Videos Cámara, 2. Videos Guardados, 3. Configuración
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Cámara (${uiState.remoteVideos.size})", fontSize = 13.sp) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        viewModel.loadLocalVideos()
                    },
                    text = { Text("Guardados (${uiState.localVideos.size})", fontSize = 13.sp) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                        if (uiState.isConnected) viewModel.fetchCameraConfig()
                    },
                    text = { Text("⚙️ Ajustes", fontSize = 13.sp) }
                )
            }

            when (selectedTab) {
                0 -> RemoteTabContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    onConnectDirect = { viewModel.connectDirectToDashcam() },
                    onOpenWifiPanel = { viewModel.openWifiPanel() },
                    onRequestRename = { oldName ->
                        renameTarget = oldName
                        newFileNameInput = oldName.removeSuffix(".avi")
                    },
                    onRequestDelete = { name ->
                        deleteTarget = name
                    }
                )
                1 -> LocalTabContent(
                    uiState = uiState,
                    viewModel = viewModel,
                    onOpenPlayer = onOpenPlayer,
                    onShareVideo = { file ->
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            file
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "video/avi"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Compartir video"))
                    }
                )
                2 -> ConfigTabContent(
                    config = uiState.config,
                    isConnected = uiState.isConnected,
                    isSaving = uiState.isSavingConfig,
                    isUpdatingFirmware = uiState.isUpdatingFirmware,
                    otaProgress = uiState.otaProgress,
                    otaStatus = uiState.otaStatus,
                    onSaveConfig = { newConfig -> viewModel.saveCameraConfig(newConfig) },
                    onRefreshConfig = { viewModel.fetchCameraConfig() },
                    onUpdateFirmwareUri = { uri -> viewModel.updateFirmwareFromUri(uri) },
                    onUpdateFirmwareFromGitHub = { viewModel.downloadAndInstallFirmwareFromGitHub() }
                )
            }
        }
    }
}

@Composable
private fun RemoteTabContent(
    uiState: MainUiState,
    viewModel: MainScreenViewModel,
    onConnectDirect: () -> Unit,
    onOpenWifiPanel: () -> Unit,
    onRequestRename: (String) -> Unit,
    onRequestDelete: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar with Select All and Action Buttons
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { viewModel.toggleSelectAllRemote() }
                ) {
                    Checkbox(
                        checked = uiState.remoteVideos.isNotEmpty() &&
                                uiState.selectedRemoteVideos.size == uiState.remoteVideos.size,
                        onCheckedChange = { viewModel.toggleSelectAllRemote() }
                    )
                    Text("Todos", fontSize = 14.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val count = uiState.selectedRemoteVideos.size
                    Button(
                        onClick = { viewModel.downloadSelectedRemote(andMerge = true) },
                        enabled = count > 0 && !uiState.isDownloading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32)
                        )
                    ) {
                        Text("🎬 Unir ($count)", fontSize = 13.sp)
                    }

                    FilledTonalButton(
                        onClick = { viewModel.downloadSelectedRemote(andMerge = false) },
                        enabled = count > 0 && !uiState.isDownloading
                    ) {
                        Text("⬇️ Bajar ($count)", fontSize = 13.sp)
                    }
                }
            }
        }

        if (!uiState.isConnected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("⚠️ No conectado a la Dashcam", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "1. Mantén pulsado el botón al alimentar el ESP32 para iniciar en modo Wi-Fi.\n" +
                        "2. Pulsa el botón de abajo para conectarte directamente sin salir de la app ni solicitar permisos de ubicación.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onConnectDirect,
                        enabled = !uiState.isConnectingWifi,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("🚗 Conectar a Dashcam-WiFi Directo", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenWifiPanel,
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Text("📶 Abrir Panel Rápido de Wi-Fi")
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(onClick = { viewModel.checkConnectionAndRefresh() }) {
                        Text("🔄 Ya estoy conectado, reintentar")
                    }
                }
            }
        } else if (uiState.remoteVideos.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No se encontraron grabaciones en la tarjeta MicroSD.", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.remoteVideos, key = { it.name }) { video ->
                    val isSelected = uiState.selectedRemoteVideos.contains(video.name)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .clickable { viewModel.toggleSelectRemoteVideo(video.name) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { viewModel.toggleSelectRemoteVideo(video.name) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "📹 ${video.name}",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (video.sizeFormatted.isNotEmpty()) {
                                    Text(
                                        text = video.sizeFormatted,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row {
                                IconButton(onClick = { onRequestRename(video.name) }) {
                                    Text("✏️", fontSize = 16.sp)
                                }
                                IconButton(onClick = { onRequestDelete(video.name) }) {
                                    Text("🗑️", fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalTabContent(
    uiState: MainUiState,
    viewModel: MainScreenViewModel,
    onOpenPlayer: (String) -> Unit,
    onShareVideo: (File) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar for local merging
        if (uiState.localVideos.isNotEmpty()) {
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { viewModel.toggleSelectAllLocal() }
                    ) {
                        Checkbox(
                            checked = uiState.localVideos.isNotEmpty() &&
                                    uiState.selectedLocalVideos.size == uiState.localVideos.size,
                            onCheckedChange = { viewModel.toggleSelectAllLocal() }
                        )
                        Text("Todos", fontSize = 14.sp)
                    }

                    val count = uiState.selectedLocalVideos.size
                    Button(
                        onClick = { viewModel.mergeSelectedLocal() },
                        enabled = count >= 2 && !uiState.isDownloading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32)
                        )
                    ) {
                        Text("🎬 Unir Seleccionados ($count)", fontSize = 13.sp)
                    }
                }
            }
        }

        if (uiState.localVideos.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No hay videos descargados.", color = Color.Gray, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Descarga clips desde la pestaña 'Cámara Wi-Fi'.",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.localVideos, key = { it.file.absolutePath }) { item ->
                    val isSelected = uiState.selectedLocalVideos.contains(item.file.absolutePath)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { viewModel.toggleSelectLocalVideo(item.file.absolutePath) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onOpenPlayer(item.file.absolutePath) }
                            ) {
                                Text(
                                    text = if (item.name.startsWith("viaje_unido")) "🎬 ${item.name}" else "📹 ${item.name}",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.sizeFormatted,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row {
                                IconButton(onClick = { onOpenPlayer(item.file.absolutePath) }) {
                                    Text("▶️", fontSize = 16.sp)
                                }
                                IconButton(onClick = { onShareVideo(item.file) }) {
                                    Text("📤", fontSize = 16.sp)
                                }
                                IconButton(onClick = { viewModel.deleteLocalVideo(item.file) }) {
                                    Text("🗑️", fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

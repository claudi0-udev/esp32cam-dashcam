package com.example.esp32dashcam.ui.player

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.esp32dashcam.utils.AviPlayerHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    filePath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val file = remember(filePath) { File(filePath) }
    val playerHelper = remember(file) { AviPlayerHelper(file) }

    var isReady by remember { mutableStateOf(false) }
    var currentFrameIndex by remember { mutableIntStateOf(0) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var totalFrames by remember { mutableIntStateOf(0) }
    var fps by remember { mutableIntStateOf(3) }

    // Clean up resources on dispose
    DisposableEffect(playerHelper) {
        onDispose {
            playerHelper.close()
        }
    }

    // Index file on launch
    LaunchedEffect(file) {
        val success = playerHelper.indexFile()
        if (success) {
            totalFrames = playerHelper.totalFrames
            fps = playerHelper.fps
            isReady = true
        }
    }

    // Single reactive driver: whenever currentFrameIndex changes, update the image immediately
    LaunchedEffect(currentFrameIndex, isReady) {
        if (isReady && totalFrames > 0) {
            currentBitmap = playerHelper.getFrameBitmap(currentFrameIndex)
        }
    }

    // Autonomous playback clock loop
    LaunchedEffect(isPlaying, isReady, totalFrames, fps) {
        if (isPlaying && isReady && totalFrames > 0) {
            val frameDelay = if (fps > 0) (1000L / fps) else 333L
            while (isActive && isPlaying) {
                delay(frameDelay)
                if (currentFrameIndex + 1 >= totalFrames) {
                    isPlaying = false
                    break
                } else {
                    currentFrameIndex++
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1, fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 22.sp)
                    }
                },
                actions = {
                    // Option to open in external video player (VLC, MX Player, Google Fotos, etc.)
                    IconButton(onClick = {
                        try {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.provider",
                                file
                            )
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "video/avi")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Abrir con reproductor externo"))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }) {
                        Text("📺", fontSize = 18.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Video display area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (!isReady) {
                    CircularProgressIndicator(color = Color.White)
                } else if (currentBitmap != null) {
                    Image(
                        bitmap = currentBitmap!!.asImageBitmap(),
                        contentDescription = "Video frame",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text("Fotograma no disponible", color = Color.White)
                }
            }

            // Controls panel
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Frame / Time progress info
                    val currentTimeSec = if (fps > 0) currentFrameIndex / fps else 0
                    val totalTimeSec = if (fps > 0) totalFrames / fps else 0
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = String.format("%02d:%02d", currentTimeSec / 60, currentTimeSec % 60),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Frame: ${currentFrameIndex + 1} / $totalFrames (${fps} fps)",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = String.format("%02d:%02d", totalTimeSec / 60, totalTimeSec % 60),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Seek Slider: Live updates frame as you drag
                    if (totalFrames > 1) {
                        Slider(
                            value = currentFrameIndex.toFloat(),
                            onValueChange = { newFrame ->
                                isPlaying = false
                                currentFrameIndex = newFrame.toInt().coerceIn(0, totalFrames - 1)
                            },
                            valueRange = 0f..(totalFrames - 1).toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Play / Pause / Step Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Frame back
                        FilledTonalButton(
                            onClick = {
                                isPlaying = false
                                if (currentFrameIndex > 0) {
                                    currentFrameIndex--
                                }
                            },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Text("⏮ -1")
                        }

                        // Play / Pause button
                        Button(
                            onClick = {
                                if (currentFrameIndex >= totalFrames - 1) {
                                    currentFrameIndex = 0
                                }
                                isPlaying = !isPlaying
                            },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Text(if (isPlaying) "⏸ Pausa" else "▶ Reproducir")
                        }

                        // Frame forward
                        FilledTonalButton(
                            onClick = {
                                isPlaying = false
                                if (currentFrameIndex < totalFrames - 1) {
                                    currentFrameIndex++
                                }
                            },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Text("+1 ⏭")
                        }
                    }
                }
            }
        }
    }
}

package com.example.esp32dashcam

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object Main : NavKey

@Serializable
data class Player(val filePath: String) : NavKey

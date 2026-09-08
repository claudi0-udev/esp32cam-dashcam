package com.example.esp32dashcam.network

data class DashcamConfig(
    val resolution: String = "VGA",
    val fps: Int = 3,
    val clipDuration: Int = 60,
    val quality: Int = 12,
    val vflip: Boolean = false,
    val hmirror: Boolean = false,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val wbMode: Int = 0
)

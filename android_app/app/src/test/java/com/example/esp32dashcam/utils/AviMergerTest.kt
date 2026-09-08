package com.example.esp32dashcam.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class AviMergerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createDummyAvi(file: File, frameCount: Int, width: Int = 640, height: Int = 480, fps: Int = 3) {
        val dummyPayload = ByteArray(1024) { 0xAA.toByte() }
        val header = AviMerger.buildAviHeader(
            width = width,
            height = height,
            totalFrames = frameCount,
            fps = fps,
            moviSize = dummyPayload.size.toLong()
        )
        FileOutputStream(file).use { fos ->
            fos.write(header)
            fos.write(dummyPayload)
        }
    }

    @Test
    fun testReadAviInfo() {
        val file = tempFolder.newFile("test_clip.avi")
        createDummyAvi(file, frameCount = 180, width = 640, height = 480, fps = 3)

        val info = AviMerger.readAviInfo(file)
        assertEquals(640, info.width)
        assertEquals(480, info.height)
        assertEquals(3, info.fps)
        assertEquals(180, info.totalFrames)
    }

    @Test
    fun testMergeAviFiles() {
        val clip1 = tempFolder.newFile("clip1.avi")
        val clip2 = tempFolder.newFile("clip2.avi")
        val merged = tempFolder.newFile("merged.avi")

        createDummyAvi(clip1, frameCount = 100)
        createDummyAvi(clip2, frameCount = 200)

        val success = AviMerger.mergeAviFiles(listOf(clip1, clip2), merged)
        assertTrue(success)

        val mergedInfo = AviMerger.readAviInfo(merged)
        assertEquals(300, mergedInfo.totalFrames)
        assertEquals(640, mergedInfo.width)
        assertEquals(480, mergedInfo.height)
        assertEquals(3, mergedInfo.fps)

        // Expected size: 224 (header) + 1024 (clip1 payload) + 1024 (clip2 payload) = 2272 bytes
        assertEquals(224 + 1024 + 1024, merged.length())
    }
}

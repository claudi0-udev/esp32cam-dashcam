package com.example.esp32dashcam.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * High-performance, memory-efficient MJPEG AVI player engine for Android.
 * Keeps an open file handle, uses an LRU bitmap cache for smooth scrubbing,
 * and accurately indexes each frame.
 */
class AviPlayerHelper(private val file: File) {

    data class FrameIndex(
        val offset: Long,
        val size: Int
    )

    private val frameIndices = ArrayList<FrameIndex>()
    private val mutex = Mutex()
    private var randomAccessFile: RandomAccessFile? = null

    // Cache decoded bitmaps (e.g. up to 60 frames in memory) for ultra-responsive scrubbing
    private val bitmapCache = object : LruCache<Int, Bitmap>(60) {}

    var fps: Int = 3
        private set
    var width: Int = 640
        private set
    var height: Int = 480
        private set

    val totalFrames: Int
        get() = frameIndices.size

    val durationSeconds: Float
        get() = if (fps > 0) totalFrames.toFloat() / fps else 0f

    /**
     * Parses the AVI file to index all '00dc' (video frame) chunks.
     */
    suspend fun indexFile(): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() < 224) return@withContext false

        mutex.withLock {
            try {
                randomAccessFile?.close()
                val raf = RandomAccessFile(file, "r")
                randomAccessFile = raf

                // Read header basic specs
                raf.seek(64)
                width = raf.readIntLittleEndian()
                height = raf.readIntLittleEndian()

                raf.seek(132)
                val readFps = raf.readIntLittleEndian()
                if (readFps > 0) fps = readFps

                // Fast scan starting at offset 224 (beginning of 'movi')
                frameIndices.clear()
                bitmapCache.evictAll()

                val fileLength = raf.length()
                var currentPos = 224L

                // Buffer to read chunk headers (4 bytes fourCC + 4 bytes size = 8 bytes)
                val chunkHeader = ByteArray(8)

                while (currentPos + 8 <= fileLength) {
                    raf.seek(currentPos)
                    val read = raf.read(chunkHeader)
                    if (read < 8) break

                    val fourCC = String(chunkHeader, 0, 4)
                    val chunkSize = (chunkHeader[4].toInt() and 0xFF) or
                            ((chunkHeader[5].toInt() and 0xFF) shl 8) or
                            ((chunkHeader[6].toInt() and 0xFF) shl 16) or
                            ((chunkHeader[7].toInt() and 0xFF) shl 24)

                    if (chunkSize <= 0 || currentPos + 8 + chunkSize > fileLength + 2) {
                        break
                    }

                    if (fourCC == "00dc" || fourCC == "00db") {
                        frameIndices.add(FrameIndex(offset = currentPos + 8, size = chunkSize))
                    }

                    // Chunks in AVI are word-aligned (2-byte padding if size is odd)
                    val padding = if (chunkSize % 2 != 0) 1 else 0
                    currentPos += 8 + chunkSize + padding
                }

                return@withContext frameIndices.isNotEmpty()
            } catch (e: Exception) {
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    /**
     * Decodes the JPEG frame at the given frame index into an Android Bitmap with LRU caching.
     */
    suspend fun getFrameBitmap(frameIndex: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (frameIndex < 0 || frameIndex >= frameIndices.size) return@withContext null

        // Check memory cache first
        val cached = bitmapCache.get(frameIndex)
        if (cached != null && !cached.isRecycled) {
            return@withContext cached
        }

        val idx = frameIndices[frameIndex]

        mutex.withLock {
            try {
                var raf = randomAccessFile
                if (raf == null) {
                    raf = RandomAccessFile(file, "r")
                    randomAccessFile = raf
                }
                raf.seek(idx.offset)
                val jpegBytes = ByteArray(idx.size)
                raf.readFully(jpegBytes)

                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, idx.size)
                if (bitmap != null) {
                    bitmapCache.put(frameIndex, bitmap)
                }
                bitmap
            } catch (e: Exception) {
                null
            }
        }
    }

    fun close() {
        try {
            randomAccessFile?.close()
            randomAccessFile = null
            bitmapCache.evictAll()
        } catch (_: Exception) {}
    }

    private fun RandomAccessFile.readIntLittleEndian(): Int {
        val b1 = read()
        val b2 = read()
        val b3 = read()
        val b4 = read()
        return (b1 and 0xFF) or ((b2 and 0xFF) shl 8) or ((b3 and 0xFF) shl 16) or ((b4 and 0xFF) shl 24)
    }
}

package com.lukas.jarvis.maps

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Map tiles from openstreetmap.org, cached in memory and on disk.
 *
 * The disk half matters more than it looks: the same few tiles are redrawn every
 * time the map opens, and the OSM tile servers are donated infrastructure with a
 * usage policy that asks callers not to refetch what they already have.
 */
class TileCache(context: Context) {

    private val dir = File(context.applicationContext.cacheDir, "map-tiles")
        .apply { mkdirs() }

    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_TILES) {}

    // The same policy asks for no more than two connections at a time.
    private val gate = Semaphore(2)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun cached(z: Int, x: Int, y: Int): ImageBitmap? = memory.get(key(z, x, y))

    suspend fun tile(z: Int, x: Int, y: Int): ImageBitmap? {
        val key = key(z, x, y)
        memory.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val file = File(dir, "$key.png")
            decode(file)?.let { fromDisk ->
                memory.put(key, fromDisk)
                return@withContext fromDisk
            }

            val bytes = gate.withPermit {
                runCatching { download(z, x, y) }.getOrNull()
            } ?: return@withContext null

            runCatching { file.writeBytes(bytes) }
            prune()

            val bitmap = runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
            bitmap?.also { memory.put(key, it) }
        }
    }

    private fun download(z: Int, x: Int, y: Int): ByteArray? {
        val request = Request.Builder()
            .url("https://tile.openstreetmap.org/$z/$x/$y.png")
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.bytes()
        }
    }

    private fun decode(file: File): ImageBitmap? {
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }.getOrNull()
    }

    /** Keeps the cache to a few megabytes by dropping the least recently used files. */
    private fun prune() {
        val files = dir.listFiles() ?: return
        if (files.size <= DISK_TILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - DISK_TILES)
            .forEach { runCatching { it.delete() } }
    }

    private fun key(z: Int, x: Int, y: Int) = "${z}_${x}_$y"

    private companion object {
        const val MEMORY_TILES = 96
        const val DISK_TILES = 900
        const val USER_AGENT =
            "JarvisAssistant/1.0 (personal Android assistant; +https://github.com/lukas787-tech/projects)"
    }
}

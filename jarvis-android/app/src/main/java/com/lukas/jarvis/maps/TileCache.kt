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
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The looks a map can have, each a keyless tile source.
 *
 * Dark and light come from CARTO at double resolution: the old map drew
 * 256-pixel OpenStreetMap tiles one-to-one on a phone with three pixels to
 * every point, which made street names about a millimetre tall. These are
 * drawn at 256 points, so the text is the size it was designed to be read at.
 */
enum class MapStyle(
    val id: String,
    val title: String,
    /** Pixels per tile edge in the downloaded image. */
    val tilePixels: Int,
    val maxZoom: Int,
    val credit: String
) {
    Dark("dark", "Dark", 512, 20, "© OpenStreetMap © CARTO"),
    Light("light", "Light", 512, 20, "© OpenStreetMap © CARTO"),
    Satellite("satellite", "Satellite", 256, 19, "© Esri, Maxar, Earthstar");

    fun url(z: Int, x: Int, y: Int): String = when (this) {
        Dark -> "https://basemaps.cartocdn.com/dark_all/$z/$x/$y@2x.png"
        Light -> "https://basemaps.cartocdn.com/rastertiles/voyager/$z/$x/$y@2x.png"
        Satellite ->
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
    }

    companion object {
        fun of(id: String?): MapStyle =
            entries.firstOrNull { it.id == id?.lowercase(Locale.ROOT) } ?: Dark
    }
}

/**
 * Map tiles, cached in memory and on disk.
 *
 * The disk half matters more than it looks: the same few tiles are redrawn every
 * time the map opens, and every one of these servers asks callers not to refetch
 * what they already have.
 */
class TileCache(context: Context) {

    private val dir = File(context.applicationContext.cacheDir, "map-tiles")
        .apply { mkdirs() }

    /** Sized in kilobytes, since a retina tile weighs four times a plain one. */
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            (value.width * value.height * 4 / 1024).coerceAtLeast(1)
    }

    private val gate = Semaphore(4)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun cached(style: MapStyle, z: Int, x: Int, y: Int): ImageBitmap? = memory.get(key(style, z, x, y))

    suspend fun tile(style: MapStyle, z: Int, x: Int, y: Int): ImageBitmap? {
        val key = key(style, z, x, y)
        memory.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val file = File(dir, "$key.img")
            decode(file)?.let { fromDisk ->
                memory.put(key, fromDisk)
                return@withContext fromDisk
            }

            val bytes = gate.withPermit {
                runCatching { download(style.url(z, x, y)) }.getOrNull()
            } ?: return@withContext null

            runCatching { file.writeBytes(bytes) }
            prune()

            val bitmap = runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
            bitmap?.also { memory.put(key, it) }
        }
    }

    private fun download(url: String): ByteArray? {
        val request = Request.Builder()
            .url(url)
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

    /** Keeps the disk cache bounded by dropping the least recently used files. */
    private fun prune() {
        val files = dir.listFiles() ?: return
        if (files.size <= DISK_TILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - DISK_TILES)
            .forEach { runCatching { it.delete() } }
    }

    private fun key(style: MapStyle, z: Int, x: Int, y: Int) = "${style.id}_${z}_${x}_$y"

    private companion object {
        const val MEMORY_KB = 48 * 1024
        const val DISK_TILES = 1_500
        const val USER_AGENT =
            "JarvisAssistant/1.0 (personal Android assistant; +https://github.com/lukas787-tech/projects)"
    }
}

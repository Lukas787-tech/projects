package com.lukas.jarvis.maps

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * One server tiles can come from.
 *
 * Every one of these is keyless. [darken] marks a light source that stands in
 * for a dark style and is turned dark on the phone rather than on the server.
 */
enum class TileSource(
    val id: String,
    val maxZoom: Int,
    val credit: String,
    val darken: Boolean = false
) {
    CartoDark("carto-dark", 20, "© OpenStreetMap © CARTO"),
    CartoLight("carto-light", 20, "© OpenStreetMap © CARTO"),
    EsriImagery("esri", 19, "© Esri, Maxar, Earthstar"),
    Osm("osm", 19, "© OpenStreetMap contributors"),
    OsmDark("osm-dark", 19, "© OpenStreetMap contributors", darken = true),
    OsmGermany("osm-de", 18, "© OpenStreetMap contributors"),
    SentinelCloudless("eox", 15, "Sentinel-2 cloudless © EOX");

    fun url(z: Int, x: Int, y: Int): String = when (this) {
        CartoDark -> "https://basemaps.cartocdn.com/dark_all/$z/$x/$y@2x.png"
        CartoLight -> "https://basemaps.cartocdn.com/rastertiles/voyager/$z/$x/$y@2x.png"
        EsriImagery ->
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
        Osm, OsmDark -> "https://tile.openstreetmap.org/$z/$x/$y.png"
        OsmGermany -> "https://tile.openstreetmap.de/$z/$x/$y.png"
        SentinelCloudless ->
            "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/$z/$y/$x.jpg"
    }
}

/**
 * The looks a map can have, each backed by a short chain of keyless sources.
 *
 * The first source is the best-looking; the ones after it are there because a
 * tile server can decide, for reasons that are not visible from the phone, to
 * answer with a picture of the words "API key required" instead of a map. That
 * happened, and it could not be reproduced from anywhere else — the same URLs
 * served real tiles to a test machine. So the app no longer trusts any single
 * source to keep working: when one starts handing back a placeholder, the style
 * moves on to the next source in its chain by itself.
 */
enum class MapStyle(
    val id: String,
    val title: String,
    val chain: List<TileSource>
) {
    Dark("dark", "Dark", listOf(TileSource.CartoDark, TileSource.OsmDark)),
    Light("light", "Light", listOf(TileSource.CartoLight, TileSource.Osm, TileSource.OsmGermany)),
    Streets("streets", "OpenStreetMap", listOf(TileSource.Osm, TileSource.OsmGermany)),
    Satellite("satellite", "Satellite", listOf(TileSource.EsriImagery, TileSource.SentinelCloudless));

    companion object {
        fun of(id: String?): MapStyle =
            entries.firstOrNull { it.id == id?.lowercase(Locale.ROOT) } ?: Dark
    }
}

/**
 * Map tiles, cached in memory and on disk, and a guard against placeholders.
 *
 * The disk half matters more than it looks: the same few tiles are redrawn every
 * time the map opens, and every one of these servers asks callers not to refetch
 * what they already have.
 */
class TileCache(context: Context) {

    private val app = context.applicationContext

    // A new folder, so placeholders saved by earlier versions are not drawn
    // again from disk after the fix that stops new ones being saved.
    private val dir = File(app.cacheDir, "map-tiles-v2").apply { mkdirs() }

    private val prefs = app.getSharedPreferences("jarvis_tiles", Context.MODE_PRIVATE)

    init {
        File(app.cacheDir, "map-tiles").takeIf { it.exists() }?.deleteRecursively()
    }

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

    /**
     * Ticks whenever a source is given up on, so the map knows the tiles it has
     * on screen came from somewhere that no longer counts and fetches again.
     */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /** Which source a style is using right now: the first one not given up on. */
    fun source(style: MapStyle): TileSource {
        val chain = style.chain
        // The last source is never given up on: an empty map helps nobody.
        return chain.dropLast(1).firstOrNull { !isBlocked(it) } ?: chain.last()
    }

    fun cached(style: MapStyle, z: Int, x: Int, y: Int): ImageBitmap? =
        memory.get(key(source(style), z, x, y))

    suspend fun tile(style: MapStyle, z: Int, x: Int, y: Int): ImageBitmap? {
        val source = source(style)
        val key = key(source, z, x, y)
        memory.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            val file = File(dir, "$key.img")
            decode(file)?.let { fromDisk ->
                memory.put(key, fromDisk)
                return@withContext fromDisk
            }

            val answer = gate.withPermit {
                runCatching { download(source.url(z, x, y)) }.getOrNull()
            } ?: return@withContext null

            // A server that says outright it wants a key is believed at once.
            if (answer.code == 401 || answer.code == 403) {
                giveUp(style, source)
                return@withContext null
            }
            val bytes = answer.bytes ?: return@withContext null

            if (looksLikePlaceholder(source, bytes, z, x, y)) {
                giveUp(style, source)
                return@withContext null
            }

            runCatching { file.writeBytes(bytes) }
            prune()

            val bitmap = runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
            bitmap?.also { memory.put(key, it) }
        }
    }

    // ------------------------------------------------------------ placeholders

    /** Byte hash → the distinct tiles that came back as exactly those bytes. */
    private val sightings = HashMap<String, HashMap<Long, MutableSet<String>>>()

    /**
     * Whether [bytes] is the same picture this source keeps sending for places
     * that should look different.
     *
     * A real map differs from tile to tile; a notice about a missing key is one
     * image served for every coordinate. Only images of some size are counted,
     * because an empty stretch of sea or field is also the same tile everywhere
     * — but a plain colour compresses to a few hundred bytes, and a picture with
     * words in it does not. Getting this wrong is cheap in any case: the style
     * moves to its next source, which also draws a map.
     */
    private fun looksLikePlaceholder(source: TileSource, bytes: ByteArray, z: Int, x: Int, y: Int): Boolean {
        if (bytes.size < PLAIN_TILE_BYTES) return false
        val hash = CRC32().apply { update(bytes) }.value
        val seen = synchronized(sightings) {
            sightings.getOrPut(source.id) { HashMap() }
                .getOrPut(hash) { HashSet() }
                .apply { add("$z/$x/$y") }
                .size
        }
        return seen >= PLACEHOLDER_REPEATS
    }

    private fun isBlocked(source: TileSource): Boolean {
        val since = prefs.getLong(source.id, 0L)
        if (since == 0L) return false
        // Tried again after a while: whatever made a server refuse may pass.
        if (System.currentTimeMillis() - since > RETRY_AFTER_MS) {
            prefs.edit().remove(source.id).apply()
            return false
        }
        return true
    }

    private fun giveUp(style: MapStyle, source: TileSource) {
        if (source == style.chain.last()) return
        if (isBlocked(source)) return
        prefs.edit().putLong(source.id, System.currentTimeMillis()).apply()
        // Nothing from it stays, on screen or on disk.
        memory.snapshot().keys.filter { it.startsWith("${source.id}_") }.forEach { memory.remove(it) }
        dir.listFiles()?.filter { it.name.startsWith("${source.id}_") }?.forEach { runCatching { it.delete() } }
        synchronized(sightings) { sightings.remove(source.id) }
        _generation.value += 1
    }

    // --------------------------------------------------------------- plumbing

    private class Answer(val code: Int, val bytes: ByteArray?)

    private fun download(url: String): Answer {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return Answer(response.code, null)
            // An error page is not a tile, whatever its status says.
            val type = response.header("Content-Type").orEmpty()
            if (type.isNotBlank() && !type.startsWith("image/")) return Answer(response.code, null)
            return Answer(response.code, response.body?.bytes())
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

    private fun key(source: TileSource, z: Int, x: Int, y: Int) = "${source.id}_${z}_${x}_$y"

    private companion object {
        const val MEMORY_KB = 48 * 1024
        const val DISK_TILES = 1_500

        /** Smaller than this is a single colour: sea, field, or empty land. */
        const val PLAIN_TILE_BYTES = 2_000

        /** Identical pictures at this many different places are not a map. */
        const val PLACEHOLDER_REPEATS = 4

        const val RETRY_AFTER_MS = 3L * 24 * 60 * 60 * 1000

        const val USER_AGENT =
            "JarvisAssistant/1.0 (personal Android assistant; +https://github.com/lukas787-tech/projects)"
    }
}

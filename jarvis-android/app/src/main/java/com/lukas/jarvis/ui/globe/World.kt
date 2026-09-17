package com.lukas.jarvis.ui.globe

import android.content.Context
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The world's coastlines, in about ten kilobytes.
 *
 * Natural Earth's 110m coastline (public domain) reduced with Douglas–Peucker
 * to roughly a third of a degree and stored as quantised 16-bit pairs: two and
 * a half thousand points for the whole planet. That is coarse for a chart and
 * exactly right for a globe the size of a palm, and it keeps the app free of a
 * map SDK and of anything that has to be downloaded at runtime.
 *
 * Longitude is stored times 180 and latitude times 360, which puts both at the
 * edge of a signed short and gives a resolution finer than the simplification.
 */
class World private constructor(
    /** One entry per coastline: interleaved lon, lat in degrees. */
    val strokes: List<FloatArray>
) {
    companion object {

        @Volatile
        private var cached: World? = null

        /** Parsed once per process; the file is small enough to keep resident. */
        fun load(context: Context): World {
            cached?.let { return it }
            return synchronized(this) {
                cached ?: read(context).also { cached = it }
            }
        }

        private fun read(context: Context): World {
            val bytes = runCatching {
                context.applicationContext.assets.open(ASSET).use { input ->
                    DataInputStream(input).readBytes()
                }
            }.getOrNull() ?: return World(emptyList())

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val strokes = ArrayList<FloatArray>()
            runCatching {
                val lineCount = buffer.short.toInt() and 0xFFFF
                repeat(lineCount) {
                    val pointCount = buffer.short.toInt() and 0xFFFF
                    val points = FloatArray(pointCount * 2)
                    for (index in 0 until pointCount) {
                        points[index * 2] = buffer.short / 180f
                        points[index * 2 + 1] = buffer.short / 360f
                    }
                    strokes += points
                }
            }
            return World(strokes)
        }

        private const val ASSET = "coastline.bin"
    }
}

package com.lukas.jarvis.web

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Pictures from a sentence, through Pollinations' public image endpoint: no
 * key, no account. The picture is written to the app's own storage so the
 * conversation can show it again after a restart, and so it can be shared or
 * saved to the gallery from there.
 */
class Imagine(context: Context) {

    private val folder = File(context.applicationContext.filesDir, "generated").apply { mkdirs() }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Drawing takes a while on a busy day; the connection just sits there.
        .readTimeout(100, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** The saved picture, or an exception saying why there is none. */
    suspend fun generate(prompt: String, shape: String): File = withContext(Dispatchers.IO) {
        val (width, height) = when (shape) {
            "portrait", "wallpaper" -> 768 to 1344
            "landscape", "wide" -> 1344 to 768
            else -> 1024 to 1024
        }
        val encoded = URLEncoder.encode(prompt.trim().take(800), "UTF-8").replace("+", "%20")
        val seed = Random.nextInt(1, 1_000_000)
        val url = "https://image.pollinations.ai/prompt/$encoded" +
            "?width=$width&height=$height&nologo=true&private=true&seed=$seed"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Jarvis/5.0")
            .build()
        val file = File(folder, "img_${System.currentTimeMillis()}.jpg")
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("the image service answered ${response.code}")
            val body = response.body ?: error("the image service sent nothing")
            file.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        // Anything that is not a decodable picture is an error page in disguise.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            file.delete()
            error("the image service sent something that is not a picture")
        }
        prune()
        file
    }

    /** Keeps the folder from growing without end: the newest hundred stay. */
    private fun prune() {
        val files = folder.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP).forEach { it.delete() }
    }

    private companion object {
        const val KEEP = 100
    }
}

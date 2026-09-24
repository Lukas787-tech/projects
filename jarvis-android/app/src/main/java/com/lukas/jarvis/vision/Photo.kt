package com.lukas.jarvis.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A picture ready to be shown to a model: small enough to send over a phone
 * connection, large enough that the writing on a receipt is still writing.
 */
class Photo(
    /** `data:image/jpeg;base64,…`, the form every vision endpoint takes. */
    val dataUrl: String,
    /** The same picture, for the thread to show as a thumbnail. */
    val preview: Bitmap,
    /** The upright picture at sending size, for the phone's own text and object reading. */
    val full: Bitmap = preview
)

object Photos {

    /**
     * Where the camera app writes a full-size shot. A preview-sized bitmap is
     * what the no-file camera intent returns, and at that size a price tag or a
     * street sign is a smudge — so the camera gets a real file to fill.
     */
    fun newCaptureUri(context: Context): Uri {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        // Only the newest few are worth keeping; each is a few megabytes.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** Reads, turns upright and shrinks a picture. Null when it cannot be read. */
    fun load(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Photo? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val rotation = resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        from(decoded, rotation, maxSide)
    }.getOrNull()

    fun from(bitmap: Bitmap, rotation: Float = 0f, maxSide: Int = MAX_SIDE): Photo {
        val longest = max(bitmap.width, bitmap.height)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }
        val upright = if (scale < 1f || rotation != 0f) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
        val bytes = ByteArrayOutputStream().use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val thumbSide = 360f / max(upright.width, upright.height)
        val preview = if (thumbSide < 1f) {
            Bitmap.createScaledBitmap(
                upright,
                (upright.width * thumbSide).roundToInt().coerceAtLeast(1),
                (upright.height * thumbSide).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            upright
        }
        return Photo("data:image/jpeg;base64,$encoded", preview, upright)
    }

    /** About 1.5 megapixels: text stays legible and the request stays well under 1 MB. */
    private const val MAX_SIDE = 1400
    private const val QUALITY = 82
}

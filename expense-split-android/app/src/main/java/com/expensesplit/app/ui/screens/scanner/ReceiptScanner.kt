package com.expensesplit.app.ui.screens.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera capture plus on-device OCR.
 *
 * ML Kit's Latin text recognizer runs entirely on the device — no receipt image or its contents
 * ever leaves the phone, which matters for a document that lists everything someone bought.
 */
object ReceiptScanner {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val captureExecutor by lazy { Executors.newSingleThreadExecutor() }

    /** Where captured receipt photos live: app-private, so no storage permission is involved. */
    fun receiptImageFile(context: Context): File {
        val directory = File(context.filesDir, "receipts").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        return File(directory, "receipt-$stamp.jpg")
    }

    /** Takes a photo and returns the file it was written to. */
    suspend fun capture(imageCapture: ImageCapture, target: File): File =
        suspendCancellableCoroutine { continuation ->
            val options = ImageCapture.OutputFileOptions.Builder(target).build()
            imageCapture.takePicture(
                options,
                captureExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (continuation.isActive) continuation.resume(target)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (continuation.isActive) continuation.resumeWithException(exception)
                    }
                },
            )
        }

    /** Runs OCR over an image file and returns the recognized text, newline-separated by line. */
    suspend fun recognizeText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val image = InputImage.fromFilePath(context, uri)
        runRecognition(image)
    }

    suspend fun recognizeText(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        runRecognition(InputImage.fromBitmap(bitmap, 0))
    }

    private suspend fun runRecognition(image: InputImage): String =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    // Rebuilding from blocks and lines preserves the vertical order the parser
                    // relies on; result.text alone can interleave columns on wide receipts.
                    val text = result.textBlocks
                        .sortedBy { it.boundingBox?.top ?: 0 }
                        .flatMap { block ->
                            block.lines.sortedBy { it.boundingBox?.top ?: 0 }.map { it.text }
                        }
                        .joinToString("\n")
                    if (continuation.isActive) continuation.resume(text)
                }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }

    /**
     * Downscales a captured photo before it is stored. Receipts are text on white; full-resolution
     * JPEGs cost megabytes each and add nothing the OCR pass can use.
     */
    fun compressForStorage(source: File, maxDimension: Int = 1600, quality: Int = 82): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)

        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        if (largest <= 0) return source

        var sampleSize = 1
        while (largest / sampleSize > maxDimension) sampleSize *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, options) ?: return source

        return try {
            source.outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            }
            source
        } finally {
            bitmap.recycle()
        }
    }
}

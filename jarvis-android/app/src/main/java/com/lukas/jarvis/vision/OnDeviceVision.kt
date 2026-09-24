package com.lukas.jarvis.vision

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** What the phone itself could make out in a picture, with no network at all. */
data class Reading(
    val text: String,
    val labels: List<String>,
    val codes: List<String>
) {
    val isEmpty: Boolean get() = text.isBlank() && labels.isEmpty() && codes.isEmpty()

    /** Words for a model that cannot see the picture. */
    fun describe(): String = buildString {
        if (codes.isNotEmpty()) {
            appendLine("QR / barcodes in the picture: ${codes.joinToString("; ")}")
        }
        if (text.isNotBlank()) {
            appendLine("Text printed in the picture, read exactly by the phone:")
            appendLine(text.take(2500))
        }
        if (labels.isNotEmpty()) {
            appendLine("What the phone recognised in it: ${labels.joinToString(", ")}")
        }
    }.trim()
}

/**
 * Eyes that need no key: Google's on-device models for reading text, naming
 * what is in a picture and decoding QR codes and barcodes. They run on the
 * phone, offline, for free.
 *
 * They are used twice. With no vision model available they are the only eyes
 * there are, and "what is this" still gets an answer. With one, the text they
 * read is added beside the model's description anyway, because the phone
 * reads a receipt's digits exactly where a model sometimes invents them.
 */
class OnDeviceVision {

    suspend fun read(bitmap: Bitmap): Reading {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = runCatching {
            withTimeoutOrNull(TIMEOUT_MS) {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(image).await()?.text
            }
        }.getOrNull().orEmpty().trim()

        val labels = runCatching {
            withTimeoutOrNull(TIMEOUT_MS) {
                ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
                    .process(image).await()
            }
        }.getOrNull().orEmpty()
            .filter { it.confidence >= 0.62f }
            .sortedByDescending { it.confidence }
            .take(8)
            .map { "${it.text.lowercase(Locale.ROOT)} (${(it.confidence * 100).toInt()}%)" }

        val codes = runCatching {
            withTimeoutOrNull(TIMEOUT_MS) {
                BarcodeScanning.getClient().process(image).await()
            }
        }.getOrNull().orEmpty()
            .mapNotNull { describe(it) }
            .distinct()

        return Reading(text, labels, codes)
    }

    private fun describe(code: Barcode): String? {
        val raw = code.rawValue?.takeIf { it.isNotBlank() } ?: return null
        return when (code.valueType) {
            Barcode.TYPE_URL -> "link ${code.url?.url ?: raw}"
            Barcode.TYPE_WIFI -> code.wifi?.let { "Wi-Fi network \"${it.ssid}\" with password \"${it.password}\"" } ?: raw
            Barcode.TYPE_PHONE -> "phone number ${code.phone?.number ?: raw}"
            Barcode.TYPE_EMAIL -> "email address ${code.email?.address ?: raw}"
            Barcode.TYPE_CONTACT_INFO -> "contact card: ${raw.take(200)}"
            Barcode.TYPE_PRODUCT, Barcode.TYPE_ISBN -> "product code $raw"
            else -> raw.take(300)
        }
    }

    private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
        addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
        addOnCanceledListener { if (continuation.isActive) continuation.resume(null) }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000L
    }
}

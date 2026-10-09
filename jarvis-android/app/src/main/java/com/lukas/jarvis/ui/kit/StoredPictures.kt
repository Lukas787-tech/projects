package com.lukas.jarvis.ui.kit

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.lukas.jarvis.ui.theme.Cafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Gives everything under it real pictures: decoded from the phone, opened full screen on a tap. */
@Composable
fun ProvidePictures(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPicture provides PictureFrame { path, description, modifier -> StoredPicture(path, description, modifier) }, content = content)
}

/** A picture from the app's own storage, decoded off the main thread at a size that suits a card. */
@Composable
fun StoredPicture(path: String, description: String, modifier: Modifier = Modifier, maxSide: Int = 1080, opens: Boolean = true) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var missing by remember(path) { mutableStateOf(false) }
    var viewing by remember { mutableStateOf(false) }
    LaunchedEffect(path, maxSide) {
        bitmap = withContext(Dispatchers.IO) { decode(path, maxSide) }
        missing = bitmap == null
    }
    val current = bitmap
    when {
        current != null -> {
            val image = remember(current) { current.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = modifier
                    .clip(Cafe.shape.medium)
                    .then(if (opens) Modifier.clickable(role = Role.Image, onClickLabel = "Open the picture") { viewing = true } else Modifier)
            )
        }
        missing -> PicturePlaceholder("No longer on this phone", modifier)
        else -> PicturePlaceholder("Opening the picture…", modifier)
    }
    if (viewing) PictureViewer(path, description) { viewing = false }
}

/** The picture filling the screen, with saving and sharing at hand. */
@Composable
fun PictureViewer(path: String, description: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(com.lukas.jarvis.ui.theme.Palette.ROAST).copy(alpha = 0.96f))) {
            StoredPicture(path, description, Modifier.fillMaxWidth().align(Alignment.Center), maxSide = 2048, opens = false)
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = Cafe.space.xxl),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Cafe.space.s)
            ) {
                CafeButton(if (saved != null) "Saved" else "Save", { saved = saveToGallery(context, path) }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Download)
                CafeButton("Share", { sharePicture(context, path) }, kind = ButtonKind.Secondary, icon = Icons.Rounded.Share)
                CafeButton("Close", onClose, icon = Icons.Rounded.Close)
            }
            saved?.let {
                Text(it, style = Cafe.type.bodySmall, color = Cafe.colors.paper, modifier = Modifier.align(Alignment.TopCenter).padding(top = Cafe.space.xxl))
            }
        }
    }
}

private fun decode(path: String, maxSide: Int): Bitmap? = runCatching {
    val file = File(path)
    if (!file.exists()) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

/** Hands a picture to whichever app the person picks. */
fun sharePicture(context: Context, path: String, text: String? = null) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND)
        val file = File(path).takeIf { it.exists() }
        if (file != null) {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            intent.type = "image/jpeg"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent.type = "text/plain"
        }
        if (!text.isNullOrBlank()) intent.putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Hands some words to whichever app the person picks. */
fun shareText(context: Context, text: String, title: String = "Share") {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.take(90_000))
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Copies a picture into Pictures/Mochi. Returns a line saying how it went. */
private fun saveToGallery(context: Context, path: String): String = runCatching {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Saving needs Android 10 or newer — Share works instead."
    val source = File(path)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, source.name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mochi")
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return "Couldn't save the picture."
    resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
    "Saved to Pictures/Mochi"
}.getOrElse { "Couldn't save: ${it.message}" }

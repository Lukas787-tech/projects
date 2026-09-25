package com.lukas.jarvis.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.lukas.jarvis.core.TimeUtil
import com.lukas.jarvis.data.ChatMessage
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.AccentBright
import com.lukas.jarvis.ui.theme.Film
import com.lukas.jarvis.ui.theme.GlassEdgeBright
import com.lukas.jarvis.ui.theme.GlassEdgeDim
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What can be done to a message from its long-press menu. */
class MessageActions(
    val onSpeak: ((ChatMessage) -> Unit)? = null,
    val onDelete: ((ChatMessage) -> Unit)? = null,
    val onRetry: ((ChatMessage) -> Unit)? = null,
    /** Keeps a line as a memory, for an answer worth having again. */
    val onRemember: ((ChatMessage) -> Unit)? = null
)

/**
 * One line of the conversation.
 *
 * Yours sits on the right in a lit pane of the accent; the assistant's on the
 * left as glass, with its reply drawn as Markdown, any picture it drew, and
 * the chips saying which tools answered it. A long press opens the actions:
 * copy, read aloud, share, remember, try again, delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    photo: Bitmap? = null,
    actions: MessageActions = MessageActions(),
    isLast: Boolean = false
) {
    val fromUser = message.role == ChatMessage.ROLE_USER
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }

    val mine = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
    val theirs = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (message.image != null) 320.dp else 310.dp)
                .clip(if (fromUser) mine else theirs)
                .background(
                    if (fromUser) {
                        Brush.linearGradient(listOf(Accent.copy(alpha = 0.26f), Accent.copy(alpha = 0.14f)))
                    } else {
                        Brush.verticalGradient(listOf(Film.lifted, Film.faint))
                    }
                )
                .border(
                    1.dp,
                    if (fromUser) {
                        Brush.linearGradient(listOf(Accent.copy(alpha = 0.55f), Accent.copy(alpha = 0.15f)))
                    } else {
                        Brush.verticalGradient(listOf(GlassEdgeBright, GlassEdgeDim))
                    },
                    if (fromUser) mine else theirs
                )
                .combinedClickable(onClick = {}, onLongClick = { menu = true })
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (photo != null) {
                Image(
                    bitmap = photo.asImageBitmap(),
                    contentDescription = "Your photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(Space.tight))
            }

            message.image?.let { path ->
                StoredImage(
                    path = path,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { viewing = path }
                )
                Spacer(Modifier.height(Space.tight))
            }

            // A photo turn keeps what was seen under the question: the receipt
            // for the answer, so it is there, but quieter.
            val isPhoto = fromUser && message.content.startsWith("📷")
            val question = if (isPhoto) message.content.substringBefore("\n\n") else message.content
            val seen = if (isPhoto) message.content.substringAfter("\n\n", "") else ""

            if (fromUser) {
                Text(question, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            } else {
                val accent = Accent
                val code = Film.selected
                val rendered = remember(message.content, accent) {
                    Markdown.render(message.content, accent, code, TextSecondary)
                }
                Text(rendered, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            }
            if (seen.isNotBlank()) {
                Spacer(Modifier.height(Space.hair))
                Text(
                    seen,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!fromUser && message.tools.isNotEmpty()) {
                Spacer(Modifier.height(Space.tight))
                ToolTrail(tools = message.tools)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                TimeUtil.relative(message.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = TextFaint
            )
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuRow("Copy", Icons.Default.ContentCopy) {
                clipboard.setText(AnnotatedString(Markdown.plain(message.content)))
                menu = false
            }
            actions.onSpeak?.let { speak ->
                if (!fromUser) MenuRow("Read aloud", Icons.AutoMirrored.Filled.VolumeUp) { speak(message); menu = false }
            }
            MenuRow("Share", Icons.Default.Share) {
                share(context, Markdown.plain(message.content), message.image)
                menu = false
            }
            actions.onRemember?.let { remember ->
                MenuRow("Remember this", Icons.Default.Psychology) { remember(message); menu = false }
            }
            actions.onRetry?.let { retry ->
                if (isLast && !fromUser) MenuRow("Try again", Icons.Default.Refresh) { retry(message); menu = false }
            }
            actions.onDelete?.let { delete ->
                MenuRow("Delete", Icons.Default.Delete) { delete(message); menu = false }
            }
        }
    }

    viewing?.let { path -> ImageViewer(path = path, onClose = { viewing = null }) }
}

@Composable
private fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        onClick = onClick
    )
}

/**
 * A picture from the app's own storage, decoded off the main thread at a size
 * that suits a bubble rather than at its full few megapixels.
 */
@Composable
fun StoredImage(path: String, modifier: Modifier = Modifier, maxSide: Int = 1080) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var missing by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { decode(path, maxSide) }
        missing = bitmap == null
    }
    val current = bitmap
    when {
        current != null -> Image(
            bitmap = current.asImageBitmap(),
            contentDescription = "A picture Jarvis drew",
            contentScale = ContentScale.FillWidth,
            modifier = modifier
        )
        missing -> Box(
            modifier = modifier.aspectRatio(1f).background(Film.faint),
            contentAlignment = Alignment.Center
        ) {
            Text("Picture no longer on this phone", color = TextFaint, style = MaterialTheme.typography.bodySmall)
        }
        else -> Shimmer(modifier = modifier.aspectRatio(1f))
    }
}

/** A soft pulse of light where something is still loading. */
@Composable
fun Shimmer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val glow by transition.animateFloat(
        initialValue = 0.04f,
        targetValue = 0.14f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "glow"
    )
    Box(modifier = modifier.background(Accent.copy(alpha = glow)))
}

/** The picture filling the screen, with saving and sharing at hand. */
@Composable
fun ImageViewer(path: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(onClick = onClose)
        ) {
            StoredImage(
                path = path,
                maxSide = 2048,
                modifier = Modifier.fillMaxWidth().align(Alignment.Center)
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(Space.snug)
            ) {
                ViewerButton(Icons.Default.Download, if (saved != null) "Saved" else "Save") {
                    saved = saveToGallery(context, path)
                }
                ViewerButton(Icons.Default.Share, "Share") { share(context, null, path) }
                ViewerButton(Icons.Default.Close, "Close", onClose)
            }
            saved?.let {
                Text(
                    it,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp)
                )
            }
        }
    }
}

@Composable
private fun ViewerButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, AccentBright.copy(alpha = 0.3f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = TextPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The typing indicator: three dots rising in turn, for the moment between
 * sending and the first sign of work.
 */
@Composable
fun TypingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1100)),
        label = "phase"
    )
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val lit = (1f - kotlin.math.abs(phase - i - 0.5f)).coerceIn(0.25f, 1f)
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .alpha(lit)
                    .clip(CircleShape)
                    .background(Accent)
            )
        }
    }
}

// ------------------------------------------------------------------ helpers

private fun decode(path: String, maxSide: Int): Bitmap? = runCatching {
    val file = File(path)
    if (!file.exists()) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

private fun share(context: Context, text: String?, imagePath: String?) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND)
        val file = imagePath?.let(::File)?.takeIf { it.exists() }
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

/** Copies a picture into Pictures/Jarvis. Returns a line saying how it went. */
private fun saveToGallery(context: Context, path: String): String = runCatching {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Saving needs Android 10 or newer — use Share instead."
    val source = File(path)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, source.name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Jarvis")
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return "Could not save the picture."
    resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
    "Saved to Pictures/Jarvis"
}.getOrElse { "Could not save: ${it.message}" }

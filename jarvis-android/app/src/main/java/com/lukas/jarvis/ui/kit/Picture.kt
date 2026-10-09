package com.lukas.jarvis.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.lukas.jarvis.ui.theme.Cafe

/**
 * A picture kept on the phone — one Mochi drew, or a photo — shown by its file
 * path. The app provides the real thing, which decodes at a sensible size and
 * opens full screen with save and share; previews and tests get a quiet frame.
 */
fun interface PictureFrame {
    @Composable
    fun Show(path: String, description: String, modifier: Modifier)
}

val LocalPicture = staticCompositionLocalOf { PictureFrame { _, description, modifier -> PicturePlaceholder(description, modifier) } }

/** Where a picture goes while there is none to show. */
@Composable
fun PicturePlaceholder(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.aspectRatio(1f).clip(Cafe.shape.medium).background(Cafe.colors.latte),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = Cafe.type.bodySmall, color = Cafe.colors.cocoa)
    }
}

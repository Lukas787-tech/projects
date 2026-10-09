package com.lukas.jarvis.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Cafe

/**
 * A sheet of paper slid up from below. It always has a way out — the close
 * button, a swipe down, the back gesture — and it says what it is for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CafeSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        modifier = modifier,
        shape = Cafe.shape.sheet,
        containerColor = Cafe.colors.paper,
        contentColor = Cafe.colors.espresso,
        scrimColor = Cafe.colors.scrim,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = Cafe.space.s)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(Cafe.shape.pill)
                    .background(Cafe.colors.latteDeep)
            )
        }
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Cafe.space.gutter)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = Cafe.type.headline,
                    color = Cafe.colors.espresso,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                IconCircle(Icons.Rounded.Close, "Close", onDismiss)
            }
            VSpace(Cafe.space.m)
            content()
            VSpace(Cafe.space.xl)
        }
    }
}

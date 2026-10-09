package com.lukas.jarvis.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.lukas.jarvis.llm.EditableField
import com.lukas.jarvis.llm.Risk
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.Elevation

/**
 * Something waiting on the person's yes: "Text Anna: 'Running late'".
 *
 * Three ways out, always: do it, change it, or don't. Changing it edits the
 * very words that will be sent, in place; nothing is sent until the yes.
 */
@Composable
fun ConfirmCard(
    title: String,
    detail: String,
    verb: String,
    risk: Risk,
    fields: List<EditableField>,
    onConfirm: () -> Unit,
    onEdit: (key: String, value: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val why = when (risk) {
        Risk.Outward -> "Leaves your phone — waiting for your OK"
        Risk.Sensitive -> "Can't be undone — waiting for your OK"
        else -> "Waiting for your OK"
    }
    PaperCard(
        modifier
            .fillMaxWidth()
            .semantics {
                paneTitle = "Needs your OK: $title"
                liveRegion = LiveRegionMode.Polite
            },
        tone = Tone.Honey,
        elevation = Elevation.Lifted
    ) {
        Eyebrow(why, color = Cafe.colors.espresso)
        VSpace(Cafe.space.xs)
        Text(title, style = Cafe.type.title, color = Cafe.colors.espresso)
        if (!editing && detail.isNotBlank()) {
            VSpace(Cafe.space.s)
            Text("“$detail”", style = Cafe.type.voiceSmall, color = Cafe.colors.espresso)
        }
        if (editing && fields.isNotEmpty()) {
            VSpace(Cafe.space.s)
            Column {
                fields.forEach { field ->
                    CafeTextField(
                        value = field.value,
                        onValueChange = { onEdit(field.key, it) },
                        label = field.label,
                        singleLine = !field.multiline,
                        minLines = if (field.multiline) 3 else 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                    VSpace(Cafe.space.s)
                }
            }
        }
        VSpace(Cafe.space.m)
        Wrap {
            CafeButton(verb, onConfirm, icon = verbIcon(verb, risk))
            if (fields.isNotEmpty()) {
                CafeButton(
                    if (editing) "Done editing" else "Edit",
                    { editing = !editing },
                    kind = ButtonKind.Secondary,
                    icon = if (editing) Icons.Rounded.Check else Icons.Rounded.Edit
                )
            }
            QuietButton("Cancel", onCancel)
        }
        VSpace(Cafe.space.xs)
        Text("Or just say yes or no.", style = Cafe.type.caption, color = Cafe.colors.cocoa)
    }
}

private fun verbIcon(verb: String, risk: Risk): ImageVector = when {
    verb.equals("Call", ignoreCase = true) -> Icons.Rounded.Call
    risk == Risk.Outward -> Icons.AutoMirrored.Rounded.Send
    else -> Icons.Rounded.DeleteOutline.takeIf { verb.contains("Delete", true) || verb.contains("Forget", true) } ?: Icons.Rounded.Check
}

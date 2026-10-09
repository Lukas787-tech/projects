package com.lukas.jarvis.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.foundation.border
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Cafe
import com.lukas.jarvis.ui.theme.Elevation
import com.lukas.jarvis.ui.theme.paper

/**
 * A field to write in: latte-coloured, soft-cornered, with its label above it
 * rather than inside, so the label is still there while you type.
 */
@Composable
fun CafeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    imeAction: ImeAction = ImeAction.Done,
    onDone: (() -> Unit)? = null,
    /** A key or a token: hidden until asked, never capitalised, never suggested. */
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** A line under the field: what it is for, or what is wrong with it. */
    supporting: String? = null,
    isError: Boolean = false
) {
    val colors = Cafe.colors
    var shown by remember { mutableStateOf(false) }
    val plain = secret || keyboardType != KeyboardType.Text
    Column(modifier) {
        if (label != null) {
            Text(label, style = Cafe.type.labelSmall, color = colors.cocoa, modifier = Modifier.padding(start = Cafe.space.xs, bottom = Cafe.space.xs))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            textStyle = Cafe.type.body.copy(color = colors.espresso),
            cursorBrush = SolidColor(colors.accentText),
            visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = if (plain) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                autoCorrectEnabled = !plain,
                keyboardType = if (secret) KeyboardType.Password else keyboardType,
                imeAction = imeAction
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onSend = { onDone?.invoke() }, onGo = { onDone?.invoke() }, onSearch = { onDone?.invoke() }),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { if (label != null) contentDescription = label },
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = Cafe.space.touch)
                        .clip(Cafe.shape.medium)
                        .background(colors.latte)
                        .then(if (isError) Modifier.border(1.5.dp, colors.berry, Cafe.shape.medium) else Modifier)
                        .padding(start = Cafe.space.l, end = if (secret) Cafe.space.xs else Cafe.space.l, top = Cafe.space.xs, bottom = Cafe.space.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f).padding(vertical = Cafe.space.s), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(placeholder, style = Cafe.type.body, color = colors.cocoa)
                        }
                        inner()
                    }
                    if (secret) {
                        IconCircle(
                            if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            if (shown) "Hide ${label ?: "it"}" else "Show ${label ?: "it"}",
                            { shown = !shown },
                            size = 40.dp,
                            tint = colors.cocoa
                        )
                    }
                }
            }
        )
        if (supporting != null) {
            Text(
                supporting,
                style = Cafe.type.caption,
                color = if (isError) colors.berryText else colors.cocoa,
                modifier = Modifier.padding(start = Cafe.space.xs, top = Cafe.space.xs)
            )
        }
    }
}

/** What the composer's mic button shows. */
enum class MicState { Ready, Listening, Busy, Unavailable }

/**
 * The way in that is always there: type, talk, or show Mochi something. Typing
 * and the mic are equal; whichever you use, the other is one tap away.
 */
@Composable
fun ComposerBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onCamera: () -> Unit,
    modifier: Modifier = Modifier,
    mic: MicState = MicState.Ready,
    placeholder: String = "Ask Mochi anything",
    partial: String = "",
    focus: FocusRequester? = null
) {
    val colors = Cafe.colors
    // Text handed in from outside ("Remind me to ") arrives with the cursor at
    // its end, ready to be finished; the person's own edits keep their place.
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val shown = if (field.text == text) field else TextFieldValue(text, TextRange(text.length))
    Row(
        modifier
            .paper(Elevation.Lifted, Cafe.shape.xlarge, colors)
            .clip(Cafe.shape.xlarge)
            .background(colors.paper)
            .padding(start = Cafe.space.l, end = Cafe.space.xs, top = Cafe.space.xs, bottom = Cafe.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Cafe.space.xs)
    ) {
        BasicTextField(
            value = shown,
            onValueChange = {
                field = it
                if (it.text != text) onTextChange(it.text)
            },
            textStyle = Cafe.type.body.copy(color = colors.espresso),
            cursorBrush = SolidColor(colors.accentText),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend() }),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Cafe.space.touch)
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .semantics { contentDescription = "Message to Mochi" },
            decorationBox = { inner ->
                Box(Modifier.heightIn(min = Cafe.space.touch), contentAlignment = Alignment.CenterStart) {
                    when {
                        text.isEmpty() && mic == MicState.Listening && partial.isNotBlank() ->
                            Text(partial, style = Cafe.type.body, color = colors.cocoa, maxLines = 2)
                        text.isEmpty() -> Text(
                            if (mic == MicState.Listening) "Listening…" else placeholder,
                            style = Cafe.type.body,
                            color = colors.cocoa,
                            maxLines = 1
                        )
                    }
                    inner()
                }
            }
        )
        if (text.isBlank()) {
            IconCircle(Icons.Rounded.PhotoCamera, "Show Mochi something", onCamera)
            IconCircle(
                icon = if (mic == MicState.Listening || mic == MicState.Busy) Icons.Rounded.Stop else Icons.Rounded.Mic,
                description = when (mic) {
                    MicState.Listening -> "Stop listening"
                    MicState.Busy -> "Stop"
                    MicState.Unavailable -> "Microphone unavailable, type instead"
                    MicState.Ready -> "Talk to Mochi"
                },
                onClick = onMic,
                filled = mic != MicState.Unavailable,
                enabled = mic != MicState.Unavailable
            )
        } else {
            IconCircle(Icons.AutoMirrored.Rounded.Send, "Send", onSend, filled = true)
        }
    }
}

/** A setting that is on or off, with what it does in plain words. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true
) {
    val colors = Cafe.colors
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Cafe.space.touch + Cafe.space.s)
            .clip(Cafe.shape.medium)
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .semantics(mergeDescendants = true) { stateDescription = if (checked) "On" else "Off" }
            .padding(vertical = Cafe.space.s, horizontal = Cafe.space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = Cafe.space.m)) {
            Text(title, style = Cafe.type.body, color = colors.espresso)
            if (detail != null) Text(detail, style = Cafe.type.bodySmall, color = colors.cocoa)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accentFill,
                uncheckedThumbColor = colors.cocoa,
                uncheckedTrackColor = colors.latte,
                uncheckedBorderColor = colors.latteDeep
            )
        )
    }
}

/** A level: volume, brightness, speed. [valueLabel] says the value in words for TalkBack and the eye. */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    valueLabel: String? = null,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val colors = Cafe.colors
    Column(modifier.fillMaxWidth().padding(vertical = Cafe.space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Cafe.type.body, color = colors.espresso, modifier = Modifier.weight(1f))
            if (valueLabel != null) Text(valueLabel, style = Cafe.type.labelSmall, color = colors.cocoa)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier.semantics { if (valueLabel != null) stateDescription = valueLabel },
            colors = SliderDefaults.colors(
                thumbColor = colors.accentFill,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.latte
            )
        )
    }
}

/** A row of small things that wraps onto the next line when it runs out of room. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Wrap(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Cafe.space.s),
        verticalArrangement = Arrangement.spacedBy(Cafe.space.s)
    ) { content() }
}

internal val ChipIconSize = 18.dp

package com.lukas.jarvis.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lukas.jarvis.ui.theme.Accent
import com.lukas.jarvis.ui.theme.Corner
import com.lukas.jarvis.ui.theme.DialogPane
import com.lukas.jarvis.ui.theme.Film
import com.lukas.jarvis.ui.theme.GlassEdgeBright
import com.lukas.jarvis.ui.theme.GlassEdgeDim
import com.lukas.jarvis.ui.theme.Hairline
import com.lukas.jarvis.ui.theme.Negative
import com.lukas.jarvis.ui.theme.Space
import com.lukas.jarvis.ui.theme.TextFaint
import com.lukas.jarvis.ui.theme.TextPrimary
import com.lukas.jarvis.ui.theme.TextSecondary

/**
 * A text field cut from the same glass as everything else.
 *
 * Material's outlined field was used as it came: a hard grey outline, a
 * transparent inside and a label that turned silver on focus. Next to glass
 * panels it looked like a form from another app. This is the same field with
 * the film inside it and a hairline edge that lights when it has the cursor —
 * one component, so every field in the app is the same field.
 */
@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = if (label != null) {
            { Text(label) }
        } else {
            null
        },
        placeholder = if (placeholder != null) {
            { Text(placeholder, color = TextFaint) }
        } else {
            null
        },
        leadingIcon = if (leadingIcon != null) {
            { Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        trailingIcon = trailing,
        supportingText = if (supportingText != null) {
            { Text(supportingText, style = MaterialTheme.typography.labelSmall) }
        } else {
            null
        },
        isError = isError,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 6,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(Corner.medium),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedContainerColor = Film.lifted,
            unfocusedContainerColor = Film.faint,
            focusedBorderColor = Accent.copy(alpha = 0.55f),
            unfocusedBorderColor = Hairline,
            cursorColor = Accent,
            focusedLabelColor = Accent,
            unfocusedLabelColor = TextFaint,
            focusedLeadingIconColor = Accent,
            unfocusedLeadingIconColor = TextFaint,
            focusedTrailingIconColor = TextSecondary,
            unfocusedTrailingIconColor = TextFaint,
            focusedSupportingTextColor = TextFaint,
            unfocusedSupportingTextColor = TextFaint,
            errorContainerColor = Negative.copy(alpha = 0.06f),
            errorBorderColor = Negative.copy(alpha = 0.7f),
            errorLabelColor = Negative,
            errorSupportingTextColor = Negative,
            errorCursorColor = Negative
        )
    )
}

/**
 * A dialog on the one opaque pane, with glass buttons.
 *
 * The stock dialog took its colour from a container role the theme never set,
 * so every "New task" box arrived tinted violet with plain text buttons. This
 * one is the dialog pane, lit along its top edge like the glass underneath it,
 * and it scrolls, because the tracker form is taller than a small phone held
 * sideways.
 */
@Composable
fun GlassDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissLabel: String = "Cancel",
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(Corner.card)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(
            width = 1.dp,
            brush = Brush.verticalGradient(listOf(GlassEdgeBright, GlassEdgeDim)),
            shape = shape
        ),
        shape = shape,
        containerColor = DialogPane,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        tonalElevation = 0.dp,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.tight),
                content = content
            )
        },
        confirmButton = {
            ChipButton(
                label = confirmLabel,
                onClick = onConfirm,
                enabled = confirmEnabled,
                prominent = true
            )
        },
        dismissButton = { ChipButton(label = dismissLabel, onClick = onDismiss) }
    )
}

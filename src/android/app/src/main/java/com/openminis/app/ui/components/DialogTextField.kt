package com.openminis.app.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Outlined text input for use inside a Dialog / ModalBottomSheet /
 * full-screen editor. Sister composable of [SectionTextField] — same
 * BasicTextField + OutlinedTextFieldDefaults.DecorationBox pattern so we
 * can override contentPadding, but keeps Material3's outlined border +
 * container visible (dialog backgrounds use surfaceContainerHigh /
 * surfaceContainerHighest, where SectionTextField's transparent chrome
 * would leave the input invisible).
 *
 * Layout contract: caller renders the label outside the field
 * (`Text(label) + Spacer(6.dp) + DialogTextField(...)`) — the inner
 * BasicTextField does NOT use a floating label slot.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    fieldModifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors()
    // Tighten Material's default 16dp/16dp contentPadding so the min-height
    // comfortably fits a single line of text + cursor.
    // [T-android-search-height] 8.dp of vertical padding plus the text's own
    // line box exceeded the 42.dp target on its own, so the field would have
    // ignored heightIn and stayed tall. 6.dp leaves the single-line case
    // comfortably inside 42 while keeping the text off the border.
    val contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
    val mergedTextStyle = LocalTextStyle.current.merge(textStyle)
    // [T-android-settings-field-selection-menu] The selection state is owned
    // here (TextFieldValue) instead of being left to the String overload: the
    // long-press menu is driven by the field's own selection, and the
    // TextFieldValue overload is the one that keeps it (and therefore keeps
    // the menu) stable across recompositions. Same pattern as
    // [SectionTextField].
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (fieldValue.text != value) {
        // The parent replaced the text (reset / hydration) — re-seed the caret
        // at the end, matching the legacy String overload.
        fieldValue = TextFieldValue(value, TextRange(value.length))
    }
    // The long-press menu is drawn INSIDE the app window (see
    // [InWindowTextToolbar]) instead of in the platform's popup window: that
    // window is outside the app's own window, so a pointer coming from a PC
    // screen projection never reaches it and the menu is dismissed before it
    // can be used.
    val toolbar = remember { InWindowTextToolbar() }
    var fieldHeight by remember { mutableStateOf(0.dp) }
    var fieldTopInWindow by remember { mutableStateOf(0.dp) }
    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        Box {
            BasicTextField(
                value = fieldValue,
                onValueChange = { newValue ->
                    fieldValue = newValue
                    if (newValue.text != value) onValueChange(newValue.text)
                },
                modifier = modifier
                    .fillMaxWidth()
                    // [T-android-search-height] Unified search/input height.
                    .heightIn(min = 42.dp)
                    // Anchor for the in-window selection menu.
                    .onGloballyPositioned { coords ->
                        fieldHeight = coords.size.height.toDp()
                        fieldTopInWindow = coords.positionInWindow().y.toDp()
                    }
                    .then(fieldModifier),
                enabled = enabled,
                readOnly = readOnly,
                textStyle = mergedTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                interactionSource = interactionSource,
                singleLine = singleLine,
                maxLines = maxLines,
                decorationBox = { innerTextField ->
                    OutlinedTextFieldDefaults.DecorationBox(
                        value = fieldValue.text,
                        visualTransformation = visualTransformation,
                        innerTextField = innerTextField,
                        placeholder = placeholder?.let { { Text(it) } },
                        label = null,
                        trailingIcon = trailingIcon,
                        singleLine = singleLine,
                        enabled = enabled,
                        isError = isError,
                        interactionSource = interactionSource,
                        colors = colors,
                        contentPadding = contentPadding,
                    )
                },
            )
            InWindowTextToolbarHost(toolbar, fieldHeight, fieldTopInWindow)
        }
    }
}

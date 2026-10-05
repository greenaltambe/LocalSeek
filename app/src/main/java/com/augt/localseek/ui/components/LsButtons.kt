package com.augt.localseek.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/*
 * Thin wrappers over the Material 3 buttons. Material3 buttons default to a fully rounded (pill) shape; the owner's decision is
 * "slightly rounded", so every button in the app goes through these and gets MaterialTheme.shapes.medium (12 dp) unless a caller
 * passes its own shape. ButtonShapeRuleTest fails if a raw Button( / OutlinedButton( / ... is used outside ui/components.
 */

@Composable
fun LsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, contentPadding = contentPadding, interactionSource = interactionSource, content = content)

@Composable
fun LsTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = FilledTonalButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, contentPadding = contentPadding, interactionSource = interactionSource, content = content)

@Composable
fun LsOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, border = border, contentPadding = contentPadding, interactionSource = interactionSource, content = content)

@Composable
fun LsTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) = TextButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, contentPadding = contentPadding, interactionSource = interactionSource, content = content)

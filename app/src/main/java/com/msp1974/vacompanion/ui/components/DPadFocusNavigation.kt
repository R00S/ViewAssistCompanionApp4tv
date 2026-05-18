package com.msp1974.vacompanion.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Intercepts D-pad arrow key presses and asks Compose to move focus in that direction.
 *
 * Returns the [Modifier] with key handling attached and only consumes the event when
 * focus movement succeeds ([FocusManager.moveFocus] returns true).
 */
fun Modifier.dpadFocusNavigation(focusManager: FocusManager): Modifier {
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

        val direction = when (event.key) {
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            Key.DirectionLeft -> FocusDirection.Left
            Key.DirectionRight -> FocusDirection.Right
            else -> null
        } ?: return@onPreviewKeyEvent false

        return@onPreviewKeyEvent focusManager.moveFocus(direction)
    }
}

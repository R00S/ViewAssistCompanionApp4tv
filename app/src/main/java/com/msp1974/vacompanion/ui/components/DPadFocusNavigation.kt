package com.msp1974.vacompanion.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Represents a navigation group of focusable entities.
 *
 * @param first FocusRequester for the first entity in the group (used when navigating Right
 *              into this group from the preceding group).
 * @param last  FocusRequester for the last entity in the group (used when navigating Left
 *              into this group from the following group). Defaults to [first] for single-item
 *              groups.
 */
data class FocusGroup(
    val first: FocusRequester,
    val last: FocusRequester = first,
) {
    /**
     * Focuses [first]; if [first] is not currently attached, falls back to [last].
     * Returns true when focus was successfully moved.
     */
    internal fun focusFirst(): Boolean =
        runCatching { first.requestFocus() }.isSuccess ||
        (last !== first && runCatching { last.requestFocus() }.isSuccess)

    /**
     * Focuses [last]; if [last] is not currently attached, falls back to [first].
     * Returns true when focus was successfully moved.
     */
    internal fun focusLast(): Boolean =
        runCatching { last.requestFocus() }.isSuccess ||
        (last !== first && runCatching { first.requestFocus() }.isSuccess)
}

/**
 * Attaches a D-pad key handler that implements linked-list group navigation:
 *
 * - **Up / Down** move focus within the current group using Compose's built-in
 *   [FocusManager.moveFocus].
 * - **Right** jumps to the **first** entity in the next available group.
 * - **Left** jumps to the **last** entity in the previous available group.
 *
 * Groups whose [FocusRequester]s are not currently attached (i.e. the composable is not
 * in the composition) are skipped automatically.
 *
 * @param groups            Ordered list of [FocusGroup]s that form the navigation chain.
 * @param currentGroupIndex Lambda that returns the index of the group that currently holds
 *                          focus. Should be backed by a [androidx.compose.runtime.mutableIntStateOf].
 * @param focusManager      The [FocusManager] obtained from
 *                          [androidx.compose.ui.platform.LocalFocusManager].
 */
fun Modifier.dpadGroupNavigation(
    groups: List<FocusGroup>,
    currentGroupIndex: () -> Int,
    focusManager: FocusManager,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
        Key.DirectionRight -> jumpToNext(groups, currentGroupIndex())
        Key.DirectionLeft  -> jumpToPrev(groups, currentGroupIndex())
        Key.DirectionDown  -> focusManager.moveFocus(FocusDirection.Down)
        Key.DirectionUp    -> focusManager.moveFocus(FocusDirection.Up)
        else -> false
    }
}

/**
 * Marks a layout container or individual node as belonging to [groupIndex] in the navigation
 * chain.  Whenever the node itself or any of its descendants receives focus,
 * [onGroupFocused] is called with [groupIndex] so the parent composable can update its
 * `currentGroupIndex` state.
 */
fun Modifier.dpadNavigationGroup(
    groupIndex: Int,
    onGroupFocused: (Int) -> Unit,
): Modifier = onFocusChanged { focusState ->
    if (focusState.hasFocus || focusState.isFocused) onGroupFocused(groupIndex)
}

/**
 * Simple D-pad handler for a single focusable container (e.g. a dialog card).
 * All four arrow keys move focus using Compose's built-in [FocusManager.moveFocus].
 * Attach to any layout that contains focusable children such as buttons or text fields.
 */
fun Modifier.dpadFocusNavigation(focusManager: FocusManager): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionUp    -> focusManager.moveFocus(FocusDirection.Up)
            Key.DirectionDown  -> focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionLeft  -> focusManager.moveFocus(FocusDirection.Left)
            Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
            else               -> false
        }
    }

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

private fun jumpToNext(groups: List<FocusGroup>, currentIndex: Int): Boolean {
    val size = groups.size
    for (step in 1..size) {
        val idx = (currentIndex + step) % size
        if (groups[idx].focusFirst()) return true
    }
    return false
}

private fun jumpToPrev(groups: List<FocusGroup>, currentIndex: Int): Boolean {
    val size = groups.size
    for (step in 1..size) {
        val idx = (currentIndex - step + size) % size
        if (groups[idx].focusLast()) return true
    }
    return false
}

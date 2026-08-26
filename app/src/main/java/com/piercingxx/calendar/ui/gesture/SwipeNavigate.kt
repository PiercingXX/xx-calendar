package com.piercingxx.calendar.ui.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Horizontal swipe → previous / next window. A flick (under the long-press
 * timeout, mostly horizontal, past a dp threshold) navigates; a held drag is
 * left for the day/week grid's create/move/resize gestures.
 */
internal object SwipeNavigate {
    const val PREVIOUS = -1
    const val NONE = 0
    const val NEXT = 1

    fun direction(dx: Float, dy: Float, thresholdPx: Float): Int {
        if (abs(dx) < thresholdPx) return NONE
        if (abs(dx) <= abs(dy)) return NONE
        return if (dx < 0f) NEXT else PREVIOUS
    }
}

fun Modifier.horizontalSwipeNavigate(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier = composed {
    val latestPrevious = rememberUpdatedState(onPrevious)
    val latestNext = rememberUpdatedState(onNext)
    val thresholdPx = with(LocalDensity.current) { 56.dp.toPx() }
    pointerInput(thresholdPx) {
        val slop = viewConfiguration.touchSlop
        val longPress = viewConfiguration.longPressTimeoutMillis
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var totalX = 0f
            var totalY = 0f
            var lockedHorizontal = false
            var heldTooLong = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (change.uptimeMillis - down.uptimeMillis >= longPress) {
                        heldTooLong = true
                    }
                    break
                }
                if (change.uptimeMillis - down.uptimeMillis >= longPress) {
                    heldTooLong = true
                }
                val delta = change.positionChange()
                totalX += delta.x
                totalY += delta.y
                if (!lockedHorizontal && !heldTooLong) {
                    if (abs(totalX) > slop || abs(totalY) > slop) {
                        lockedHorizontal = abs(totalX) > abs(totalY)
                    }
                }
                if (lockedHorizontal && !heldTooLong) {
                    change.consume()
                }
            }
            if (heldTooLong) return@awaitEachGesture
            when (SwipeNavigate.direction(totalX, totalY, thresholdPx)) {
                SwipeNavigate.PREVIOUS -> latestPrevious.value()
                SwipeNavigate.NEXT -> latestNext.value()
            }
        }
    }
}

package dev.liteluncher.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import dev.liteluncher.data.Direction
import dev.liteluncher.data.NavigationPrefs
import dev.liteluncher.data.Screen
import kotlin.math.abs

class ScreenManager(initial: Screen = Screen.HOME) {
    var current by mutableStateOf(initial)
        private set

    fun navigate(screen: Screen, prefs: NavigationPrefs) {
        if (screen == Screen.HOME || screen in prefs.enabledScreens) current = screen
    }

    fun swipe(direction: Direction, prefs: NavigationPrefs) {
        val dx = when (direction) { Direction.LEFT -> 1; Direction.RIGHT -> -1; else -> 0 }
        val dy = when (direction) { Direction.UP -> 1; Direction.DOWN -> -1; else -> 0 }
        val target = Screen.entries.firstOrNull { it.x == current.x + dx && it.y == current.y + dy }
        when {
            target != null && target in prefs.enabledScreens -> navigate(target, prefs)
            prefs.infinite -> {
                val axis = Screen.entries.filter { it in prefs.enabledScreens && (if (dx != 0) it.y == current.y else it.x == current.x) }
                    .sortedBy { if (dx != 0) it.x else it.y }
                if (axis.isNotEmpty()) {
                    val index = axis.indexOf(current).coerceAtLeast(0)
                    val step = if (dx + dy > 0) 1 else -1
                    navigate(axis[(index + step + axis.size) % axis.size], prefs)
                }
            }
        }
    }

    companion object {
        val stateSaver = Saver<ScreenManager, String>(
            save = { it.current.name },
            restore = { ScreenManager(runCatching { Screen.valueOf(it) }.getOrDefault(Screen.HOME)) }
        )
    }
}

/** Real-time horizontal pager gesture: finger tracks canvas 1:1 (translationX = touchDeltaX),
 *  fling velocity completes the transition with spring physics on release. */
@Composable
fun Modifier.pagerGesture(
    onTranslate: (Float) -> Unit,
    onFling: (Float, Float) -> Unit,
    onVerticalSwipe: (Direction) -> Unit
): Modifier {
    val translateAction by rememberUpdatedState(onTranslate)
    val flingAction by rememberUpdatedState(onFling)
    val verticalAction by rememberUpdatedState(onVerticalSwipe)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            val start = down.position
            var lastPos = down.position
            var finished = false
            while (!finished) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                event.changes.firstOrNull { it.id == down.id }?.let { change ->
                    if (change.position != change.previousPosition) {
                        val deltaX = change.position.x - lastPos.x
                        translateAction(deltaX)
                        lastPos = change.position
                    }
                }
                finished = event.changes.all { !it.pressed }
            }
            val upEvent = event.changes.firstOrNull { it.id == down.id }
            val totalDx = upEvent?.let { it.position.x - start.x } ?: 0f
            val velocityX = upEvent?.velocity?.velocity?.x ?: 0f
            flingAction(velocityX, totalDx)
            val totalDy = upEvent?.let { it.position.y - start.y } ?: 0f
            if (abs(totalDy) > abs(totalDx) && abs(totalDy) > 64 * density) {
                verticalAction(if (totalDy < 0) Direction.UP else Direction.DOWN)
            }
        }
    }
}

/** Observe at Final so a list, icon or native widget retains its own touch sequence. */
@Composable
fun Modifier.launcherGestures(
    fingers: Int, home: Boolean, onSwipe: (Direction, Int) -> Unit, onPinch: (Boolean) -> Unit
): Modifier {
    val swipeAction by rememberUpdatedState(onSwipe)
    val pinchAction by rememberUpdatedState(onPinch)
    return pointerInput(fingers, home) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        val start = down.position
        var displacement = Offset.Zero
        var pointerCount = 1
        var consumed = false
        var firstSpan = 0f
        var lastSpan = 0f
        var finished = false
        while (!finished) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val pressed = event.changes.filter { it.pressed }
            pointerCount = maxOf(pointerCount, pressed.size)
            event.changes.firstOrNull { it.id == down.id }?.let {
                displacement = it.position - start
                if (it.position != it.previousPosition) consumed = consumed || it.isConsumed
            }
            if (pressed.size >= 2) {
                lastSpan = (pressed[0].position - pressed[1].position).getDistance()
                if (firstSpan == 0f) firstSpan = lastSpan
            }
            finished = pressed.isEmpty()
        }
        val pinch = firstSpan > 0f && abs(lastSpan - firstSpan) > 64 * density
        when {
            pinch && (!consumed || pointerCount >= 2) -> pinchAction(lastSpan < firstSpan)
            pointerCount > 0 && (!consumed || pointerCount >= 2) &&
                maxOf(abs(displacement.x), abs(displacement.y)) > 68 * density -> {
                val direction = if (abs(displacement.x) > abs(displacement.y)) {
                    if (displacement.x < 0) Direction.LEFT else Direction.RIGHT
                } else if (displacement.y < 0) Direction.UP else Direction.DOWN
                swipeAction(direction, pointerCount)
            }
        }
    }
}
}

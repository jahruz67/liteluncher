package dev.liteluncher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
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

fun NavigationPrefs.horizontalScreens(): List<Screen> =
    listOf(Screen.DESKTOP, Screen.HOME, Screen.APPS).filter { it == Screen.HOME || it in enabledScreens }

class ScreenManager(initial: Screen = Screen.HOME) {
    var current by mutableStateOf(initial)
        private set
    var navigationRevision by mutableLongStateOf(0L)
        private set

    fun navigate(screen: Screen, prefs: NavigationPrefs): Boolean {
        if (screen == Screen.HOME || screen in prefs.enabledScreens) {
            current = screen
            // Repeating Home must also cancel a swipe that has not settled yet.
            navigationRevision++
            return true
        }
        return false
    }

    fun onPageSettled(screen: Screen, prefs: NavigationPrefs) {
        if (screen in prefs.horizontalScreens()) current = screen
    }

    fun swipe(direction: Direction, prefs: NavigationPrefs) {
        val horizontal = direction == Direction.LEFT || direction == Direction.RIGHT
        val axis = Screen.entries.filter {
            (it == Screen.HOME || it in prefs.enabledScreens) && (if (horizontal) it.y == current.y else it.x == current.x)
        }.sortedBy { if (horizontal) it.x else it.y }
        val index = axis.indexOf(current)
        if (index < 0 || axis.size < 2) return
        val step = if (direction == Direction.LEFT || direction == Direction.UP) 1 else -1
        val next = index + step
        val target = axis.getOrNull(next) ?: if (prefs.infinite) axis[(next + axis.size) % axis.size] else return
        navigate(target, prefs)
    }

    companion object {
        val stateSaver = Saver<ScreenManager, String>(
            save = { it.current.name },
            restore = { ScreenManager(runCatching { Screen.valueOf(it) }.getOrDefault(Screen.HOME)) }
        )
    }
}

/** Observe at Final so a list, icon or native widget retains its own touch sequence. */
@Composable
fun Modifier.launcherGestures(
    fingers: Int, home: Boolean, onSwipe: (Direction, Int) -> Unit, onPinch: (Boolean) -> Unit,
    onPointerCountChange: (Int) -> Unit = {}
): Modifier {
    val swipeAction by rememberUpdatedState(onSwipe)
    val pinchAction by rememberUpdatedState(onPinch)
    val pointerCountAction by rememberUpdatedState(onPointerCountChange)
    return pointerInput(fingers, home) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            val start = down.position
            var displacement = Offset.Zero
            var pointerCount = 1
            var consumed = false
            var swipePointers = 1
            var firstSpan = 0f
            var lastSpan = 0f
            var finished = false
            pointerCountAction(1)
            try {
                while (!finished) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val pressed = event.changes.filter { it.pressed }
                    pointerCount = maxOf(pointerCount, pressed.size)
                    pointerCountAction(pointerCount)
                    event.changes.firstOrNull { it.id == down.id }?.let {
                        displacement = it.position - start
                    }
                    if (pressed.isNotEmpty() && maxOf(abs(displacement.x), abs(displacement.y)) > viewConfiguration.touchSlop) {
                        swipePointers = pressed.size
                    }
                    event.changes.forEach {
                        if (it.pressed && it.previousPressed && it.position != it.previousPosition) {
                            consumed = consumed || it.isConsumed
                        }
                    }
                    if (pressed.size >= 2) {
                        lastSpan = (pressed[0].position - pressed[1].position).getDistance()
                        if (firstSpan == 0f) firstSpan = lastSpan
                    }
                    finished = pressed.isEmpty()
                }
            } finally {
                pointerCountAction(0)
            }
            val pinch = firstSpan > 0f && abs(lastSpan - firstSpan) > 64 * density
            val verticalHomeSwipe = home && abs(displacement.y) > abs(displacement.x)
            val swipeThreshold = if (verticalHomeSwipe) maxOf(48 * density, viewConfiguration.touchSlop * 2) else 68 * density
            when {
                pinch && home && !consumed -> pinchAction(lastSpan < firstSpan)
                !consumed &&
                    maxOf(abs(displacement.x), abs(displacement.y)) > swipeThreshold -> {
                    val direction = if (abs(displacement.x) > abs(displacement.y)) {
                        if (displacement.x < 0) Direction.LEFT else Direction.RIGHT
                    } else if (displacement.y < 0) Direction.UP else Direction.DOWN
                    swipeAction(direction, swipePointers)
                }
            }
        }
    }
}

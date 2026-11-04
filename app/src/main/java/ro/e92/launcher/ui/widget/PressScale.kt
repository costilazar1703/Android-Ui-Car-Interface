package ro.e92.launcher.ui.widget

import android.view.MotionEvent
import android.view.View

private const val PRESSED_SCALE = 0.82f
private const val PRESS_MS = 90L

/** Butoanele fără fundal: apăsate, iconița se micșorează puțin și revine. */
fun View.scaleOnPress() {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                v.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE).setDuration(PRESS_MS).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(PRESS_MS).start()
        }
        false
    }
}

package app.peloskin.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Lets an overlay window be dragged vertically while taps still reach its content.
 * Uses screen coordinates (rawY), because the window itself moves under the finger.
 */
@SuppressLint("ViewConstructor")
class DragFrame(
    context: Context,
    private val onDrag: (totalDy: Float) -> Unit,
    private val onDragEnd: () -> Unit,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downRawY = ev.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> if (abs(ev.rawY - downRawY) > slop) dragging = true
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            // Reached only when no child took the touch: start tracking here instead.
            MotionEvent.ACTION_DOWN -> { downRawY = ev.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.rawY - downRawY) > slop) dragging = true
                if (dragging) onDrag(ev.rawY - downRawY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDragEnd()
                dragging = false
            }
        }
        return true
    }
}

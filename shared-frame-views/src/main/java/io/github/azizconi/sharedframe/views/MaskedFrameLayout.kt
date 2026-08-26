package io.github.azizconi.sharedframe.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

internal class MaskedFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    internal interface GestureDelegate {
        fun onInterceptTouchEvent(event: MotionEvent): Boolean
        fun onTouchEvent(event: MotionEvent): Boolean
    }

    var gestureDelegate: GestureDelegate? = null
    var maskFrame: RectF? = null
        set(value) { field = value; invalidate() }
    var maskRadius: Float = 0f
        set(value) { field = value; invalidate() }
    private val path = Path()

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        gestureDelegate?.onInterceptTouchEvent(event) ?: super.onInterceptTouchEvent(event)

    override fun onTouchEvent(event: MotionEvent): Boolean =
        gestureDelegate?.onTouchEvent(event) == true || super.onTouchEvent(event)

    override fun draw(canvas: Canvas) {
        val frame = maskFrame ?: return super.draw(canvas)
        val save = canvas.save()
        path.reset()
        path.addRoundRect(frame, maskRadius, maskRadius, Path.Direction.CW)
        canvas.clipPath(path)
        super.draw(canvas)
        canvas.restoreToCount(save)
    }
}

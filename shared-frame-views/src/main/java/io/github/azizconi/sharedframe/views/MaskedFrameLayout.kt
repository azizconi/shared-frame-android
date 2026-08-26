package io.github.azizconi.sharedframe.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

internal class MaskedFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var maskFrame: RectF? = null
        set(value) { field = value; invalidate() }
    var maskRadius: Float = 0f
        set(value) { field = value; invalidate() }
    private val path = Path()

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

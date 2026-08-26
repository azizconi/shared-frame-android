package io.github.azizconi.sharedframe.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Frame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun isUsable(): Boolean = left.isFinite() && top.isFinite() && right.isFinite() &&
        bottom.isFinite() && width > 0f && height > 0f
}

data class UniformTransform(val scale: Float, val translationX: Float, val translationY: Float) {
    companion object { val Identity = UniformTransform(1f, 0f, 0f) }
    fun isUsable() = scale.isFinite() && scale > 0f && translationX.isFinite() && translationY.isFinite()
}

data class ImageTransform(val scale: Float, val translationX: Float, val translationY: Float) {
    fun isUsable() = scale.isFinite() && scale > 0f && translationX.isFinite() && translationY.isFinite()
}

data class CubicBezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

data class SharedFrameConfig(
    val durationMillis: Long = 250L,
    val easing: CubicBezier = CubicBezier(.25f, .1f, .25f, 1f),
    val scrimAlpha: Float = .34f,
    val minimumDragScale: Float = .6f,
    val dismissDistanceFraction: Float = .15f,
    val minimumFlingVelocityDp: Float = 700f,
    val dismissDirections: Set<SharedFrameDismissDirection> = setOf(
        SharedFrameDismissDirection.Left,
        SharedFrameDismissDirection.Right,
        SharedFrameDismissDirection.Down,
    ),
)

enum class SharedFrameDismissDirection { Left, Right, Down }

data class DragOffset(val x: Float, val y: Float)

enum class SharedFramePhase { Hidden, Opening, Idle, Dragging, CancellingDrag, Closing }

class SharedFrameStateMachine {
    var phase: SharedFramePhase = SharedFramePhase.Hidden
        private set

    fun beginOpen() = transition(SharedFramePhase.Hidden, SharedFramePhase.Opening)
    fun finishOpen() = transition(SharedFramePhase.Opening, SharedFramePhase.Idle)
    fun beginDrag() = transition(SharedFramePhase.Idle, SharedFramePhase.Dragging)
    fun cancelDrag() = transition(SharedFramePhase.Dragging, SharedFramePhase.CancellingDrag)
    fun finishCancel() = transition(SharedFramePhase.CancellingDrag, SharedFramePhase.Idle)
    fun beginClose(): Boolean = when (phase) {
        SharedFramePhase.Idle, SharedFramePhase.Dragging -> { phase = SharedFramePhase.Closing; true }
        else -> false
    }
    fun finishClose() = transition(SharedFramePhase.Closing, SharedFramePhase.Hidden)
    fun forceHidden() { phase = SharedFramePhase.Hidden }

    private fun transition(from: SharedFramePhase, to: SharedFramePhase): Boolean {
        if (phase != from) return false
        phase = to
        return true
    }
}

data class SharedFrameGeometry(
    val collapsedTransform: UniformTransform,
    val collapsedMask: Frame,
    val expandedMask: Frame,
)

enum class SharedFrameFit { AspectFill, AspectFit }

object SharedFrameMath {
    fun transformParentSoChildMatches(
        parent: Frame,
        child: Frame,
        target: Frame,
        fit: SharedFrameFit = SharedFrameFit.AspectFill,
    ): UniformTransform? {
        if (!parent.isUsable() || !child.isUsable() || !target.isUsable()) return null
        val scaleX = target.width / child.width
        val scaleY = target.height / child.height
        val scale = if (fit == SharedFrameFit.AspectFill) max(scaleX, scaleY) else min(scaleX, scaleY)
        if (!scale.isFinite() || scale <= 0f) return null
        return UniformTransform(
            scale,
            target.centerX - parent.centerX - (child.centerX - parent.centerX) * scale,
            target.centerY - parent.centerY - (child.centerY - parent.centerY) * scale,
        )
    }

    fun aspectFit(content: Frame, container: Frame): Frame? =
        scaleInto(content, container, useMaxScale = false)

    fun aspectFill(content: Frame, container: Frame): Frame? =
        scaleInto(content, container, useMaxScale = true)

    private fun scaleInto(content: Frame, container: Frame, useMaxScale: Boolean): Frame? {
        if (!content.isUsable() || !container.isUsable()) return null
        val widthScale = container.width / content.width
        val heightScale = container.height / content.height
        val scale = if (useMaxScale) max(widthScale, heightScale) else min(widthScale, heightScale)
        if (!scale.isFinite() || scale <= 0f) return null
        val width = content.width * scale
        val height = content.height * scale
        val left = container.centerX - width / 2f
        val top = container.centerY - height / 2f
        return Frame(left, top, left + width, top + height)
    }

    fun buildGeometry(sourceHero: Frame, parent: Frame, detailHero: Frame): SharedFrameGeometry? {
        val transform = transformParentSoChildMatches(parent, detailHero, sourceHero) ?: return null
        val mask = aspectFit(sourceHero, detailHero) ?: return null
        return SharedFrameGeometry(transform, mask, parent)
    }

    fun dragTransform(
        totalDragX: Float,
        totalDragY: Float,
        containerWidthPx: Float,
        minimumScale: Float = .6f,
    ): UniformTransform {
        val width = containerWidthPx.coerceAtLeast(1f)
        val progress = (totalDragX.coerceAtLeast(0f) / width).coerceIn(0f, 1f)
        return UniformTransform(
            (1f - progress * (1f - minimumScale)).coerceIn(minimumScale, 1f),
            totalDragX,
            totalDragY,
        )
    }

    fun resolveDismissDirection(
        totalDragX: Float,
        totalDragY: Float,
        touchSlopPx: Float,
        allowedDirections: Set<SharedFrameDismissDirection>,
        dominanceRatio: Float = 1.15f,
    ): SharedFrameDismissDirection? {
        if (!totalDragX.isFinite() || !totalDragY.isFinite() || allowedDirections.isEmpty()) return null
        val x = abs(totalDragX)
        val y = abs(totalDragY)
        val distance = hypot(x, y)
        val slop = touchSlopPx.coerceAtLeast(0f)
        if (distance <= slop) return null

        val horizontal = if (totalDragX < 0f) SharedFrameDismissDirection.Left else SharedFrameDismissDirection.Right
        if (x >= y * dominanceRatio && horizontal in allowedDirections) return horizontal
        if (totalDragY > 0f && y >= x * dominanceRatio && SharedFrameDismissDirection.Down in allowedDirections) {
            return SharedFrameDismissDirection.Down
        }

        if (distance >= slop * 2f) {
            if (x >= y && horizontal in allowedDirections) return horizontal
            if (totalDragY > 0f && SharedFrameDismissDirection.Down in allowedDirections) {
                return SharedFrameDismissDirection.Down
            }
        }
        return null
    }

    fun dragOffsetAfterSlop(totalDragX: Float, totalDragY: Float, touchSlopPx: Float): DragOffset {
        if (!totalDragX.isFinite() || !totalDragY.isFinite()) return DragOffset(0f, 0f)
        val distance = hypot(totalDragX, totalDragY)
        if (distance <= 0f) return DragOffset(0f, 0f)
        val factor = ((distance - touchSlopPx.coerceAtLeast(0f)) / distance).coerceIn(0f, 1f)
        return DragOffset(totalDragX * factor, totalDragY * factor)
    }

    fun dragTransform(
        totalDragX: Float,
        totalDragY: Float,
        containerWidthPx: Float,
        containerHeightPx: Float,
        direction: SharedFrameDismissDirection,
        minimumScale: Float = .6f,
    ): UniformTransform {
        val reference = min(containerWidthPx, containerHeightPx).coerceAtLeast(1f)
        val progress = (projectedTravel(totalDragX, totalDragY, direction) / reference).coerceIn(0f, 1f)
        return UniformTransform(
            (1f - progress * (1f - minimumScale)).coerceIn(minimumScale, 1f),
            totalDragX,
            totalDragY,
        )
    }

    fun shouldFinishDismiss(
        totalDragX: Float,
        velocityXPxPerSecond: Float,
        containerWidthPx: Float,
        minimumFlingVelocityPxPerSecond: Float,
        distanceFraction: Float = .25f,
    ): Boolean = totalDragX >= containerWidthPx * distanceFraction ||
        velocityXPxPerSecond >= minimumFlingVelocityPxPerSecond

    fun shouldFinishDismiss(
        totalDragX: Float,
        totalDragY: Float,
        velocityXPxPerSecond: Float,
        velocityYPxPerSecond: Float,
        containerWidthPx: Float,
        containerHeightPx: Float,
        direction: SharedFrameDismissDirection,
        minimumFlingVelocityPxPerSecond: Float,
        distanceFraction: Float = .15f,
    ): Boolean {
        val reference = min(containerWidthPx, containerHeightPx).coerceAtLeast(1f)
        val distance = projectedTravel(totalDragX, totalDragY, direction)
        val velocity = projectedVelocity(velocityXPxPerSecond, velocityYPxPerSecond, direction)
        return distance >= reference * distanceFraction.coerceAtLeast(0f) ||
            velocity >= minimumFlingVelocityPxPerSecond.coerceAtLeast(0f)
    }

    private fun projectedTravel(x: Float, y: Float, direction: SharedFrameDismissDirection): Float = when (direction) {
        SharedFrameDismissDirection.Left -> (-x).coerceAtLeast(0f)
        SharedFrameDismissDirection.Right -> x.coerceAtLeast(0f)
        SharedFrameDismissDirection.Down -> y.coerceAtLeast(0f)
    }

    private fun projectedVelocity(x: Float, y: Float, direction: SharedFrameDismissDirection): Float = when (direction) {
        SharedFrameDismissDirection.Left -> -x
        SharedFrameDismissDirection.Right -> x
        SharedFrameDismissDirection.Down -> y
    }

    fun centerCropTransform(
        contentWidth: Float,
        contentHeight: Float,
        containerWidth: Float,
        containerHeight: Float,
    ): ImageTransform? = imageFitTransform(contentWidth, contentHeight, containerWidth, containerHeight, true)

    fun centerFitTransform(
        contentWidth: Float,
        contentHeight: Float,
        containerWidth: Float,
        containerHeight: Float,
    ): ImageTransform? = imageFitTransform(contentWidth, contentHeight, containerWidth, containerHeight, false)

    private fun imageFitTransform(
        contentWidth: Float,
        contentHeight: Float,
        containerWidth: Float,
        containerHeight: Float,
        crop: Boolean,
    ): ImageTransform? {
        if (contentWidth <= 0f || contentHeight <= 0f || containerWidth <= 0f || containerHeight <= 0f) return null
        val widthScale = containerWidth / contentWidth
        val heightScale = containerHeight / contentHeight
        val scale = if (crop) max(widthScale, heightScale) else min(widthScale, heightScale)
        if (!scale.isFinite() || scale <= 0f) return null
        return ImageTransform(
            scale,
            (containerWidth - contentWidth * scale) / 2f,
            (containerHeight - contentHeight * scale) / 2f,
        )
    }

    fun imageTransformInScreen(
        local: ImageTransform,
        parentFrame: Frame,
        heroFrame: Frame,
        parentTransform: UniformTransform,
    ): ImageTransform? {
        if (!local.isUsable() || !parentFrame.isUsable() || !heroFrame.isUsable() || !parentTransform.isUsable()) return null
        val scale = parentTransform.scale
        val offsetX = parentFrame.centerX * (1f - scale) + parentTransform.translationX
        val offsetY = parentFrame.centerY * (1f - scale) + parentTransform.translationY
        return ImageTransform(
            local.scale * scale,
            (heroFrame.left + local.translationX) * scale + offsetX,
            (heroFrame.top + local.translationY) * scale + offsetY,
        )
    }

    fun localImageTransformForScreen(
        screen: ImageTransform,
        parentFrame: Frame,
        heroFrame: Frame,
        parentTransform: UniformTransform,
    ): ImageTransform? {
        if (!screen.isUsable() || !parentFrame.isUsable() || !heroFrame.isUsable() || !parentTransform.isUsable()) return null
        val scale = parentTransform.scale
        val offsetX = parentFrame.centerX * (1f - scale) + parentTransform.translationX
        val offsetY = parentFrame.centerY * (1f - scale) + parentTransform.translationY
        return ImageTransform(
            screen.scale / scale,
            (screen.translationX - offsetX) / scale - heroFrame.left,
            (screen.translationY - offsetY) / scale - heroFrame.top,
        ).takeIf(ImageTransform::isUsable)
    }

    fun lerp(start: Frame, end: Frame, fraction: Float): Frame {
        val t = fraction.coerceIn(0f, 1f)
        return Frame(lerp(start.left, end.left, t), lerp(start.top, end.top, t), lerp(start.right, end.right, t), lerp(start.bottom, end.bottom, t))
    }

    fun lerp(start: UniformTransform, end: UniformTransform, fraction: Float): UniformTransform {
        val t = fraction.coerceIn(0f, 1f)
        return UniformTransform(lerp(start.scale, end.scale, t), lerp(start.translationX, end.translationX, t), lerp(start.translationY, end.translationY, t))
    }

    fun lerp(start: ImageTransform, end: ImageTransform, fraction: Float): ImageTransform {
        val t = fraction.coerceIn(0f, 1f)
        return ImageTransform(lerp(start.scale, end.scale, t), lerp(start.translationX, end.translationX, t), lerp(start.translationY, end.translationY, t))
    }

    private fun lerp(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction
}

package io.github.azizconi.sharedframe.views

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import io.github.azizconi.sharedframe.core.Frame
import io.github.azizconi.sharedframe.core.ImageTransform
import io.github.azizconi.sharedframe.core.SharedFrameConfig
import io.github.azizconi.sharedframe.core.SharedFrameDismissDirection
import io.github.azizconi.sharedframe.core.SharedFrameGeometry
import io.github.azizconi.sharedframe.core.SharedFrameMath
import io.github.azizconi.sharedframe.core.SharedFramePhase
import io.github.azizconi.sharedframe.core.SharedFrameStateMachine
import io.github.azizconi.sharedframe.core.UniformTransform

data class SharedFrameViewRequest(
    val key: String,
    val sourceHero: ImageView,
    val drawable: Drawable,
    val sourceRadiusPx: Float = 0f,
    val detailRoot: View,
    val detailHero: ImageView,
    val canStartDismiss: (SharedFrameDismissDirection) -> Boolean = { true },
    val onShown: () -> Unit = {},
    val onHidden: () -> Unit = {},
)

internal data class SharedFrameViewRenderSnapshot(
    val transform: UniformTransform,
    val imageLocal: ImageTransform?,
    val parent: Frame?,
    val hero: Frame?,
    val detailImageLocal: ImageTransform?,
    val sourceImageScreen: ImageTransform?,
    val collapsedTransform: UniformTransform?,
)

class SharedFrameViewController(
    private val host: FrameLayout,
    val config: SharedFrameConfig = SharedFrameConfig(),
) {
    private data class ImageTransition(
        val startInScreen: ImageTransform,
        val endInScreen: ImageTransform,
        val parentFrame: Frame,
        val heroFrame: Frame,
    )

    private data class PreparedFrames(
        val parent: Frame,
        val hero: Frame,
        val geometry: SharedFrameGeometry,
        val sourceImageScreen: ImageTransform,
        val detailImageLocal: ImageTransform,
        val intrinsicWidth: Int,
        val intrinsicHeight: Int,
    )

    private data class ClosingFrames(
        val geometry: SharedFrameGeometry,
        val image: ImageTransition,
    )

    private val stateMachine = SharedFrameStateMachine()
    val phase: SharedFramePhase get() = stateMachine.phase

    private val scrim = View(host.context).apply {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
        visibility = View.GONE
    }
    private val overlay = MaskedFrameLayout(host.context).apply {
        setBackgroundColor(Color.WHITE)
        isClickable = true
        visibility = View.GONE
    }
    private var request: SharedFrameViewRequest? = null
    private var preparedFrames: PreparedFrames? = null
    private var animator: ValueAnimator? = null
    private var preDrawObserver: ViewTreeObserver? = null
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var startRunnable: Runnable? = null
    private var handoffRunnable: Runnable? = null

    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var dragDirection: SharedFrameDismissDirection? = null
    private var downWasBlocked = false
    private var lastDragTransform = UniformTransform.Identity
    private var lastTrackedEventTime = Long.MIN_VALUE
    private val touchSlop = ViewConfiguration.get(host.context).scaledTouchSlop.toFloat()
    private val minimumFlingVelocity = config.minimumFlingVelocityDp * host.resources.displayMetrics.density

    init {
        host.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.gestureDelegate = object : MaskedFrameLayout.GestureDelegate {
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean = handleInterceptTouch(event)
            override fun onTouchEvent(event: MotionEvent): Boolean = handleTouch(event)
        }
    }

    fun open(newRequest: SharedFrameViewRequest): Boolean {
        if (!newRequest.sourceHero.isAttachedToWindow || !stateMachine.beginOpen()) return false
        clearPendingOpen()
        preparedFrames = null
        request = newRequest
        attachDetail(newRequest.detailRoot)
        newRequest.detailHero.scaleType = ImageView.ScaleType.CENTER_CROP
        newRequest.detailHero.setImageDrawable(copyDrawable(newRequest.drawable))
        newRequest.sourceHero.alpha = 1f
        overlay.apply { alpha = 1f; visibility = View.VISIBLE }
        scrim.apply { alpha = 0f; visibility = View.VISIBLE }

        val observer = overlay.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            clearPreDraw()
            if (phase != SharedFramePhase.Opening || request !== newRequest) return@OnPreDrawListener true
            val prepared = prepareOpeningFrames(newRequest)
            val image = prepared?.let(::buildOpeningImageTransition)
            if (prepared == null || image == null) {
                showExpandedImmediately()
                return@OnPreDrawListener true
            }
            preparedFrames = prepared
            val calculated = prepared.geometry
            val radius = newRequest.sourceRadiusPx / calculated.collapsedTransform.scale
            newRequest.detailHero.scaleType = ImageView.ScaleType.MATRIX
            applyImageTransition(image, calculated.collapsedTransform, 0f)
            applyFrame(calculated.collapsedTransform, calculated.collapsedMask, radius, 0f)
            newRequest.sourceHero.alpha = 0f
            val runnable = Runnable {
                startRunnable = null
                if (phase != SharedFramePhase.Opening || request !== newRequest || !overlay.isAttachedToWindow) return@Runnable
                animate(
                    calculated.collapsedTransform,
                    UniformTransform.Identity,
                    calculated.collapsedMask,
                    calculated.expandedMask,
                    radius,
                    0f,
                    0f,
                    config.scrimAlpha,
                    image,
                ) {
                    stateMachine.finishOpen()
                    newRequest.onShown()
                }
            }
            startRunnable = runnable
            overlay.postOnAnimation(runnable)
            true
        }
        preDrawObserver = observer
        preDrawListener = listener
        observer.addOnPreDrawListener(listener)
        return true
    }

    fun close(): Boolean {
        val active = request ?: return false
        if (!stateMachine.beginClose()) return false
        if (!sourceIsAvailable(active)) {
            fadeClose()
            return true
        }
        val closing = prepareClosingFrames(active, UniformTransform.Identity)
        if (closing == null) {
            fadeClose()
            return true
        }
        val calculated = closing.geometry
        animate(
            UniformTransform.Identity,
            calculated.collapsedTransform,
            calculated.expandedMask,
            calculated.collapsedMask,
            0f,
            active.sourceRadiusPx / calculated.collapsedTransform.scale,
            config.scrimAlpha,
            0f,
            closing.image,
            restoreHeroOnEnd = false,
            onEnd = ::scheduleSharedCloseHandoff,
        )
        return true
    }

    fun handleBack(): Boolean = if (phase == SharedFramePhase.Idle) close() else phase != SharedFramePhase.Hidden

    internal fun renderSnapshot(): SharedFrameViewRenderSnapshot {
        val prepared = preparedFrames
        return SharedFrameViewRenderSnapshot(
            transform = UniformTransform(overlay.scaleX, overlay.translationX, overlay.translationY),
            imageLocal = request?.detailHero?.imageTransform(),
            parent = prepared?.parent,
            hero = prepared?.hero,
            detailImageLocal = prepared?.detailImageLocal,
            sourceImageScreen = prepared?.sourceImageScreen,
            collapsedTransform = prepared?.geometry?.collapsedTransform,
        )
    }

    fun dispose() {
        clearPendingOpen()
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        velocityTracker?.recycle()
        velocityTracker = null
        activePointerId = MotionEvent.INVALID_POINTER_ID
        dragDirection = null
        request?.sourceHero?.alpha = 1f
        (scrim.parent as? ViewGroup)?.removeView(scrim)
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        stateMachine.forceHidden()
    }

    private fun attachDetail(detailRoot: View) {
        if (detailRoot.parent !== overlay) {
            (detailRoot.parent as? ViewGroup)?.removeView(detailRoot)
            overlay.removeAllViews()
            overlay.addView(detailRoot, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun showExpandedImmediately() {
        clearPendingOpen()
        preparedFrames = null
        val active = request ?: return
        active.sourceHero.alpha = 0f
        active.detailHero.scaleType = ImageView.ScaleType.CENTER_CROP
        overlay.maskFrame = null
        overlay.applyTransform(UniformTransform.Identity)
        scrim.alpha = config.scrimAlpha
        stateMachine.finishOpen()
        active.onShown()
    }

    private fun handleInterceptTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startGesture(event)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (phase != SharedFramePhase.Idle && phase != SharedFramePhase.Dragging) return false
                trackRawMovement(event)
                maybeBeginDrag(event)
                return phase == SharedFramePhase.Dragging
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    if (phase == SharedFramePhase.Dragging) return true
                    resetGesture()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (phase != SharedFramePhase.Dragging) resetGesture()
        }
        return phase == SharedFramePhase.Dragging
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return startGesture(event)
            MotionEvent.ACTION_MOVE -> {
                if (phase != SharedFramePhase.Idle && phase != SharedFramePhase.Dragging) return false
                trackRawMovement(event)
                maybeBeginDrag(event)
                if (phase == SharedFramePhase.Dragging) updateDrag(event)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) finishGesture(event, cancelled = true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finishGesture(event, cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL)
                return true
            }
        }
        return phase == SharedFramePhase.Dragging
    }

    private fun startGesture(event: MotionEvent): Boolean {
        if (phase != SharedFramePhase.Idle) return false
        activePointerId = event.getPointerId(0)
        downX = event.rawX
        downY = event.rawY
        dragX = 0f
        dragY = 0f
        dragDirection = null
        downWasBlocked = false
        lastDragTransform = UniformTransform.Identity
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain()
        lastTrackedEventTime = Long.MIN_VALUE
        trackRawMovement(event)
        return true
    }

    private fun maybeBeginDrag(event: MotionEvent) {
        if (dragDirection != null || activePointerId == MotionEvent.INVALID_POINTER_ID) return
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return
        var totalX = event.rawX - downX
        var totalY = event.rawY - downY
        val active = request ?: return
        val downConfigured = SharedFrameDismissDirection.Down in config.dismissDirections
        val downAllowed = downConfigured && active.canStartDismiss(SharedFrameDismissDirection.Down)
        val downDominant = totalY > 0f && kotlin.math.abs(totalY) > kotlin.math.abs(totalX)
        if (downDominant && !downAllowed) downWasBlocked = true
        if (downDominant && downAllowed && downWasBlocked) {
            downX = event.rawX
            downY = event.rawY
            totalX = 0f
            totalY = 0f
            downWasBlocked = false
            velocityTracker?.recycle()
            velocityTracker = VelocityTracker.obtain()
            lastTrackedEventTime = Long.MIN_VALUE
            trackRawMovement(event)
        }

        val allowed = config.dismissDirections.filterTo(mutableSetOf()) { active.canStartDismiss(it) }
        val resolved = SharedFrameMath.resolveDismissDirection(totalX, totalY, touchSlop, allowed) ?: return
        if (!stateMachine.beginDrag()) return
        dragDirection = resolved
        overlay.maskFrame = (preparedFrames?.geometry?.expandedMask
            ?: Frame(0f, 0f, overlay.width.toFloat(), overlay.height.toFloat())).toRectF()
        overlay.maskRadius = 0f
        scrim.alpha = config.scrimAlpha
    }

    private fun updateDrag(event: MotionEvent) {
        val direction = dragDirection ?: return
        val offset = SharedFrameMath.dragOffsetAfterSlop(event.rawX - downX, event.rawY - downY, touchSlop)
        dragX = offset.x
        dragY = offset.y
        lastDragTransform = SharedFrameMath.dragTransform(
            dragX,
            dragY,
            overlay.width.toFloat(),
            overlay.height.toFloat(),
            direction,
            config.minimumDragScale,
        )
        overlay.applyTransform(lastDragTransform)
    }

    private fun finishGesture(event: MotionEvent, cancelled: Boolean) {
        trackRawMovement(event)
        if (phase == SharedFramePhase.Dragging) {
            if (!cancelled) updateDrag(event)
            velocityTracker?.computeCurrentVelocity(1000)
            val direction = dragDirection
            val finish = !cancelled && direction != null && SharedFrameMath.shouldFinishDismiss(
                dragX,
                dragY,
                velocityTracker?.getXVelocity(activePointerId) ?: 0f,
                velocityTracker?.getYVelocity(activePointerId) ?: 0f,
                overlay.width.toFloat(),
                overlay.height.toFloat(),
                direction,
                minimumFlingVelocity,
                config.dismissDistanceFraction,
            )
            if (finish) finishDrag() else cancelDrag()
        }
        resetGesture()
    }

    private fun trackRawMovement(event: MotionEvent) {
        if (event.eventTime == lastTrackedEventTime) return
        val rawEvent = MotionEvent.obtain(event)
        rawEvent.setLocation(event.rawX, event.rawY)
        velocityTracker?.addMovement(rawEvent)
        rawEvent.recycle()
        lastTrackedEventTime = event.eventTime
    }

    private fun resetGesture() {
        velocityTracker?.recycle()
        velocityTracker = null
        activePointerId = MotionEvent.INVALID_POINTER_ID
        dragDirection = null
        downWasBlocked = false
        lastTrackedEventTime = Long.MIN_VALUE
    }

    private fun cancelDrag() {
        if (!stateMachine.cancelDrag()) return
        val expanded = preparedFrames?.geometry?.expandedMask
            ?: Frame(0f, 0f, overlay.width.toFloat(), overlay.height.toFloat())
        animate(
            lastDragTransform, UniformTransform.Identity,
            expanded, expanded,
            0f, 0f, config.scrimAlpha, config.scrimAlpha,
        ) { stateMachine.finishCancel() }
    }

    private fun finishDrag() {
        val active = request ?: return hide()
        if (!stateMachine.beginClose()) return
        if (!sourceIsAvailable(active)) return fadeClose()
        val current = lastDragTransform
        val closing = prepareClosingFrames(active, current) ?: return fadeClose()
        val calculated = closing.geometry
        animate(
            current, calculated.collapsedTransform,
            calculated.expandedMask, calculated.collapsedMask,
            0f, active.sourceRadiusPx / calculated.collapsedTransform.scale,
            config.scrimAlpha, 0f, closing.image,
            restoreHeroOnEnd = false,
            onEnd = ::scheduleSharedCloseHandoff,
        )
    }

    private fun animate(
        fromTransform: UniformTransform,
        toTransform: UniformTransform,
        fromMask: Frame,
        toMask: Frame,
        fromRadius: Float,
        toRadius: Float,
        fromScrim: Float,
        toScrim: Float,
        image: ImageTransition? = null,
        restoreHeroOnEnd: Boolean = true,
        onEnd: () -> Unit,
    ) {
        animator?.cancel()
        val hero = request?.detailHero
        if (image != null && hero != null) {
            hero.scaleType = ImageView.ScaleType.MATRIX
            applyImageTransition(image, fromTransform, 0f)
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            var wasCancelled = false
            duration = config.durationMillis
            val e = config.easing
            interpolator = PathInterpolator(e.x1, e.y1, e.x2, e.y2)
            addUpdateListener {
                val t = it.animatedFraction
                val transform = SharedFrameMath.lerp(fromTransform, toTransform, t)
                applyFrame(transform, SharedFrameMath.lerp(fromMask, toMask, t), lerp(fromRadius, toRadius, t), lerp(fromScrim, toScrim, t))
                if (image != null) applyImageTransition(image, transform, t)
            }
            doOnCancel { wasCancelled = true }
            doOnEnd {
                if (wasCancelled) return@doOnEnd
                if (restoreHeroOnEnd) hero?.scaleType = ImageView.ScaleType.CENTER_CROP
                animator = null
                onEnd()
            }
            start()
        }
    }

    private fun fadeClose() {
        val startOverlay = overlay.alpha
        val startScrim = scrim.alpha
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            var wasCancelled = false
            duration = config.durationMillis
            addUpdateListener {
                val t = it.animatedFraction
                overlay.alpha = startOverlay * (1f - t)
                scrim.alpha = startScrim * (1f - t)
            }
            doOnCancel { wasCancelled = true }
            doOnEnd {
                if (wasCancelled) return@doOnEnd
                animator = null
                hide()
            }
            start()
        }
    }

    private fun scheduleSharedCloseHandoff() {
        val active = request ?: return hide()
        val runnable = Runnable {
            handoffRunnable = null
            if (request !== active || phase != SharedFramePhase.Closing) return@Runnable
            hide()
        }
        handoffRunnable = runnable
        overlay.postOnAnimation(runnable)
    }

    private fun hide() {
        clearPendingOpen()
        resetGesture()
        val active = request
        active?.detailHero?.scaleType = ImageView.ScaleType.CENTER_CROP
        overlay.apply { visibility = View.GONE; alpha = 1f; maskFrame = null; applyTransform(UniformTransform.Identity) }
        scrim.apply { visibility = View.GONE; alpha = 0f }
        active?.sourceHero?.alpha = 1f
        request = null
        preparedFrames = null
        stateMachine.finishClose()
        active?.onHidden?.invoke()
    }

    private fun buildOpeningImageTransition(frames: PreparedFrames): ImageTransition? {
        val detailScreen = SharedFrameMath.imageTransformInScreen(
            frames.detailImageLocal,
            frames.parent,
            frames.hero,
            UniformTransform.Identity,
        ) ?: return null
        return ImageTransition(frames.sourceImageScreen, detailScreen, frames.parent, frames.hero)
    }

    private fun prepareOpeningFrames(active: SharedFrameViewRequest): PreparedFrames? {
        val sourceDrawable = active.sourceHero.drawable ?: return null
        val detailDrawable = active.detailHero.drawable ?: return null
        if (sourceDrawable.intrinsicWidth != detailDrawable.intrinsicWidth || sourceDrawable.intrinsicHeight != detailDrawable.intrinsicHeight) return null
        val sourceLocal = active.sourceHero.imageTransform() ?: return null
        val detailLocal = active.detailHero.imageTransform() ?: return null
        val sourceFrame = active.sourceHero.frameRelativeTo(host)
        val parent = overlay.layoutFrameInHost()
        val hero = active.detailHero.frameRelativeTo(host)
        val geometry = SharedFrameMath.buildGeometry(sourceFrame, parent, hero) ?: return null
        return PreparedFrames(
            parent = parent,
            hero = hero,
            geometry = geometry,
            sourceImageScreen = ImageTransform(
                sourceLocal.scale,
                sourceFrame.left + sourceLocal.translationX,
                sourceFrame.top + sourceLocal.translationY,
            ),
            detailImageLocal = detailLocal,
            intrinsicWidth = detailDrawable.intrinsicWidth,
            intrinsicHeight = detailDrawable.intrinsicHeight,
        )
    }

    private fun prepareClosingFrames(
        active: SharedFrameViewRequest,
        parentTransform: UniformTransform,
    ): ClosingFrames? {
        val baseline = preparedFrames ?: return null
        if (!active.detailHero.isAttachedToWindow ||
            active.detailHero.width.toFloat() != baseline.hero.width ||
            active.detailHero.height.toFloat() != baseline.hero.height ||
            !overlay.layoutFrameInHost().approximatelyEquals(baseline.parent)
        ) return null

        val sourceDrawable = active.sourceHero.drawable ?: return null
        if (sourceDrawable.intrinsicWidth != baseline.intrinsicWidth ||
            sourceDrawable.intrinsicHeight != baseline.intrinsicHeight
        ) return null
        val sourceLocal = active.sourceHero.imageTransform() ?: return null
        val sourceFrame = active.sourceHero.frameRelativeTo(host)
        val geometry = SharedFrameMath.buildGeometry(sourceFrame, baseline.parent, baseline.hero) ?: return null
        val sourceImageScreen = ImageTransform(
            sourceLocal.scale,
            sourceFrame.left + sourceLocal.translationX,
            sourceFrame.top + sourceLocal.translationY,
        )
        val detailImageScreen = SharedFrameMath.imageTransformInScreen(
            baseline.detailImageLocal,
            baseline.parent,
            baseline.hero,
            parentTransform,
        ) ?: return null
        return ClosingFrames(
            geometry,
            ImageTransition(detailImageScreen, sourceImageScreen, baseline.parent, baseline.hero),
        )
    }

    private fun applyImageTransition(transition: ImageTransition, parentTransform: UniformTransform, fraction: Float) {
        val desired = SharedFrameMath.lerp(transition.startInScreen, transition.endInScreen, fraction)
        val local = SharedFrameMath.localImageTransformForScreen(desired, transition.parentFrame, transition.heroFrame, parentTransform) ?: return
        request?.detailHero?.imageMatrix = Matrix().apply {
            setValues(floatArrayOf(local.scale, 0f, local.translationX, 0f, local.scale, local.translationY, 0f, 0f, 1f))
        }
    }

    private fun applyFrame(transform: UniformTransform, mask: Frame, radius: Float, scrimAlpha: Float) {
        overlay.applyTransform(transform)
        overlay.maskFrame = mask.toRectF()
        overlay.maskRadius = radius
        scrim.alpha = scrimAlpha
    }

    private fun clearPreDraw() {
        val listener = preDrawListener ?: return
        val observer = preDrawObserver
        if (observer?.isAlive == true) observer.removeOnPreDrawListener(listener)
        preDrawObserver = null; preDrawListener = null
    }

    private fun clearPendingOpen() {
        clearPreDraw()
        startRunnable?.let(overlay::removeCallbacks)
        startRunnable = null
        handoffRunnable?.let(overlay::removeCallbacks)
        handoffRunnable = null
    }

    private fun copyDrawable(drawable: Drawable): Drawable =
        drawable.constantState?.newDrawable(host.resources)?.mutate() ?: drawable

    private fun View.frameRelativeTo(root: View): Frame {
        val rootLocation = IntArray(2); val viewLocation = IntArray(2)
        root.getLocationInWindow(rootLocation); getLocationInWindow(viewLocation)
        val left = (viewLocation[0] - rootLocation[0]).toFloat()
        val top = (viewLocation[1] - rootLocation[1]).toFloat()
        return Frame(left, top, left + width, top + height)
    }

    private fun View.layoutFrameInHost(): Frame =
        Frame(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    private fun Frame.approximatelyEquals(other: Frame, tolerance: Float = .5f): Boolean =
        kotlin.math.abs(left - other.left) <= tolerance &&
            kotlin.math.abs(top - other.top) <= tolerance &&
            kotlin.math.abs(right - other.right) <= tolerance &&
            kotlin.math.abs(bottom - other.bottom) <= tolerance

    private fun ImageView.imageTransform(): ImageTransform? {
        val values = FloatArray(9); imageMatrix.getValues(values)
        return ImageTransform(values[Matrix.MSCALE_X], values[Matrix.MTRANS_X], values[Matrix.MTRANS_Y]).takeIf(ImageTransform::isUsable)
    }

    private fun View.applyTransform(transform: UniformTransform) {
        pivotX = width / 2f; pivotY = height / 2f
        scaleX = transform.scale; scaleY = transform.scale
        translationX = transform.translationX; translationY = transform.translationY
    }

    private fun sourceIsAvailable(active: SharedFrameViewRequest): Boolean {
        if (!active.sourceHero.isAttachedToWindow || active.sourceHero.visibility != View.VISIBLE) return false
        val source = active.sourceHero.frameRelativeTo(host)
        val hostFrame = Frame(0f, 0f, host.width.toFloat(), host.height.toFloat())
        return source.isUsable() && hostFrame.isUsable() && source.intersects(hostFrame)
    }

    private fun Frame.intersects(other: Frame): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    private fun Frame.toRectF() = RectF(left, top, right, bottom)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

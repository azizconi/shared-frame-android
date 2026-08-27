package io.github.azizconi.sharedframe.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.azizconi.sharedframe.core.Frame
import io.github.azizconi.sharedframe.core.ImageTransform
import io.github.azizconi.sharedframe.core.SharedFrameConfig
import io.github.azizconi.sharedframe.core.SharedFrameDismissDirection
import io.github.azizconi.sharedframe.core.SharedFrameGeometry
import io.github.azizconi.sharedframe.core.SharedFrameMath
import io.github.azizconi.sharedframe.core.SharedFramePhase
import io.github.azizconi.sharedframe.core.SharedFrameStateMachine
import io.github.azizconi.sharedframe.core.UniformTransform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.abs

internal data class SourceRegistration(
    val token: Long,
    val key: String,
    val painter: Painter,
    val contentScale: ContentScale,
    val cornerRadius: Dp,
    val coordinates: LayoutCoordinates,
)

internal data class DetailRegistration(
    val token: Long,
    val sessionId: Long,
    val contentScale: ContentScale,
    val coordinates: LayoutCoordinates,
)

internal data class TransitionSession(
    val id: Long,
    val key: String,
    val painter: Painter,
    val openingSource: SourceRegistration,
)

internal sealed interface Operation {
    val id: Long
    val sessionId: Long

    data class Open(override val id: Long, override val sessionId: Long) : Operation
    data class Close(
        override val id: Long,
        override val sessionId: Long,
        val fromTransform: UniformTransform,
    ) : Operation
    data class CancelDrag(
        override val id: Long,
        override val sessionId: Long,
        val fromTransform: UniformTransform,
    ) : Operation
}

internal data class RenderState(
    val prepared: Boolean = false,
    val transform: UniformTransform = UniformTransform.Identity,
    val mask: Frame? = null,
    val image: ImageTransform? = null,
    val radius: Float = 0f,
    val scrimAlpha: Float = 0f,
    val fallbackAlpha: Float? = null,
    val hiddenSourceToken: Long? = null,
)

private data class ControllerState(
    val phase: SharedFramePhase = SharedFramePhase.Hidden,
    val active: TransitionSession? = null,
    val operation: Operation? = null,
    val render: RenderState = RenderState(),
    val frames: PreparedFrames? = null,
)

internal data class PreparedFrames(
    val source: SourceRegistration,
    val sourceFrame: Frame,
    val parent: Frame,
    val hero: Frame,
    val geometry: SharedFrameGeometry,
    val sourceImageScreen: ImageTransform,
    val detailImageLocal: ImageTransform,
)

@Stable
class SharedFrameComposeController internal constructor(
    val config: SharedFrameConfig,
) {
    private val machine = SharedFrameStateMachine()
    private val sources = mutableStateMapOf<String, SourceRegistration>()
    private var nextToken = 0L
    private var nextOperation = 0L

    private var state by mutableStateOf(ControllerState())
    private var hostCoordinates by mutableStateOf<LayoutCoordinates?>(null)
    private var detailRegistration by mutableStateOf<DetailRegistration?>(null)

    val phase: SharedFramePhase get() = state.phase
    val activeKey: String? get() = state.active?.key

    fun open(key: String): Boolean {
        val source = sources[key]?.takeIf { it.coordinates.isAttached } ?: return false
        if (!machine.beginOpen()) return false
        val sessionId = ++nextOperation
        val active = TransitionSession(sessionId, key, source.painter, source)
        state = ControllerState(
            phase = SharedFramePhase.Opening,
            active = active,
            operation = Operation.Open(++nextOperation, sessionId),
            render = RenderState(),
        )
        detailRegistration = null
        return true
    }

    fun close(): Boolean {
        val active = state.active ?: return false
        if (!machine.beginClose()) return false
        state = state.copy(
            phase = SharedFramePhase.Closing,
            operation = Operation.Close(++nextOperation, active.id, state.render.transform),
        )
        return true
    }

    internal fun beginDrag(): Boolean {
        if (!machine.beginDrag()) return false
        state = state.copy(phase = SharedFramePhase.Dragging, operation = null)
        return true
    }

    internal fun updateDrag(
        totalX: Float,
        totalY: Float,
        direction: SharedFrameDismissDirection,
    ) {
        if (state.phase != SharedFramePhase.Dragging) return
        val parent = currentHostFrame() ?: return
        state = state.copy(
            render = state.render.copy(
                prepared = true,
                transform = SharedFrameMath.dragTransform(
                    totalX,
                    totalY,
                    parent.width,
                    parent.height,
                    direction,
                    config.minimumDragScale,
                ),
                mask = parent,
                image = null,
                radius = 0f,
                scrimAlpha = config.scrimAlpha,
                fallbackAlpha = null,
            )
        )
    }

    internal fun cancelDrag() {
        val active = state.active ?: return
        if (!machine.cancelDrag()) return
        state = state.copy(
            phase = SharedFramePhase.CancellingDrag,
            operation = Operation.CancelDrag(++nextOperation, active.id, state.render.transform),
        )
    }

    internal fun finishDrag() {
        val active = state.active ?: return
        if (!machine.beginClose()) return
        state = state.copy(
            phase = SharedFramePhase.Closing,
            operation = Operation.Close(++nextOperation, active.id, state.render.transform),
        )
    }

    internal fun registerSource(registration: SourceRegistration) {
        sources[registration.key] = registration
    }

    internal fun unregisterSource(key: String, token: Long) {
        if (sources[key]?.token == token) sources.remove(key)
    }

    internal fun registerDetail(registration: DetailRegistration) {
        if (state.active?.id == registration.sessionId) detailRegistration = registration
    }

    internal fun unregisterDetail(token: Long) {
        if (detailRegistration?.token == token) detailRegistration = null
    }

    internal fun registerHost(coordinates: LayoutCoordinates) {
        hostCoordinates = coordinates
    }

    internal fun sourceIsHidden(token: Long): Boolean = state.render.hiddenSourceToken == token
    internal fun renderState(): RenderState = state.render
    internal fun preparedFrames(): PreparedFrames? = state.frames
    internal fun activeSession(): TransitionSession? = state.active
    internal fun operation(): Operation? = state.operation
    internal fun newRegistrationToken(): Long = ++nextToken

    internal fun transitionPainter(sessionId: Long): Pair<Painter, ImageTransform>? {
        val active = state.active ?: return null
        val image = state.render.image ?: return null
        return if (active.id == sessionId) active.painter to image else null
    }

    internal suspend fun awaitReady(sessionId: Long): Pair<LayoutCoordinates, DetailRegistration> =
        snapshotFlow {
            val host = hostCoordinates
            val detail = detailRegistration
            if (host?.isAttached == true && detail?.coordinates?.isAttached == true && detail.sessionId == sessionId) {
                host to detail
            } else {
                null
            }
        }.filterNotNull().first()

    internal fun currentSource(key: String): SourceRegistration? =
        sources[key]?.takeIf { it.coordinates.isAttached }

    internal fun isCurrentOperation(id: Long): Boolean = state.operation?.id == id

    internal fun installOpeningFrame(
        operationId: Long,
        prepared: PreparedFrames,
        radius: Float,
        image: ImageTransform,
    ): Boolean {
        if (!isCurrentOperation(operationId)) return false
        state = state.copy(
            render = RenderState(
                prepared = true,
                transform = prepared.geometry.collapsedTransform,
                mask = prepared.geometry.collapsedMask,
                image = image,
                radius = radius,
                scrimAlpha = 0f,
                fallbackAlpha = null,
                hiddenSourceToken = prepared.source.token,
            ),
            frames = prepared,
        )
        return true
    }

    internal fun updateAnimation(operationId: Long, render: RenderState): Boolean {
        if (!isCurrentOperation(operationId)) return false
        state = state.copy(render = render)
        return true
    }

    internal fun finishOpen(operationId: Long, parent: Frame) {
        if (!isCurrentOperation(operationId) || !machine.finishOpen()) return
        state = state.copy(
            phase = SharedFramePhase.Idle,
            operation = null,
            render = state.render.copy(
                prepared = true,
                transform = UniformTransform.Identity,
                mask = parent,
                image = null,
                radius = 0f,
                scrimAlpha = config.scrimAlpha,
                fallbackAlpha = null,
            ),
        )
    }

    internal fun showExpandedImmediately(operationId: Long, parent: Frame?) {
        if (!isCurrentOperation(operationId) || !machine.finishOpen()) return
        val active = state.active ?: return forceHidden()
        state = state.copy(
            phase = SharedFramePhase.Idle,
            operation = null,
            render = RenderState(
                prepared = true,
                transform = UniformTransform.Identity,
                mask = parent,
                scrimAlpha = config.scrimAlpha,
                fallbackAlpha = null,
                hiddenSourceToken = active.openingSource.token,
            ),
            frames = null,
        )
    }

    internal fun finishCancel(operationId: Long) {
        if (!isCurrentOperation(operationId) || !machine.finishCancel()) return
        state = state.copy(
            phase = SharedFramePhase.Idle,
            operation = null,
            render = state.render.copy(
                transform = UniformTransform.Identity,
                image = null,
                radius = 0f,
                scrimAlpha = config.scrimAlpha,
                fallbackAlpha = null,
            ),
        )
    }

    internal fun finishClose(operationId: Long) {
        if (!isCurrentOperation(operationId) || !machine.finishClose()) return
        state = ControllerState()
        detailRegistration = null
    }

    internal fun forceHidden() {
        machine.forceHidden()
        state = ControllerState()
        detailRegistration = null
    }

    internal fun disposeHost() {
        forceHidden()
        hostCoordinates = null
        detailRegistration = null
        sources.clear()
    }

    private fun currentHostFrame(): Frame? {
        val coordinates = hostCoordinates ?: return null
        if (!coordinates.isAttached) return null
        val size = coordinates.size
        return Frame(0f, 0f, size.width.toFloat(), size.height.toFloat()).takeIf(Frame::isUsable)
    }
}

@Composable
fun rememberSharedFrameController(
    config: SharedFrameConfig = SharedFrameConfig(),
): SharedFrameComposeController = remember(config) { SharedFrameComposeController(config) }

fun Modifier.sharedFrameSource(
    controller: SharedFrameComposeController,
    key: String,
    painter: Painter,
    contentScale: ContentScale,
    cornerRadius: Dp = 0.dp,
): Modifier {
    requireSupported(contentScale)
    return this.then(SourceElement(controller, key, painter, contentScale, cornerRadius))
}

class SharedFrameDetailScope internal constructor(
    private val controller: SharedFrameComposeController,
    private val sessionId: Long,
    val key: String,
    val painter: Painter,
) {
    fun Modifier.sharedFrameDetailHero(contentScale: ContentScale): Modifier {
        requireSupported(contentScale)
        return this.then(DetailElement(controller, sessionId, contentScale))
    }
}

@Composable
fun SharedFrameHost(
    controller: SharedFrameComposeController,
    modifier: Modifier = Modifier,
    canStartDismiss: (SharedFrameDismissDirection) -> Boolean = { true },
    detailContent: @Composable SharedFrameDetailScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val active = controller.activeSession()
    val operation = controller.operation()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val currentCanStartDismiss = rememberUpdatedState(canStartDismiss)

    DisposableEffect(controller) {
        onDispose { controller.disposeHost() }
    }

    BackHandler(enabled = controller.phase != SharedFramePhase.Hidden) {
        if (controller.phase == SharedFramePhase.Idle) controller.close()
    }

    LaunchedEffect(operation?.id) {
        val current = operation ?: return@LaunchedEffect
        try {
            when (current) {
                is Operation.Open -> runOpening(controller, current, density)
                is Operation.Close -> runClosing(controller, current, density)
                is Operation.CancelDrag -> runCancel(controller, current)
            }
        } catch (cancelled: CancellationException) {
            if (controller.isCurrentOperation(current.id)) controller.forceHidden()
            throw cancelled
        }
    }

    Box(modifier.onGloballyPositioned(controller::registerHost)) {
        content()
        if (active != null && controller.phase != SharedFramePhase.Hidden) {
            Box(
                Modifier
                    .fillMaxSize()
                    .sharedFrameDrag(controller, active.id, currentCanStartDismiss)
            ) {
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        drawRect(Color.Black.copy(alpha = controller.renderState().scrimAlpha))
                    }
                )
                val detailModifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val render = controller.renderState()
                        val transform = render.transform
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.translationX
                        translationY = transform.translationY
                        transformOrigin = TransformOrigin.Center
                        alpha = render.fallbackAlpha ?: 1f
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawPreparedDetailWithSharedFrameMask(controller)

                Box(detailModifier) {
                    SharedFrameDetailScope(controller, active.id, active.key, active.painter).detailContent()
                }
            }
        }
    }
}

private fun Modifier.drawPreparedDetailWithSharedFrameMask(controller: SharedFrameComposeController): Modifier =
    drawWithContent {
        val render = controller.renderState()
        if (!render.prepared) return@drawWithContent
        val mask = render.mask
        if (mask == null) {
            drawContent()
        } else {
            val path = Path().apply {
                addRoundRect(RoundRect(Rect(mask.left, mask.top, mask.right, mask.bottom), render.radius, render.radius))
            }
            clipPath(path) { this@drawWithContent.drawContent() }
        }
    }

private fun Modifier.sharedFrameDrag(
    controller: SharedFrameComposeController,
    sessionId: Long?,
    canStartDismiss: State<(SharedFrameDismissDirection) -> Boolean>,
): Modifier = pointerInput(controller, sessionId) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (sessionId == null || controller.phase != SharedFramePhase.Idle) return@awaitEachGesture

        val pointerId: PointerId = down.id
        var start = down.position
        val tracker = VelocityTracker().also { it.addPosition(down.uptimeMillis, down.position) }
        var direction: SharedFrameDismissDirection? = null
        var dragging = false
        var downWasBlocked = false

        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                var total = change.position - start

                if (direction == null) {
                    val downConfigured = SharedFrameDismissDirection.Down in controller.config.dismissDirections
                    val downAllowed = downConfigured && canStartDismiss.value(SharedFrameDismissDirection.Down)
                    val downDominant = total.y > 0f && abs(total.y) > abs(total.x)
                    if (downDominant && !downAllowed) downWasBlocked = true
                    if (downDominant && downAllowed && downWasBlocked) {
                        start = change.position
                        total = Offset.Zero
                        downWasBlocked = false
                    }

                    val allowed = controller.config.dismissDirections.filterTo(mutableSetOf()) {
                        canStartDismiss.value(it)
                    }
                    direction = SharedFrameMath.resolveDismissDirection(
                        total.x,
                        total.y,
                        viewConfiguration.touchSlop,
                        allowed,
                    )
                    if (direction != null) dragging = controller.beginDrag()
                }

                val lockedDirection = direction
                if (lockedDirection != null && dragging) {
                    val effective = SharedFrameMath.dragOffsetAfterSlop(
                        total.x,
                        total.y,
                        viewConfiguration.touchSlop,
                    )
                    change.consume()
                    controller.updateDrag(effective.x, effective.y, lockedDirection)

                    if (!change.pressed && controller.phase == SharedFramePhase.Dragging) {
                        val velocity = tracker.calculateVelocity()
                        val finish = SharedFrameMath.shouldFinishDismiss(
                            effective.x,
                            effective.y,
                            velocity.x,
                            velocity.y,
                            size.width.toFloat(),
                            size.height.toFloat(),
                            lockedDirection,
                            controller.config.minimumFlingVelocityDp * density,
                            controller.config.dismissDistanceFraction,
                        )
                        if (finish) controller.finishDrag() else controller.cancelDrag()
                        dragging = false
                    }
                }

                if (!change.pressed) break
            }
        } finally {
            if (dragging && controller.phase == SharedFramePhase.Dragging) controller.cancelDrag()
        }
    }
}

private suspend fun runOpening(
    controller: SharedFrameComposeController,
    operation: Operation.Open,
    density: Density,
) {
    val active = controller.activeSession()?.takeIf { it.id == operation.sessionId }
        ?: return controller.forceHidden()
    val (hostCoordinates, detail) = controller.awaitReady(operation.sessionId)
    if (!controller.isCurrentOperation(operation.id)) return

    val prepared = prepareFrames(hostCoordinates, detail, active.openingSource)
    if (prepared == null) {
        controller.showExpandedImmediately(operation.id, hostCoordinates.hostFrame())
        return
    }

    val collapsed = prepared.geometry.collapsedTransform
    val collapsedRadius = prepared.source.cornerRadius.toPx(density) / collapsed.scale
    val openingImage = SharedFrameMath.localImageTransformForScreen(
        prepared.sourceImageScreen,
        prepared.parent,
        prepared.hero,
        collapsed,
    ) ?: return controller.showExpandedImmediately(operation.id, prepared.parent)

    if (!controller.installOpeningFrame(operation.id, prepared, collapsedRadius, openingImage)) return
    withFrameNanos { }
    withFrameNanos { }
    if (!controller.isCurrentOperation(operation.id)) return

    val detailImageScreen = SharedFrameMath.imageTransformInScreen(
        prepared.detailImageLocal,
        prepared.parent,
        prepared.hero,
        UniformTransform.Identity,
    ) ?: return controller.showExpandedImmediately(operation.id, prepared.parent)
    val easing = controller.config.easing
    Animatable(0f).animateTo(
        1f,
        tween(
            durationMillis = controller.config.durationMillis.toInt(),
            easing = CubicBezierEasing(easing.x1, easing.y1, easing.x2, easing.y2),
        ),
    ) {
        val transform = SharedFrameMath.lerp(collapsed, UniformTransform.Identity, value)
        val desiredImage = SharedFrameMath.lerp(prepared.sourceImageScreen, detailImageScreen, value)
        val localImage = SharedFrameMath.localImageTransformForScreen(
            desiredImage,
            prepared.parent,
            prepared.hero,
            transform,
        ) ?: return@animateTo

        controller.updateAnimation(
            operation.id,
            RenderState(
                prepared = true,
                transform = transform,
                mask = SharedFrameMath.lerp(prepared.geometry.collapsedMask, prepared.geometry.expandedMask, value),
                image = localImage,
                radius = lerp(collapsedRadius, 0f, value),
                scrimAlpha = lerp(0f, controller.config.scrimAlpha, value),
                fallbackAlpha = null,
                hiddenSourceToken = prepared.source.token,
            ),
        )
    }
    controller.finishOpen(operation.id, prepared.parent)
}

private suspend fun runClosing(
    controller: SharedFrameComposeController,
    operation: Operation.Close,
    density: Density,
) {
    val active = controller.activeSession()?.takeIf { it.id == operation.sessionId }
        ?: return controller.forceHidden()
    val (hostCoordinates, detail) = controller.awaitReady(operation.sessionId)
    if (!controller.isCurrentOperation(operation.id)) return

    val baseline = controller.preparedFrames()
    val source = controller.currentSource(active.key)
    val prepared = if (baseline != null && detail.coordinates.isAttached) {
        source?.let { prepareClosingFrames(hostCoordinates, baseline, it) }
    } else {
        null
    }
    if (prepared == null || !prepared.sourceFrame.intersects(prepared.parent)) {
        runFadeClose(controller, operation)
        return
    }

    val fromTransform = operation.fromTransform
    val startImageScreen = SharedFrameMath.imageTransformInScreen(
        prepared.detailImageLocal,
        prepared.parent,
        prepared.hero,
        fromTransform,
    ) ?: return runFadeClose(controller, operation)
    val startImageLocal = SharedFrameMath.localImageTransformForScreen(
        startImageScreen,
        prepared.parent,
        prepared.hero,
        fromTransform,
    ) ?: return runFadeClose(controller, operation)
    val collapsedRadius = prepared.source.cornerRadius.toPx(density) / prepared.geometry.collapsedTransform.scale
    val startMask = prepared.parent

    if (!controller.updateAnimation(
            operation.id,
            RenderState(
                prepared = true,
                transform = fromTransform,
                mask = startMask,
                image = startImageLocal,
                radius = 0f,
                scrimAlpha = controller.config.scrimAlpha,
                fallbackAlpha = null,
                hiddenSourceToken = prepared.source.token,
            ),
        )
    ) return

    withFrameNanos { }
    withFrameNanos { }
    val easing = controller.config.easing
    Animatable(0f).animateTo(
        1f,
        tween(
            durationMillis = controller.config.durationMillis.toInt(),
            easing = CubicBezierEasing(easing.x1, easing.y1, easing.x2, easing.y2),
        ),
    ) {
        val transform = SharedFrameMath.lerp(fromTransform, prepared.geometry.collapsedTransform, value)
        val desiredImage = SharedFrameMath.lerp(startImageScreen, prepared.sourceImageScreen, value)
        val localImage = SharedFrameMath.localImageTransformForScreen(
            desiredImage,
            prepared.parent,
            prepared.hero,
            transform,
        ) ?: return@animateTo
        controller.updateAnimation(
            operation.id,
            RenderState(
                prepared = true,
                transform = transform,
                mask = SharedFrameMath.lerp(startMask, prepared.geometry.collapsedMask, value),
                image = localImage,
                radius = lerp(0f, collapsedRadius, value),
                scrimAlpha = lerp(controller.config.scrimAlpha, 0f, value),
                fallbackAlpha = null,
                hiddenSourceToken = prepared.source.token,
            ),
        )
    }
    withFrameNanos { }
    if (!controller.isCurrentOperation(operation.id)) return
    controller.finishClose(operation.id)
}

private suspend fun runFadeClose(
    controller: SharedFrameComposeController,
    operation: Operation.Close,
) {
    val start = controller.renderState()
    controller.updateAnimation(
        operation.id,
        start.copy(prepared = true, image = null, fallbackAlpha = 1f, hiddenSourceToken = null),
    )
    val easing = controller.config.easing
    Animatable(0f).animateTo(
        1f,
        tween(
            durationMillis = controller.config.durationMillis.toInt(),
            easing = CubicBezierEasing(easing.x1, easing.y1, easing.x2, easing.y2),
        ),
    ) {
        controller.updateAnimation(
            operation.id,
            start.copy(
                prepared = true,
                image = null,
                fallbackAlpha = 1f - value,
                scrimAlpha = start.scrimAlpha * (1f - value),
                hiddenSourceToken = null,
            ),
        )
    }
    controller.finishClose(operation.id)
}

private suspend fun runCancel(
    controller: SharedFrameComposeController,
    operation: Operation.CancelDrag,
) {
    val start = controller.renderState()
    val easing = controller.config.easing
    Animatable(0f).animateTo(
        1f,
        tween(
            durationMillis = controller.config.durationMillis.toInt(),
            easing = CubicBezierEasing(easing.x1, easing.y1, easing.x2, easing.y2),
        ),
    ) {
        controller.updateAnimation(
            operation.id,
            start.copy(
                transform = SharedFrameMath.lerp(operation.fromTransform, UniformTransform.Identity, value),
                image = null,
                radius = 0f,
                scrimAlpha = controller.config.scrimAlpha,
                fallbackAlpha = null,
            ),
        )
    }
    controller.finishCancel(operation.id)
}

private fun prepareFrames(
    hostCoordinates: LayoutCoordinates,
    detail: DetailRegistration,
    source: SourceRegistration,
): PreparedFrames? {
    if (!hostCoordinates.isAttached || !detail.coordinates.isAttached || !source.coordinates.isAttached) return null
    val parent = hostCoordinates.hostFrame() ?: return null
    val sourceFrame = hostCoordinates.localBoundingBoxOf(source.coordinates, clipBounds = false).toCoreFrame()
    val heroFrame = hostCoordinates.localBoundingBoxOf(detail.coordinates, clipBounds = false).toCoreFrame()
    val geometry = SharedFrameMath.buildGeometry(sourceFrame, parent, heroFrame) ?: return null
    val intrinsic = source.painter.intrinsicSize
    if (!intrinsic.width.isFinite() || !intrinsic.height.isFinite() || intrinsic.width <= 0f || intrinsic.height <= 0f) return null

    val sourceLocal = imageTransform(source.contentScale, intrinsic.width, intrinsic.height, sourceFrame.width, sourceFrame.height)
        ?: return null
    val detailLocal = imageTransform(detail.contentScale, intrinsic.width, intrinsic.height, heroFrame.width, heroFrame.height)
        ?: return null
    val sourceScreen = ImageTransform(
        sourceLocal.scale,
        sourceFrame.left + sourceLocal.translationX,
        sourceFrame.top + sourceLocal.translationY,
    )
    return PreparedFrames(source, sourceFrame, parent, heroFrame, geometry, sourceScreen, detailLocal)
}

private fun prepareClosingFrames(
    hostCoordinates: LayoutCoordinates,
    baseline: PreparedFrames,
    source: SourceRegistration,
): PreparedFrames? {
    if (!hostCoordinates.isAttached || !source.coordinates.isAttached) return null
    val parent = hostCoordinates.hostFrame() ?: return null
    if (!parent.approximatelyEquals(baseline.parent)) return null

    val sourceFrame = hostCoordinates.localBoundingBoxOf(source.coordinates, clipBounds = false).toCoreFrame()
    val geometry = SharedFrameMath.buildGeometry(sourceFrame, baseline.parent, baseline.hero) ?: return null
    val baselineIntrinsic = baseline.source.painter.intrinsicSize
    val currentIntrinsic = source.painter.intrinsicSize
    if (!baselineIntrinsic.width.isFinite() || !baselineIntrinsic.height.isFinite() ||
        baselineIntrinsic.width <= 0f || baselineIntrinsic.height <= 0f ||
        !currentIntrinsic.width.isFinite() || !currentIntrinsic.height.isFinite() ||
        currentIntrinsic.width <= 0f || currentIntrinsic.height <= 0f ||
        kotlin.math.abs(baselineIntrinsic.width - currentIntrinsic.width) > .5f ||
        kotlin.math.abs(baselineIntrinsic.height - currentIntrinsic.height) > .5f
    ) return null

    val sourceLocal = imageTransform(
        source.contentScale,
        baselineIntrinsic.width,
        baselineIntrinsic.height,
        sourceFrame.width,
        sourceFrame.height,
    ) ?: return null
    val sourceScreen = ImageTransform(
        sourceLocal.scale,
        sourceFrame.left + sourceLocal.translationX,
        sourceFrame.top + sourceLocal.translationY,
    )
    return PreparedFrames(
        source = source,
        sourceFrame = sourceFrame,
        parent = baseline.parent,
        hero = baseline.hero,
        geometry = geometry,
        sourceImageScreen = sourceScreen,
        detailImageLocal = baseline.detailImageLocal,
    )
}

private fun imageTransform(
    contentScale: ContentScale,
    contentWidth: Float,
    contentHeight: Float,
    containerWidth: Float,
    containerHeight: Float,
): ImageTransform? = when (contentScale) {
    ContentScale.Crop -> SharedFrameMath.centerCropTransform(contentWidth, contentHeight, containerWidth, containerHeight)
    ContentScale.Fit -> SharedFrameMath.centerFitTransform(contentWidth, contentHeight, containerWidth, containerHeight)
    else -> null
}

private data class SourceElement(
    val controller: SharedFrameComposeController,
    val key: String,
    val painter: Painter,
    val contentScale: ContentScale,
    val cornerRadius: Dp,
) : ModifierNodeElement<SourceNode>() {
    override fun create() = SourceNode(controller, key, painter, contentScale, cornerRadius)
    override fun update(node: SourceNode) = node.update(controller, key, painter, contentScale, cornerRadius)
    override fun InspectorInfo.inspectableProperties() {
        name = "sharedFrameSource"
        properties["key"] = key
        properties["contentScale"] = contentScale
        properties["cornerRadius"] = cornerRadius
    }
}

private class SourceNode(
    private var controller: SharedFrameComposeController,
    private var key: String,
    private var painter: Painter,
    private var contentScale: ContentScale,
    private var cornerRadius: Dp,
) : Modifier.Node(), GlobalPositionAwareModifierNode, DrawModifierNode {
    private var coordinates: LayoutCoordinates? = null
    private var token: Long = controller.newRegistrationToken()

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        register()
    }

    override fun ContentDrawScope.draw() {
        if (!controller.sourceIsHidden(token)) drawContent()
    }

    override fun onDetach() {
        controller.unregisterSource(key, token)
        coordinates = null
    }

    fun update(
        controller: SharedFrameComposeController,
        key: String,
        painter: Painter,
        contentScale: ContentScale,
        cornerRadius: Dp,
    ) {
        val identityChanged = this.controller !== controller || this.key != key
        if (identityChanged) {
            this.controller.unregisterSource(this.key, token)
            token = controller.newRegistrationToken()
        }
        this.controller = controller
        this.key = key
        this.painter = painter
        this.contentScale = contentScale
        this.cornerRadius = cornerRadius
        register()
    }

    private fun register() {
        val coordinates = coordinates ?: return
        if (!coordinates.isAttached) return
        controller.registerSource(SourceRegistration(token, key, painter, contentScale, cornerRadius, coordinates))
    }
}

private data class DetailElement(
    val controller: SharedFrameComposeController,
    val sessionId: Long,
    val contentScale: ContentScale,
) : ModifierNodeElement<DetailNode>() {
    override fun create() = DetailNode(controller, sessionId, contentScale)
    override fun update(node: DetailNode) = node.update(controller, sessionId, contentScale)
    override fun InspectorInfo.inspectableProperties() {
        name = "sharedFrameDetailHero"
        properties["contentScale"] = contentScale
    }
}

private class DetailNode(
    private var controller: SharedFrameComposeController,
    private var sessionId: Long,
    private var contentScale: ContentScale,
) : Modifier.Node(), GlobalPositionAwareModifierNode, DrawModifierNode {
    private var coordinates: LayoutCoordinates? = null
    private var token: Long = controller.newRegistrationToken()

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        register()
    }

    override fun ContentDrawScope.draw() {
        val transition = controller.transitionPainter(sessionId)
        if (transition == null) {
            drawContent()
            return
        }
        val (painter, image) = transition
        val intrinsic = painter.intrinsicSize
        if (!intrinsic.width.isFinite() || !intrinsic.height.isFinite() || intrinsic.width <= 0f || intrinsic.height <= 0f) {
            drawContent()
            return
        }
        clipRect(0f, 0f, size.width, size.height) {
            withTransform({
                translate(image.translationX, image.translationY)
                scale(image.scale, image.scale, Offset.Zero)
            }) {
                with(painter) { draw(intrinsic) }
            }
        }
    }

    override fun onDetach() {
        controller.unregisterDetail(token)
        coordinates = null
    }

    fun update(controller: SharedFrameComposeController, sessionId: Long, contentScale: ContentScale) {
        val identityChanged = this.controller !== controller || this.sessionId != sessionId
        if (identityChanged) {
            this.controller.unregisterDetail(token)
            token = controller.newRegistrationToken()
        }
        this.controller = controller
        this.sessionId = sessionId
        this.contentScale = contentScale
        register()
    }

    private fun register() {
        val coordinates = coordinates ?: return
        if (!coordinates.isAttached) return
        controller.registerDetail(DetailRegistration(token, sessionId, contentScale, coordinates))
    }
}

private fun requireSupported(contentScale: ContentScale) {
    require(contentScale == ContentScale.Crop || contentScale == ContentScale.Fit) {
        "Shared Frame supports only ContentScale.Crop and ContentScale.Fit"
    }
}

private fun LayoutCoordinates.hostFrame(): Frame? {
    if (!isAttached) return null
    return Frame(0f, 0f, size.width.toFloat(), size.height.toFloat()).takeIf(Frame::isUsable)
}

private fun Dp.toPx(density: Density): Float = with(density) { toPx() }
private fun Rect.toCoreFrame() = Frame(left, top, right, bottom)
private fun Frame.intersects(other: Frame) = right > other.left && left < other.right && bottom > other.top && top < other.bottom
private fun Frame.approximatelyEquals(other: Frame, tolerance: Float = .5f): Boolean =
    abs(left - other.left) <= tolerance &&
        abs(top - other.top) <= tolerance &&
        abs(right - other.right) <= tolerance &&
        abs(bottom - other.bottom) <= tolerance
private fun lerp(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction

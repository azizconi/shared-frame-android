package io.github.azizconi.sharedframe.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.azizconi.sharedframe.core.Frame
import io.github.azizconi.sharedframe.core.ImageTransform
import io.github.azizconi.sharedframe.core.SharedFrameConfig
import io.github.azizconi.sharedframe.core.SharedFrameMath
import io.github.azizconi.sharedframe.core.SharedFramePhase
import io.github.azizconi.sharedframe.core.SharedFrameStateMachine
import io.github.azizconi.sharedframe.core.UniformTransform

internal data class ComposeSource(
    val key: String,
    val painter: Painter,
    val bounds: Frame,
    val crop: Boolean,
    val radiusPx: Float,
)

@Stable
class SharedFrameComposeController internal constructor(
    val config: SharedFrameConfig,
) {
    private val machine = SharedFrameStateMachine()
    internal val sources = mutableStateMapOf<String, ComposeSource>()
    internal var activeSource by mutableStateOf<ComposeSource?>(null)
    var phase by mutableStateOf(SharedFramePhase.Hidden)
        internal set
    var activeKey by mutableStateOf<String?>(null)
        internal set
    internal var detailHeroFrame by mutableStateOf<Frame?>(null)
    internal var hostFrame by mutableStateOf<Frame?>(null)
    internal var renderTransform by mutableStateOf(UniformTransform.Identity)
    internal var renderMask by mutableStateOf<Frame?>(null)
    internal var renderImage by mutableStateOf<ImageTransform?>(null)
    internal var renderRadius by mutableFloatStateOf(0f)
    internal var scrimAlpha by mutableFloatStateOf(0f)
    internal var detailAlpha by mutableFloatStateOf(1f)
    internal var prepared by mutableStateOf(false)
    internal var sourceHidden by mutableStateOf(false)
    internal var dragX by mutableFloatStateOf(0f)
    internal var dragY by mutableFloatStateOf(0f)
    internal var closingStart by mutableStateOf<UniformTransform?>(null)

    fun open(key: String): Boolean {
        val source = sources[key] ?: return false
        if (!machine.beginOpen()) return false
        activeKey = key
        activeSource = source
        phase = SharedFramePhase.Opening
        prepared = false
        sourceHidden = false
        detailAlpha = 1f
        closingStart = null
        return true
    }

    fun close(): Boolean {
        if (!machine.beginClose()) return false
        phase = SharedFramePhase.Closing
        closingStart = UniformTransform.Identity
        return true
    }

    internal fun beginDrag(): Boolean {
        if (!machine.beginDrag()) return false
        phase = SharedFramePhase.Dragging
        dragX = 0f; dragY = 0f
        return true
    }

    internal fun cancelDrag() {
        if (machine.cancelDrag()) phase = SharedFramePhase.CancellingDrag
    }

    internal fun finishDrag() {
        if (machine.beginClose()) {
            closingStart = SharedFrameMath.dragTransform(dragX, dragY, hostFrame?.width ?: 1f, config.minimumDragScale)
            phase = SharedFramePhase.Closing
        }
    }

    internal fun finishOpen() { machine.finishOpen(); phase = SharedFramePhase.Idle }
    internal fun finishCancel() { machine.finishCancel(); phase = SharedFramePhase.Idle }
    internal fun finishClose() {
        machine.finishClose()
        phase = SharedFramePhase.Hidden
        activeKey = null
        activeSource = null
        prepared = false
        sourceHidden = false
        closingStart = null
        detailHeroFrame = null
        detailAlpha = 1f
    }

    internal fun abortOpen() {
        machine.forceHidden()
        phase = SharedFramePhase.Hidden
        activeKey = null
        activeSource = null
        prepared = false
        sourceHidden = false
        detailHeroFrame = null
    }

    internal fun register(source: ComposeSource) { sources[source.key] = source }
    internal fun unregister(key: String) { sources.remove(key) }
}

@Composable
fun rememberSharedFrameController(
    config: SharedFrameConfig = SharedFrameConfig(),
): SharedFrameComposeController = remember(config) { SharedFrameComposeController(config) }

fun Modifier.sharedFrameSource(
    controller: SharedFrameComposeController,
    key: String,
    painter: Painter,
    contentScale: ContentScale = ContentScale.Crop,
    cornerRadius: Dp = 0.dp,
): Modifier = composed {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val radiusPx = with(density) { cornerRadius.toPx() }
    DisposableEffect(controller, key) { onDispose { controller.unregister(key) } }
    onGloballyPositioned { coordinates ->
        val rect = coordinates.boundsInRoot()
        controller.register(
            ComposeSource(
                key,
                painter,
                rect.toCoreFrame(),
                contentScale != ContentScale.Fit,
                radiusPx,
            )
        )
    }.graphicsLayer {
        alpha = if (controller.activeKey == key && controller.sourceHidden) 0f else 1f
    }
}

class SharedFrameDetailScope internal constructor(
    internal val controller: SharedFrameComposeController,
    val key: String,
    val painter: Painter,
) {
    fun Modifier.sharedFrameDetailHero(): Modifier = onGloballyPositioned { coordinates ->
        // boundsInRoot includes ancestor graphics transforms. Freeze the identity-layout
        // frame once opening is prepared; updating it during animation would restart the
        // phase LaunchedEffect on every frame and pin the transition at fraction zero.
        if (!controller.prepared || controller.phase == SharedFramePhase.Idle) {
            controller.detailHeroFrame = coordinates.boundsInRoot().toCoreFrame()
        }
    }.graphicsLayer {
        alpha = if (controller.phase == SharedFramePhase.Idle || controller.phase == SharedFramePhase.Dragging ||
            controller.phase == SharedFramePhase.CancellingDrag
        ) 1f else 0f
    }
}

@Composable
fun SharedFrameHost(
    controller: SharedFrameComposeController,
    modifier: Modifier = Modifier,
    detailContent: @Composable SharedFrameDetailScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val active = controller.activeSource
    val closeTarget = controller.activeKey?.let(controller.sources::get)
    val host = controller.hostFrame
    val hero = controller.detailHeroFrame

    BackHandler(enabled = controller.phase != SharedFramePhase.Hidden) {
        if (controller.phase == SharedFramePhase.Idle) controller.close()
    }

    androidx.compose.runtime.LaunchedEffect(controller.phase, active, closeTarget, host, hero) {
        when (controller.phase) {
            SharedFramePhase.Opening -> if (active != null && host != null && hero != null) {
                runOpening(controller, active, host, hero)
            }
            SharedFramePhase.Closing -> if (host != null && hero != null) {
                runClosing(controller, closeTarget, host, hero)
            }
            SharedFramePhase.CancellingDrag -> runCancel(controller)
            else -> Unit
        }
    }

    Box(
        modifier.onSizeChanged { controller.hostFrame = Frame(0f, 0f, it.width.toFloat(), it.height.toFloat()) }
    ) {
        content()
        if (active != null && controller.phase != SharedFramePhase.Hidden) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = controller.scrimAlpha)))
            run {
                val transform = controller.renderTransform
                val mask = controller.renderMask
                val detailModifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.translationX
                        translationY = transform.translationY
                        transformOrigin = TransformOrigin.Center
                        alpha = if (controller.prepared) controller.detailAlpha else 0f
                    }
                    .drawWithContent {
                        if (mask == null) drawContent() else {
                            val path = Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        Rect(mask.left, mask.top, mask.right, mask.bottom),
                                        controller.renderRadius,
                                        controller.renderRadius,
                                    )
                                )
                            }
                            clipPath(path) { this@drawWithContent.drawContent() }
                        }
                    }
                    .then(if (controller.prepared) Modifier.sharedFrameDrag(controller) else Modifier)
                Box(detailModifier) {
                    val scope = SharedFrameDetailScope(controller, active.key, active.painter)
                    scope.detailContent()
                    val image = controller.renderImage
                    if (image != null && controller.phase != SharedFramePhase.Idle &&
                        controller.phase != SharedFramePhase.Dragging && controller.phase != SharedFramePhase.CancellingDrag
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            withTransform({
                                translate(image.translationX, image.translationY)
                                scale(image.scale, image.scale, Offset.Zero)
                            }) {
                                with(active.painter) { draw(intrinsicSize) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.sharedFrameDrag(controller: SharedFrameComposeController): Modifier = pointerInput(controller.phase) {
    if (controller.phase != SharedFramePhase.Idle) return@pointerInput
    val tracker = VelocityTracker()
    detectDragGestures(
        onDragStart = { controller.beginDrag() },
        onDragCancel = { controller.cancelDrag() },
        onDragEnd = {
            val velocity = tracker.calculateVelocity().x
            val width = controller.hostFrame?.width ?: 1f
            val finish = SharedFrameMath.shouldFinishDismiss(
                controller.dragX,
                velocity,
                width,
                controller.config.minimumFlingVelocityDp * density,
                controller.config.dismissDistanceFraction,
            )
            if (finish) controller.finishDrag() else controller.cancelDrag()
        },
    ) { change, amount ->
        change.consume()
        tracker.addPosition(change.uptimeMillis, change.position)
        controller.dragX += amount.x
        controller.dragY += amount.y
        controller.renderTransform = SharedFrameMath.dragTransform(
            controller.dragX,
            controller.dragY,
            controller.hostFrame?.width ?: 1f,
            controller.config.minimumDragScale,
        )
        controller.renderMask = controller.hostFrame
        controller.renderRadius = 0f
        controller.scrimAlpha = controller.config.scrimAlpha
    }
}

private suspend fun runOpening(
    controller: SharedFrameComposeController,
    source: ComposeSource,
    parent: Frame,
    hero: Frame,
) {
    val geometry = SharedFrameMath.buildGeometry(source.bounds, parent, hero) ?: return controller.abortOpen()
    val transforms = imageTransforms(source, parent, hero, UniformTransform.Identity) ?: return controller.abortOpen()
    controller.renderTransform = geometry.collapsedTransform
    controller.renderMask = geometry.collapsedMask
    controller.renderRadius = source.radiusPx / geometry.collapsedTransform.scale
    controller.scrimAlpha = 0f
    controller.renderImage = localImage(transforms.first, parent, hero, geometry.collapsedTransform)
    controller.prepared = true
    controller.sourceHidden = true
    val animation = Animatable(0f)
    val easing = controller.config.easing
    animation.animateTo(1f, tween(controller.config.durationMillis.toInt(), easing = CubicBezierEasing(easing.x1, easing.y1, easing.x2, easing.y2))) {
        val transform = SharedFrameMath.lerp(geometry.collapsedTransform, UniformTransform.Identity, value)
        controller.renderTransform = transform
        controller.renderMask = SharedFrameMath.lerp(geometry.collapsedMask, geometry.expandedMask, value)
        controller.renderRadius = lerp(source.radiusPx / geometry.collapsedTransform.scale, 0f, value)
        controller.scrimAlpha = lerp(0f, controller.config.scrimAlpha, value)
        controller.renderImage = localImage(SharedFrameMath.lerp(transforms.first, transforms.second, value), parent, hero, transform)
    }
    controller.renderImage = null
    controller.finishOpen()
}

private suspend fun runClosing(
    controller: SharedFrameComposeController,
    source: ComposeSource?,
    parent: Frame,
    hero: Frame,
) {
    if (source == null || !source.bounds.intersects(parent)) {
        val animation = Animatable(0f)
        animation.animateTo(1f, tween(controller.config.durationMillis.toInt())) {
            controller.detailAlpha = 1f - value
            controller.scrimAlpha = controller.config.scrimAlpha * (1f - value)
        }
        controller.finishClose()
        return
    }
    val geometry = SharedFrameMath.buildGeometry(source.bounds, parent, hero) ?: return controller.finishClose()
    val from = controller.closingStart ?: UniformTransform.Identity
    val detailLocal = imageLocal(source, hero) ?: return controller.finishClose()
    val detailScreen = SharedFrameMath.imageTransformInScreen(detailLocal, parent, hero, from) ?: return controller.finishClose()
    val sourceScreen = sourceScreenTransform(source) ?: return controller.finishClose()
    controller.prepared = true
    controller.sourceHidden = true
    controller.renderTransform = from
    controller.renderMask = parent
    controller.renderImage = localImage(detailScreen, parent, hero, from)
    val animation = Animatable(0f)
    val easing = controller.config.easing
    animation.animateTo(1f, tween(controller.config.durationMillis.toInt(), easing = CubicBezierEasing(easing.x1, easing.y1, easing.x2, easing.y2))) {
        val transform = SharedFrameMath.lerp(from, geometry.collapsedTransform, value)
        controller.renderTransform = transform
        controller.renderMask = SharedFrameMath.lerp(parent, geometry.collapsedMask, value)
        controller.renderRadius = lerp(0f, source.radiusPx / geometry.collapsedTransform.scale, value)
        controller.scrimAlpha = controller.config.scrimAlpha * (1f - value)
        controller.renderImage = localImage(SharedFrameMath.lerp(detailScreen, sourceScreen, value), parent, hero, transform)
    }
    controller.finishClose()
}

private suspend fun runCancel(controller: SharedFrameComposeController) {
    val from = controller.renderTransform
    val animation = Animatable(0f)
    animation.animateTo(1f, tween(controller.config.durationMillis.toInt())) {
        controller.renderTransform = SharedFrameMath.lerp(from, UniformTransform.Identity, value)
    }
    controller.finishCancel()
}

private fun imageTransforms(source: ComposeSource, parent: Frame, hero: Frame, parentTransform: UniformTransform): Pair<ImageTransform, ImageTransform>? {
    val sourceScreen = sourceScreenTransform(source) ?: return null
    val detailLocal = imageLocal(source, hero) ?: return null
    val detailScreen = SharedFrameMath.imageTransformInScreen(detailLocal, parent, hero, parentTransform) ?: return null
    return sourceScreen to detailScreen
}

private fun sourceScreenTransform(source: ComposeSource): ImageTransform? {
    val size = source.painter.intrinsicSize
    if (!size.width.isFinite() || !size.height.isFinite()) return null
    val local = if (source.crop) SharedFrameMath.centerCropTransform(size.width, size.height, source.bounds.width, source.bounds.height)
    else SharedFrameMath.centerFitTransform(size.width, size.height, source.bounds.width, source.bounds.height)
    return local?.let { ImageTransform(it.scale, source.bounds.left + it.translationX, source.bounds.top + it.translationY) }
}

private fun imageLocal(source: ComposeSource, hero: Frame): ImageTransform? {
    val size = source.painter.intrinsicSize
    if (!size.width.isFinite() || !size.height.isFinite()) return null
    return if (source.crop) SharedFrameMath.centerCropTransform(size.width, size.height, hero.width, hero.height)
    else SharedFrameMath.centerFitTransform(size.width, size.height, hero.width, hero.height)
}

private fun localImage(screen: ImageTransform, parent: Frame, hero: Frame, transform: UniformTransform) =
    SharedFrameMath.localImageTransformForScreen(screen, parent, hero, transform)

private fun Frame.intersects(other: Frame) = right > other.left && left < other.right && bottom > other.top && top < other.bottom
private fun Rect.toCoreFrame() = Frame(left, top, right, bottom)
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

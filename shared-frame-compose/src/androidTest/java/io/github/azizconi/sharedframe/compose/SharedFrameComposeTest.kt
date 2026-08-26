package io.github.azizconi.sharedframe.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.azizconi.sharedframe.core.ImageTransform
import io.github.azizconi.sharedframe.core.SharedFrameConfig
import io.github.azizconi.sharedframe.core.SharedFrameDismissDirection
import io.github.azizconi.sharedframe.core.SharedFrameMath
import io.github.azizconi.sharedframe.core.SharedFramePhase
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedFrameComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun openingAndClosingKeepImageInsideHeroAndSupportRepeatedOpen() {
        lateinit var controller: SharedFrameComposeController
        val painter = PatternPainter()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            controller = rememberSharedFrameController()
            TestHost(controller, painter, remember { mutableStateOf(true) })
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        val hostBounds = compose.onNodeWithTag(HOST).fetchSemanticsNode().boundsInRoot
        val sourceBounds = compose.onNodeWithTag(SOURCE).fetchSemanticsNode().boundsInRoot

        compose.runOnIdle { assertTrue(controller.open(KEY)) }
        advanceUntil { controller.renderState().prepared }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(SharedFramePhase.Opening, controller.phase)
        val firstFrame = compose.onNodeWithTag(HOST).captureToImage()
        assertCollapsedImageTransform(controller)
        val sourceInHost = sourceBounds.relativeTo(hostBounds)
        val firstPixels = firstFrame.toPixelMap()
        val sourceCenter = firstPixels[sourceInHost.center.x.toInt(), sourceInHost.center.y.toInt()]
        assertTrue("prepared frame must contain the photo, not an empty source", sourceCenter.distance(Color(0xFFE0E0E0)) > .2f)

        compose.mainClock.advanceTimeBy(125)
        compose.waitForIdle()
        val middle = compose.onNodeWithTag(HOST).captureToImage().toPixelMap()
        val currentHost = compose.onNodeWithTag(HOST).fetchSemanticsNode().boundsInRoot
        val currentHeader = compose.onNodeWithTag(HEADER).fetchSemanticsNode().boundsInRoot.relativeTo(currentHost)
        val headerPixel = middle[currentHeader.center.x.toInt(), currentHeader.center.y.toInt()]
        assertFalse("transition painter must not cover the detail header", headerPixel.isPatternColor())

        advanceUntil { controller.phase == SharedFramePhase.Idle }
        val idle = compose.onNodeWithTag(HOST).captureToImage()
        assertEquals(SharedFramePhase.Idle, controller.phase)

        compose.runOnIdle { assertTrue(controller.close()) }
        advanceUntil { controller.renderState().image != null }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val closingZero = compose.onNodeWithTag(HOST).captureToImage()
        assertImagesClose(idle, closingZero, maxDifferentFraction = .012f)
        advanceUntil { controller.phase == SharedFramePhase.Hidden }

        compose.runOnIdle { assertTrue("source must be registered after the first close", controller.open(KEY)) }
        advanceUntil { controller.phase == SharedFramePhase.Idle }
        assertEquals(SharedFramePhase.Idle, controller.phase)
    }

    @Test
    fun draggedCloseKeepsReleaseFrameAndHandsOffAtTheExactSourceFrame() {
        lateinit var controller: SharedFrameComposeController
        val painter = PatternPainter()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            controller = rememberSharedFrameController()
            TestHost(controller, painter, remember { mutableStateOf(true) })
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val sourceVisible = compose.onNodeWithTag(HOST).captureToImage()

        compose.runOnIdle { assertTrue(controller.open(KEY)) }
        advanceUntil { controller.phase == SharedFramePhase.Idle }
        val baseline = checkNotNull(controller.preparedFrames())
        compose.runOnIdle {
            assertTrue(controller.beginDrag())
            controller.updateDrag(-190f, 28f, SharedFrameDismissDirection.Left)
        }
        val dragRender = controller.renderState()
        val visibleAtRelease = checkNotNull(
            SharedFrameMath.imageTransformInScreen(
                baseline.detailImageLocal,
                baseline.parent,
                baseline.hero,
                dragRender.transform,
            )
        )
        val draggedPixels = compose.onNodeWithTag(HOST).captureToImage()

        compose.runOnIdle { controller.finishDrag() }
        advanceUntil {
            controller.phase == SharedFramePhase.Closing && controller.renderState().image != null
        }
        val closingStart = controller.renderState()
        assertEquals(dragRender.transform.scale, closingStart.transform.scale, .001f)
        assertEquals(dragRender.transform.translationX, closingStart.transform.translationX, .001f)
        assertEquals(dragRender.transform.translationY, closingStart.transform.translationY, .001f)
        assertImageTransform(
            visibleAtRelease,
            checkNotNull(
                SharedFrameMath.imageTransformInScreen(
                    checkNotNull(closingStart.image),
                    baseline.parent,
                    baseline.hero,
                    closingStart.transform,
                )
            ),
        )
        assertImagesClose(draggedPixels, compose.onNodeWithTag(HOST).captureToImage(), .012f)

        advanceUntil {
            val render = controller.renderState()
            controller.phase == SharedFramePhase.Closing &&
                abs(render.transform.scale - baseline.geometry.collapsedTransform.scale) < .001f &&
                abs(render.transform.translationX - baseline.geometry.collapsedTransform.translationX) < .001f &&
                abs(render.transform.translationY - baseline.geometry.collapsedTransform.translationY) < .001f
        }
        val closingEnd = controller.renderState()
        assertNotNull(closingEnd.hiddenSourceToken)
        assertImageTransform(
            baseline.sourceImageScreen,
            checkNotNull(
                SharedFrameMath.imageTransformInScreen(
                    checkNotNull(closingEnd.image),
                    baseline.parent,
                    baseline.hero,
                    closingEnd.transform,
                )
            ),
        )
        val exactHandoff = compose.onNodeWithTag(HOST).captureToImage()
        assertImagesClose(sourceVisible, exactHandoff, .02f)

        advanceUntil { controller.phase == SharedFramePhase.Hidden }
        assertImagesClose(exactHandoff, compose.onNodeWithTag(HOST).captureToImage(), .02f)
    }

    @Test
    fun gesturesCancelShortDragRejectUpAndFinishLeftRightAndDown() {
        lateinit var controller: SharedFrameComposeController
        val painter = PatternPainter()
        compose.setContent {
            controller = rememberSharedFrameController(SharedFrameConfig(durationMillis = 80))
            TestHost(controller, painter, remember { mutableStateOf(true) })
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SOURCE).performClick()
        compose.waitUntil(3_000) { controller.phase == SharedFramePhase.Idle }

        compose.onNodeWithTag(HOST).performTouchInput { swipe(center, center + Offset(10f, -260f), 400) }
        compose.waitForIdle()
        assertEquals(SharedFramePhase.Idle, controller.phase)

        compose.onNodeWithTag(HOST).performTouchInput { swipe(center, center + Offset(30f, 3f), 700) }
        compose.waitUntil(3_000) { controller.phase == SharedFramePhase.Idle }
        assertEquals(1f, controller.renderState().transform.scale, .001f)

        compose.onNodeWithTag(HOST).performTouchInput { swipe(center, center + Offset(-180f, 25f), 600) }
        compose.waitUntil(3_000) { controller.phase == SharedFramePhase.Hidden }

        compose.onNodeWithTag(SOURCE).performClick()
        compose.waitUntil(3_000) { controller.phase == SharedFramePhase.Idle }
        compose.onNodeWithTag(HOST).performTouchInput { swipe(center, center + Offset(180f, 25f), 600) }
        compose.waitUntil(3_000) { controller.phase == SharedFramePhase.Hidden }

        compose.onNodeWithTag(SOURCE).performClick()
        compose.waitUntil(3_000) { controller.phase == SharedFramePhase.Idle }
        compose.onNodeWithTag(HOST).performTouchInput { swipe(center, center + Offset(25f, 180f), 600) }
        compose.waitUntil(3_000) { controller.phase == SharedFramePhase.Hidden }
        assertEquals(SharedFramePhase.Hidden, controller.phase)
    }

    @Test
    fun downDismissWaitsUntilCallerReportsTopBoundary() {
        lateinit var controller: SharedFrameComposeController
        val canDragDown = mutableStateOf(false)
        val painter = PatternPainter()
        compose.setContent {
            controller = rememberSharedFrameController(SharedFrameConfig(durationMillis = 80))
            TestHost(
                controller,
                painter,
                remember { mutableStateOf(true) },
                canStartDismiss = { it != SharedFrameDismissDirection.Down || canDragDown.value },
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SOURCE).performClick()
        compose.waitUntil(3_000) { controller.phase == SharedFramePhase.Idle }

        compose.onNodeWithTag(HOST).performTouchInput { swipe(center, center + Offset(5f, 220f), 600) }
        compose.waitForIdle()
        assertEquals(SharedFramePhase.Idle, controller.phase)

        compose.runOnIdle { canDragDown.value = true }
        compose.onNodeWithTag(HOST).performTouchInput { swipe(center, center + Offset(5f, 220f), 600) }
        compose.waitUntil(3_000) { controller.phase == SharedFramePhase.Hidden }
    }

    @Test
    fun missingSourceUsesFadeInsteadOfRemovingDetailImmediately() {
        lateinit var controller: SharedFrameComposeController
        val sourceVisible = mutableStateOf(true)
        val painter = PatternPainter()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            controller = rememberSharedFrameController()
            TestHost(controller, painter, sourceVisible)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(controller.open(KEY)) }
        advanceUntil { controller.phase == SharedFramePhase.Idle }

        compose.runOnIdle { sourceVisible.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(controller.close()) }
        advanceUntil { controller.phase == SharedFramePhase.Closing && controller.renderState().hiddenSourceToken == null }
        assertEquals(1f, controller.renderState().detailAlpha, .001f)

        compose.mainClock.advanceTimeBy(125)
        compose.waitForIdle()
        assertEquals(SharedFramePhase.Closing, controller.phase)
        assertTrue(controller.renderState().detailAlpha in .05f..0.95f)
        assertNotNull(compose.onNodeWithTag(DETAIL).captureToImage())

        advanceUntil { controller.phase == SharedFramePhase.Hidden }
    }

    @Test
    fun cropAndFitAreAcceptedButOtherContentScalesFailFast() {
        val controller = SharedFrameComposeController(SharedFrameConfig())
        val painter = PatternPainter()
        Modifier.sharedFrameSource(controller, "crop", painter, ContentScale.Crop)
        Modifier.sharedFrameSource(controller, "fit", painter, ContentScale.Fit)
        try {
            Modifier.sharedFrameSource(controller, "invalid", painter, ContentScale.FillBounds)
            fail("FillBounds must be rejected because it needs a non-uniform image transform")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Crop"))
        }
    }

    private fun advanceUntil(predicate: () -> Boolean) {
        repeat(240) {
            if (predicate()) {
                compose.waitForIdle()
                return
            }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        fail("condition was not reached; phase may be stuck")
    }

    private fun assertCollapsedImageTransform(controller: SharedFrameComposeController) {
        val frames = checkNotNull(controller.preparedFrames())
        val render = controller.renderState()
        val local = checkNotNull(render.image)
        val actual = checkNotNull(
            SharedFrameMath.imageTransformInScreen(local, frames.parent, frames.hero, render.transform)
        )
        assertEquals(frames.sourceImageScreen.scale, actual.scale, .001f)
        assertEquals(frames.sourceImageScreen.translationX, actual.translationX, .001f)
        assertEquals(frames.sourceImageScreen.translationY, actual.translationY, .001f)
    }

    private fun assertImagesClose(before: ImageBitmap, after: ImageBitmap, maxDifferentFraction: Float) {
        val first = before.toPixelMap()
        val second = after.toPixelMap()
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        var samples = 0
        var different = 0
        for (y in 0 until first.height step 12) {
            for (x in 0 until first.width step 12) {
                samples++
                if (first[x, y].distance(second[x, y]) > .08f) different++
            }
        }
        assertTrue("closing fraction 0 must match idle; changed $different / $samples samples", different.toFloat() / samples <= maxDifferentFraction)
    }

    private fun assertImageTransform(expected: ImageTransform, actual: ImageTransform) {
        assertEquals(expected.scale, actual.scale, .001f)
        assertEquals(expected.translationX, actual.translationX, .001f)
        assertEquals(expected.translationY, actual.translationY, .001f)
    }

    private fun Rect.relativeTo(parent: Rect) = translate(Offset(-parent.left, -parent.top))
    private fun Color.distance(other: Color) =
        abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue) + abs(alpha - other.alpha)

    private fun Color.isPatternColor(): Boolean =
        distance(Color(0xFFE53935)) < .25f || distance(Color(0xFF43A047)) < .25f ||
            distance(Color(0xFF1E88E5)) < .25f || distance(Color(0xFFFDD835)) < .25f

    companion object {
        private const val KEY = "photo"
        private const val HOST = "host"
        private const val SOURCE = "source"
        private const val HEADER = "header"
        private const val DETAIL = "detail"
    }
}

@Composable
private fun TestHost(
    controller: SharedFrameComposeController,
    painter: Painter,
    sourceVisible: MutableState<Boolean>,
    canStartDismiss: (SharedFrameDismissDirection) -> Boolean = { true },
) {
    Box(Modifier.fillMaxSize().background(Color(0xFF303030)).padding(24.dp)) {
        SharedFrameHost(
            controller = controller,
            modifier = Modifier.size(320.dp, 640.dp).background(Color.White).testTag("host"),
            canStartDismiss = canStartDismiss,
            detailContent = {
                Column(Modifier.fillMaxSize().background(Color.White).testTag("detail")) {
                    Box(Modifier.fillMaxWidth().height(64.dp).background(Color(0xFF00BCD4)).testTag("header"))
                    Image(
                        painter = painter,
                        contentDescription = "detail hero",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .testTag("hero")
                            .sharedFrameDetailHero(ContentScale.Fit),
                    )
                }
            },
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xFFE0E0E0))) {
                if (sourceVisible.value) {
                    Image(
                        painter = painter,
                        contentDescription = "source hero",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .offset(16.dp, 360.dp)
                            .size(120.dp, 160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .testTag("source")
                            .sharedFrameSource(controller, "photo", painter, ContentScale.Crop, 12.dp)
                            .clickable { controller.open("photo") },
                    )
                }
            }
        }
    }
}

private class PatternPainter : Painter() {
    override val intrinsicSize = Size(240f, 120f)

    override fun DrawScope.onDraw() {
        val stripe = size.width / 4f
        drawRect(Color(0xFFE53935), size = Size(stripe, size.height))
        drawRect(Color(0xFF43A047), topLeft = Offset(stripe, 0f), size = Size(stripe, size.height))
        drawRect(Color(0xFF1E88E5), topLeft = Offset(stripe * 2f, 0f), size = Size(stripe, size.height))
        drawRect(Color(0xFFFDD835), topLeft = Offset(stripe * 3f, 0f), size = Size(stripe, size.height))
    }
}

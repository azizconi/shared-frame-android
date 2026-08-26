package io.github.azizconi.sharedframe.views

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.azizconi.sharedframe.core.SharedFrameConfig
import io.github.azizconi.sharedframe.core.SharedFrameDismissDirection
import io.github.azizconi.sharedframe.core.SharedFrameMath
import io.github.azizconi.sharedframe.core.SharedFramePhase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

class SharedFrameTestActivity : Activity() {
    lateinit var host: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = FrameLayout(this)
        setContentView(host)
    }
}

@RunWith(AndroidJUnit4::class)
class SharedFrameViewControllerTest {
    @Test
    fun openAndCloseRestoreSourceVisibility() {
        ActivityScenario.launch(SharedFrameTestActivity::class.java).use { scenario ->
            val opened = CountDownLatch(1)
            val closed = CountDownLatch(1)
            lateinit var controller: SharedFrameViewController
            lateinit var source: ImageView
            scenario.onActivity { activity ->
                val bitmap = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
                val drawable = BitmapDrawable(activity.resources, bitmap)
                source = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(drawable)
                }
                activity.host.addView(source, FrameLayout.LayoutParams(240, 180))
                val detail = FrameLayout(activity)
                val hero = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                detail.addView(hero, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 720))
                controller = SharedFrameViewController(activity.host)
                activity.host.post {
                    assertTrue(controller.open(SharedFrameViewRequest(
                        key = "test",
                        sourceHero = source,
                        drawable = drawable,
                        detailRoot = detail,
                        detailHero = hero,
                        onShown = { opened.countDown() },
                        onHidden = { closed.countDown() },
                    )))
                }
            }
            assertTrue(opened.await(3, TimeUnit.SECONDS))
            scenario.onActivity { assertTrue(controller.close()) }
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            scenario.onActivity {
                assertEquals(1f, source.alpha)
                controller.dispose()
            }
        }
    }

    @Test
    fun dragUsesStableCoordinatesAndSupportsLeftAndBoundaryGuardedDown() {
        ActivityScenario.launch(SharedFrameTestActivity::class.java).use { scenario ->
            lateinit var controller: SharedFrameViewController
            lateinit var source: ImageView
            lateinit var detail: FrameLayout
            lateinit var hero: ImageView
            val downAllowed = AtomicBoolean(false)
            val bitmapReady = CountDownLatch(1)

            scenario.onActivity { activity ->
                val bitmap = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
                val drawable = BitmapDrawable(activity.resources, bitmap)
                source = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(drawable)
                }
                activity.host.addView(source, FrameLayout.LayoutParams(240, 180))
                detail = FrameLayout(activity)
                hero = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                detail.addView(hero, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 720))
                controller = SharedFrameViewController(activity.host, SharedFrameConfig(durationMillis = 80))
                activity.host.post { bitmapReady.countDown() }
            }
            assertTrue(bitmapReady.await(3, TimeUnit.SECONDS))

            fun open() {
                val shown = CountDownLatch(1)
                scenario.onActivity {
                    assertTrue(controller.open(SharedFrameViewRequest(
                        key = "drag-test",
                        sourceHero = source,
                        drawable = source.drawable,
                        detailRoot = detail,
                        detailHero = hero,
                        canStartDismiss = { direction ->
                            direction != SharedFrameDismissDirection.Down || downAllowed.get()
                        },
                        onShown = { shown.countDown() },
                    )))
                }
                assertTrue(shown.await(3, TimeUnit.SECONDS))
            }

            fun hostCenter(): Pair<Float, Float> {
                var result = 0f to 0f
                scenario.onActivity { activity ->
                    val location = IntArray(2)
                    activity.host.getLocationOnScreen(location)
                    result = location[0] + activity.host.width / 2f to location[1] + activity.host.height / 2f
                }
                return result
            }

            open()
            val (centerX, centerY) = hostCenter()
            sendSwipe(centerX, centerY, centerX - 35f, centerY + 2f, 500)
            waitForPhase(controller, SharedFramePhase.Idle)

            sendSwipe(centerX, centerY, centerX - 260f, centerY + 20f, 500)
            waitForPhase(controller, SharedFramePhase.Hidden)

            open()
            sendSwipe(centerX, centerY, centerX + 5f, centerY + 260f, 500)
            waitForPhase(controller, SharedFramePhase.Idle)

            downAllowed.set(true)
            sendSwipe(centerX, centerY, centerX + 5f, centerY + 260f, 500)
            waitForPhase(controller, SharedFramePhase.Hidden)
            scenario.onActivity { controller.dispose() }
        }
    }

    @Test
    fun dragClosingStartsFromTheDisplayedImageWithoutReapplyingTheDragTransform() {
        ActivityScenario.launch(SharedFrameTestActivity::class.java).use { scenario ->
            val shown = CountDownLatch(1)
            lateinit var controller: SharedFrameViewController
            scenario.onActivity { activity ->
                val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply {
                    for (x in 0 until width) {
                        val color = when (x / 40) {
                            0 -> Color.RED
                            1 -> Color.GREEN
                            2 -> Color.BLUE
                            else -> Color.YELLOW
                        }
                        for (y in 0 until height) setPixel(x, y, color)
                    }
                }
                val drawable = BitmapDrawable(activity.resources, bitmap)
                val source = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(drawable)
                }
                activity.host.addView(source, FrameLayout.LayoutParams(240, 300).apply {
                    leftMargin = 48
                    topMargin = 520
                })
                val detail = FrameLayout(activity)
                val hero = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                detail.addView(hero, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 720).apply {
                    topMargin = 96
                })
                controller = SharedFrameViewController(activity.host, SharedFrameConfig(durationMillis = 1_000))
                activity.host.post {
                    assertTrue(controller.open(SharedFrameViewRequest(
                        key = "stable-close",
                        sourceHero = source,
                        drawable = drawable,
                        detailRoot = detail,
                        detailHero = hero,
                        onShown = { shown.countDown() },
                    )))
                }
            }
            assertTrue(shown.await(3, TimeUnit.SECONDS))

            val (centerX, centerY) = hostCenter(scenario)
            val pendingUp = beginSwipe(centerX, centerY, centerX - 280f, centerY + 24f, 240)
            lateinit var dragged: SharedFrameViewRenderSnapshot
            scenario.onActivity {
                assertEquals(SharedFramePhase.Dragging, controller.phase)
                dragged = controller.renderSnapshot()
            }
            val visibleAtRelease = SharedFrameMath.imageTransformInScreen(
                checkNotNull(dragged.detailImageLocal),
                checkNotNull(dragged.parent),
                checkNotNull(dragged.hero),
                dragged.transform,
            ) ?: error("dragged image must map to screen")

            finishSwipe(pendingUp)
            lateinit var closing: SharedFrameViewRenderSnapshot
            scenario.onActivity {
                assertEquals(SharedFramePhase.Closing, controller.phase)
                closing = controller.renderSnapshot()
            }
            val collapsed = checkNotNull(closing.collapsedTransform)
            val scaleDistance = collapsed.scale - dragged.transform.scale
            val fraction = if (kotlin.math.abs(scaleDistance) < .0001f) {
                0f
            } else {
                ((closing.transform.scale - dragged.transform.scale) / scaleDistance).coerceIn(0f, 1f)
            }
            val expectedTransform = SharedFrameMath.lerp(dragged.transform, collapsed, fraction)
            assertEquals(expectedTransform.scale, closing.transform.scale, .001f)
            assertEquals(expectedTransform.translationX, closing.transform.translationX, 2f)
            assertEquals(expectedTransform.translationY, closing.transform.translationY, 2f)
            val closingImage = SharedFrameMath.imageTransformInScreen(
                checkNotNull(closing.imageLocal),
                checkNotNull(closing.parent),
                checkNotNull(closing.hero),
                closing.transform,
            ) ?: error("closing image must map to screen")
            val expectedImage = SharedFrameMath.lerp(
                visibleAtRelease,
                checkNotNull(closing.sourceImageScreen),
                fraction,
            )
            assertEquals(expectedImage.scale, closingImage.scale, .01f)
            assertEquals(expectedImage.translationX, closingImage.translationX, 2f)
            assertEquals(expectedImage.translationY, closingImage.translationY, 2f)

            waitForPhase(controller, SharedFramePhase.Hidden, timeoutMillis = 3_000)
            scenario.onActivity { controller.dispose() }
        }
    }

    private fun sendSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long) {
        finishSwipe(beginSwipe(startX, startY, endX, endY, durationMillis))
    }

    private data class PendingUp(val downTime: Long, val x: Float, val y: Float)

    private fun beginSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long): PendingUp {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0))
        val steps = 12
        repeat(steps) { index ->
            SystemClock.sleep(durationMillis / steps)
            val fraction = (index + 1f) / steps
            val eventTime = SystemClock.uptimeMillis()
            instrumentation.sendPointerSync(MotionEvent.obtain(
                downTime,
                eventTime,
                MotionEvent.ACTION_MOVE,
                startX + (endX - startX) * fraction,
                startY + (endY - startY) * fraction,
                0,
            ))
        }
        return PendingUp(downTime, endX, endY)
    }

    private fun finishSwipe(pending: PendingUp) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val upTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(pending.downTime, upTime, MotionEvent.ACTION_UP, pending.x, pending.y, 0))
    }

    private fun hostCenter(scenario: ActivityScenario<SharedFrameTestActivity>): Pair<Float, Float> {
        var result = 0f to 0f
        scenario.onActivity { activity ->
            val location = IntArray(2)
            activity.host.getLocationOnScreen(location)
            result = location[0] + activity.host.width / 2f to location[1] + activity.host.height / 2f
        }
        return result
    }

    private fun waitForPhase(
        controller: SharedFrameViewController,
        expected: SharedFramePhase,
        timeoutMillis: Long = 3_000,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline && controller.phase != expected) SystemClock.sleep(16)
        assertEquals(expected, controller.phase)
    }
}

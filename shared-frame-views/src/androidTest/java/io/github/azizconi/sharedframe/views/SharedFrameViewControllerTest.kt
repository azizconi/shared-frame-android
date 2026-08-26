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

    private fun sendSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long) {
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
        val upTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, endX, endY, 0))
    }

    private fun waitForPhase(controller: SharedFrameViewController, expected: SharedFramePhase) {
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (SystemClock.uptimeMillis() < deadline && controller.phase != expected) SystemClock.sleep(16)
        assertEquals(expected, controller.phase)
    }
}

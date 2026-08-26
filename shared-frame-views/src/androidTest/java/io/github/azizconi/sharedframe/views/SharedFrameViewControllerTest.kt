package io.github.azizconi.sharedframe.views

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
}

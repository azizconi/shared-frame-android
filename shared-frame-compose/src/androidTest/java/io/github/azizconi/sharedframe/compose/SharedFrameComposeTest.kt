package io.github.azizconi.sharedframe.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.azizconi.sharedframe.core.SharedFramePhase
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedFrameComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun registeredSourceOpensAndClosesOverlay() {
        lateinit var controller: SharedFrameComposeController
        val painter: Painter = object : Painter() {
            override val intrinsicSize: Size = Size(120f, 90f)
            override fun DrawScope.onDraw() { drawRect(Color.Magenta) }
        }
        compose.setContent {
            controller = rememberSharedFrameController()
            SharedFrameHost(
                controller = controller,
                detailContent = {
                    Box(Modifier.fillMaxSize()) {
                        Image(
                            painter = painter,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().sharedFrameDetailHero(),
                        )
                    }
                },
            ) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(120.dp, 90.dp)
                        .sharedFrameSource(controller, "photo", painter)
                        .clickable { controller.open("photo") },
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(controller.open("photo")) }
        compose.waitUntil(3_000) { controller.phase == SharedFramePhase.Idle }
        compose.runOnIdle { assertTrue(controller.close()) }
        compose.waitUntil(3_000) { controller.phase == SharedFramePhase.Hidden }
    }
}

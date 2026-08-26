package tj.app.testproject

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import io.github.azizconi.sharedframe.compose.SharedFrameHost
import io.github.azizconi.sharedframe.compose.rememberSharedFrameController
import io.github.azizconi.sharedframe.compose.sharedFrameSource

class ComposeDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFFFAFAFA)) { ComposeDemo() }
            }
        }
    }
}

@Composable
private fun ComposeDemo() {
    val controller = rememberSharedFrameController()
    SharedFrameHost(
        controller = controller,
        detailContent = {
            Column(Modifier.fillMaxSize().background(Color.White).testTag("compose_detail")) {
                Row(
                    Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "×",
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { controller.close() }
                            .testTag("detail_close")
                            .wrapContentSize(Alignment.Center),
                        color = Color(0xFF202020),
                        fontSize = 32.sp,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Post",
                        Modifier.weight(1f),
                        color = Color(0xFF202020),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "•••",
                        Modifier.size(48.dp).wrapContentSize(Alignment.Center),
                        color = Color(0xFF202020),
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                Image(
                    painter = painter,
                    contentDescription = "Selected photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .sharedFrameDetailHero(ContentScale.Crop),
                )
                Column(Modifier.padding(20.dp)) {
                    Text("♡   ◯   ⤴", fontSize = 28.sp)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        ViewsDemoActivity.PHOTO_TITLES[key.removePrefix("photo-").toInt()],
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("The same reusable controller works from carousel, grid and list images.", color = Color(0xFF5C5C5C), fontSize = 15.sp)
                    Spacer(Modifier.height(24.dp))
                    Text("Drag horizontally to dismiss", color = Color(0xFF8A8A8A), fontSize = 13.sp)
                }
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize().testTag("compose_feed")) {
            item {
                Row(
                    Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Compose demo", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Text("Carousel · Grid · List", fontSize = 12.sp, color = Color.Gray)
                }
                SectionTitle("Carousel")
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items((0..3).toList()) { index ->
                        SourcePhoto(index, Modifier.size(146.dp, 184.dp), 18, controller)
                    }
                }
                Spacer(Modifier.height(12.dp))
                SectionTitle("Grid")
            }
            items(listOf(listOf(4, 5, 6), listOf(7, 8, 9))) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { index -> SourcePhoto(index, Modifier.weight(1f).aspectRatio(1f), 0, controller) }
                }
                Spacer(Modifier.height(4.dp))
            }
            item { SectionTitle("Vertical list") }
            items((10..12).toList()) { index ->
                Column {
                    SourcePhoto(index, Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(210.dp), 14, controller)
                    Spacer(Modifier.height(14.dp))
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF171717),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
    )
}

@Composable
private fun SourcePhoto(
    index: Int,
    modifier: Modifier,
    radius: Int,
    controller: io.github.azizconi.sharedframe.compose.SharedFrameComposeController,
) {
    val painter = rememberAsyncImagePainter(ViewsDemoActivity.PHOTO_URLS[index])
    val painterState by painter.state.collectAsState()
    val loaded = painterState is AsyncImagePainter.State.Success
    val shape = RoundedCornerShape(radius.dp)
    Image(
        painter = painter,
        contentDescription = "Open ${ViewsDemoActivity.PHOTO_TITLES[index]}",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(shape)
            .background(Color(0xFFE7E7E7))
            .testTag("source_photo_$index")
            .then(
                if (loaded) Modifier.sharedFrameSource(
                    controller = controller,
                    key = "photo-$index",
                    painter = painter,
                    contentScale = ContentScale.Crop,
                    cornerRadius = radius.dp,
                ) else Modifier
            )
            .clickable(enabled = loaded) { controller.open("photo-$index") },
    )
}

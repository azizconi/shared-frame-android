package tj.app.testproject

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import io.github.azizconi.sharedframe.views.SharedFrameViewController
import io.github.azizconi.sharedframe.views.SharedFrameViewRequest
import tj.app.testproject.databinding.ActivityViewsDemoBinding
import tj.app.testproject.databinding.ViewPhotoDetailBinding

class ViewsDemoActivity : AppCompatActivity() {
    private lateinit var controller: SharedFrameViewController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityViewsDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        controller = SharedFrameViewController(binding.sharedFrameHost)
        buildFeed(binding.feedContent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!controller.handleBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onDestroy() {
        controller.dispose()
        super.onDestroy()
    }

    private fun buildFeed(feed: LinearLayout) {
        feed.addView(sectionTitle("Carousel"))
        val carousel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(8), dp(12))
        }
        repeat(4) { index ->
            carousel.addView(photo(index, dp(146), dp(184), 18f), LinearLayout.LayoutParams(dp(146), dp(184)).apply {
                marginEnd = dp(12)
            })
        }
        feed.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(carousel)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        feed.addView(sectionTitle("Grid"))
        val grid = GridLayout(this).apply {
            columnCount = 3
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        val cell = (resources.displayMetrics.widthPixels - dp(32)) / 3
        repeat(6) { offset ->
            grid.addView(photo(offset + 4, cell, cell, 0f), GridLayout.LayoutParams().apply {
                width = cell
                height = cell
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        feed.addView(grid)

        feed.addView(sectionTitle("Vertical list"))
        repeat(3) { offset ->
            val index = offset + 10
            val image = photo(index, ViewGroup.LayoutParams.MATCH_PARENT, dp(210), 14f)
            feed.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                bottomMargin = dp(14)
            })
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 19f
        setTextColor(0xFF171717.toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(16), dp(18), dp(16), dp(12))
    }

    private fun photo(index: Int, width: Int, height: Int, radiusDp: Float): PhotoArtworkView =
        PhotoArtworkView(this).apply {
            contentDescription = "Open ${PHOTO_TITLES[index]}"
            isFocusable = false
            isFocusableInTouchMode = false
            if (radiusDp > 0f) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(radiusDp).toFloat()
                    setColor(0xFFE7E7E7.toInt())
                }
                clipToOutline = true
            }
            bind(PHOTO_URLS[index])
            setOnClickListener { source -> openPhoto(source as PhotoArtworkView, index, radiusDp) }
        }

    private fun openPhoto(source: PhotoArtworkView, index: Int, radiusDp: Float) {
        if (!source.isEnabled) return
        val drawable = source.drawable ?: return
        val detail = ViewPhotoDetailBinding.inflate(layoutInflater)
        detail.detailTitle.text = PHOTO_TITLES[index]
        detail.closeButton.setOnClickListener { controller.close() }
        controller.open(
            SharedFrameViewRequest(
                key = "photo-$index",
                sourceHero = source,
                drawable = drawable,
                sourceRadiusPx = dp(radiusDp).toFloat(),
                detailRoot = detail.root,
                detailHero = detail.detailHero,
            )
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        val PHOTO_URLS = listOf(
            "https://images.unsplash.com/photo-1500530855697-b586d89ba3ee?w=1200&q=88",
            "https://images.unsplash.com/photo-1501854140801-50d01698950b?w=1200&q=88",
            "https://images.unsplash.com/photo-1470770841072-f978cf4d019e?w=1200&q=88",
            "https://images.unsplash.com/photo-1441974231531-c6227db76b6e?w=1200&q=88",
            "https://images.unsplash.com/photo-1469474968028-56623f02e42e?w=1200&q=88",
            "https://images.unsplash.com/photo-1501785888041-af3ef285b470?w=1200&q=88",
            "https://images.unsplash.com/photo-1472214103451-9374bd1c798e?w=1200&q=88",
            "https://images.unsplash.com/photo-1433086966358-54859d0ed716?w=1200&q=88",
            "https://images.unsplash.com/photo-1447752875215-b2761acb3c5d?w=1200&q=88",
            "https://images.unsplash.com/photo-1470252649378-9c29740c9fa8?w=1200&q=88",
            "https://images.unsplash.com/photo-1497250681960-ef046c08a56e?w=1200&q=88",
            "https://images.unsplash.com/photo-1511497584788-876760111969?w=1200&q=88",
            "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=1200&q=88",
        )
        val PHOTO_TITLES = listOf(
            "Quiet morning", "Emerald lake", "Mountain air", "Forest light", "Open road",
            "Golden valley", "Green horizon", "Waterfall", "Deep woods", "Sunrise",
            "Tropical leaves", "Wild forest", "Above the clouds",
        )
    }
}

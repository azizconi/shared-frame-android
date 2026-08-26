package tj.app.testproject

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.util.Log
import androidx.appcompat.widget.AppCompatImageView
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import androidx.core.graphics.drawable.toDrawable

class PhotoArtworkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    var imageUrl: String = ""
        private set

    init {
        scaleType = ScaleType.CENTER_CROP
    }

    fun bind(url: String) {
        imageUrl = url
        isEnabled = false
        load(url) {
            memoryCacheKey(url)
            diskCacheKey(url)
            crossfade(false)
            placeholder(Color.rgb(232, 232, 232).toDrawable())
            error(Color.rgb(210, 210, 210).toDrawable())
            listener(
                onSuccess = { _, _ -> isEnabled = true },
                onError = { _, result ->
                    isEnabled = false
                    Log.e("PhotoArtworkView", "Unable to load $url", result.throwable)
                },
            )
        }
    }
}

package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.pb_logo
import org.jetbrains.compose.resources.imageResource
import kotlin.math.roundToInt

/** The ink bounds of the original 500 x 500 PB PNG; the source asset stays unmodified. */
object PureBitsLogoBounds {
    const val SOURCE_SIZE = 500
    const val LEFT = 48
    const val TOP = 107
    const val WIDTH = 404
    const val HEIGHT = 287
}

/**
 * Render the mark without its transparent outer padding. Callers retain their
 * existing logo slots and tint; BitmapPainter preserves the PB aspect ratio.
 */
@Composable
fun rememberPureBitsLogoPainter(): Painter {
    val bitmap = imageResource(Res.drawable.pb_logo)
    return remember(bitmap) {
        // Resource loading can briefly return a 1px placeholder. Android also
        // scales MDPI image resources to the display density before returning them.
        if (bitmap.width <= 1 || bitmap.height <= 1) {
            ColorPainter(Color.Transparent)
        } else {
            val bounds = PureBitsLogoBounds
            val scaleX = bitmap.width.toFloat() / bounds.SOURCE_SIZE
            val scaleY = bitmap.height.toFloat() / bounds.SOURCE_SIZE
            val left = (bounds.LEFT * scaleX).roundToInt()
            val top = (bounds.TOP * scaleY).roundToInt()
            val right = ((bounds.LEFT + bounds.WIDTH) * scaleX).roundToInt()
            val bottom = ((bounds.TOP + bounds.HEIGHT) * scaleY).roundToInt()
            BitmapPainter(
                image = bitmap,
                srcOffset = IntOffset(left, top),
                srcSize = IntSize(right - left, bottom - top),
            )
        }
    }
}

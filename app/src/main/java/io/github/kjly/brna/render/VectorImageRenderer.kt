package io.github.kjly.brna.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import io.github.kjly.brna.model.NativeVectorImageElement
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Rasterises Rnote vector images (imported PDF pages) once, the way desktop Rnote does
 * (`VectorImage::gen_images` renders them to a full image because they are too expensive
 * to redraw per frame).
 */
object VectorImageRenderer {

    /** Longest bitmap side. A PDF page at roughly one pixel per canvas unit fits under it. */
    private const val MAX_SIDE_PX = 2048

    /** Returns null when the SVG can't be parsed or there isn't memory for the bitmap. */
    fun rasterize(el: NativeVectorImageElement): Bitmap? = try {
        val t = el.transform
        val scaleX = sqrt(t[0] * t[0] + t[1] * t[1]).coerceAtLeast(1e-3f)
        val scaleY = sqrt(t[2] * t[2] + t[3] * t[3]).coerceAtLeast(1e-3f)
        var w = 2f * el.halfExtentX * scaleX
        var h = 2f * el.halfExtentY * scaleY
        val longest = max(w, h)
        if (longest > MAX_SIDE_PX) {
            w *= MAX_SIDE_PX / longest
            h *= MAX_SIDE_PX / longest
        }
        val pw = w.roundToInt().coerceAtLeast(1)
        val ph = h.roundToInt().coerceAtLeast(1)

        val svg = SVG.getFromString(el.svgData)
        val bitmap = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Rnote stretches the SVG's intrinsic box over the image rectangle
        // (preserveAspectRatio="none"), so the two axes scale independently.
        canvas.scale(pw / el.intrinsicWidth, ph / el.intrinsicHeight)
        svg.renderToCanvas(
            canvas,
            RenderOptions().viewPort(0f, 0f, el.intrinsicWidth, el.intrinsicHeight)
        )
        bitmap
    } catch (e: Throwable) {
        // OutOfMemoryError included: one page failing to render must not take the app down.
        e.printStackTrace()
        null
    }

    /** Maps the image's local rectangle [-hx, hx] x [-hy, hy] into canvas coordinates. */
    fun matrixFor(el: NativeVectorImageElement): Matrix {
        val t = el.transform
        return Matrix().apply {
            setValues(floatArrayOf(t[0], t[2], t[4], t[1], t[3], t[5], 0f, 0f, 1f))
        }
    }
}

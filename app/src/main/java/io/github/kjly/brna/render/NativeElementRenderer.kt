package io.github.kjly.brna.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathEffect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.kjly.brna.model.EllipseShape
import io.github.kjly.brna.model.LineShape
import io.github.kjly.brna.model.NativeBitmapElement
import io.github.kjly.brna.model.NativeBrushStroke
import io.github.kjly.brna.model.NativeCanvasElement
import io.github.kjly.brna.model.NativeShapeElement
import io.github.kjly.brna.model.NativeShapeKind
import io.github.kjly.brna.model.NativeTextElement
import io.github.kjly.brna.model.NativeVectorImageElement
import io.github.kjly.brna.model.PathOp
import io.github.kjly.brna.model.PathShape
import io.github.kjly.brna.model.RectShape
import io.github.kjly.brna.model.RnoteNativeColor
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import kotlin.math.ceil

/**
 * Draws the desktop elements this app shows but does not edit — text boxes, shapes and
 * embedded images — onto an `android.graphics.Canvas` in document coordinates. The
 * drawing canvas and the PNG/JPEG/PDF export both go through here, so a note looks the
 * same on screen and in a file.
 *
 * Layouts and paths are cached per element; make a new renderer when the document
 * changes rather than keeping one for the app's lifetime.
 */
class NativeElementRenderer {

    private val textLayouts = IdentityHashMap<NativeTextElement, StaticLayout>()
    private val shapePaths = IdentityHashMap<NativeShapeElement, Path>()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Rnote's `TextStroke::draw`: the laid-out text, its top-left at the transform's origin. */
    fun drawText(canvas: Canvas, el: NativeTextElement) {
        val layout = textLayouts.getOrPut(el) { buildLayout(el) }
        canvas.save()
        canvas.concat(matrixOf(el.transform))
        layout.draw(canvas)
        canvas.restore()
    }

    /** [cache] false for a shape drawn only once, like the Shaper's preview. */
    fun drawShape(canvas: Canvas, el: NativeShapeElement, cache: Boolean = true) {
        val path = if (cache) shapePaths.getOrPut(el) { pathOf(el.shape) } else pathOf(el.shape)
        if (el.fillColor.a > 0f) {
            fillPaint.color = argb(el.fillColor)
            canvas.drawPath(path, fillPaint)
        }
        if (el.strokeWidth > 0f && el.color.a > 0f) {
            strokePaint.color = argb(el.color)
            strokePaint.strokeWidth = el.strokeWidth
            strokePaint.strokeCap = if (el.roundCap) Paint.Cap.ROUND else Paint.Cap.BUTT
            strokePaint.pathEffect = dashEffect(el.lineStyle, el.strokeWidth, el.roundCap)
            canvas.drawPath(path, strokePaint)
        }
    }

    /** [bitmap] is [decodeBitmap]'s result for [el], decoded ahead of time off the UI thread. */
    fun drawBitmap(canvas: Canvas, el: NativeBitmapElement, bitmap: Bitmap) {
        val rect = el.rect
        if (rect != null) {
            canvas.save()
            canvas.concat(matrixOf(rect.transform))
            canvas.drawBitmap(
                bitmap, null,
                RectF(-rect.halfExtentX, -rect.halfExtentY, rect.halfExtentX, rect.halfExtentY),
                bitmapPaint
            )
            canvas.restore()
        } else {
            canvas.drawBitmap(bitmap, null, RectF(el.minX, el.minY, el.maxX, el.maxY), bitmapPaint)
        }
    }

    /** Drops cached layouts and paths of elements no longer in the document. */
    fun retainOnly(elements: Collection<NativeCanvasElement>) {
        val keep = java.util.Collections.newSetFromMap(IdentityHashMap<NativeCanvasElement, Boolean>())
        keep.addAll(elements)
        textLayouts.keys.retainAll(keep)
        shapePaths.keys.retainAll(keep)
    }

    private fun buildLayout(el: NativeTextElement): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(el.color)
            textSize = el.fontSize
            typeface = typefaceFor(el)
        }
        // Without a wrap width, a text box is as wide as its longest line.
        val width = el.maxWidth?.takeIf { it > 0f }?.let { ceil(it).toInt() }
            ?: (el.text.split('\n').maxOf { ceil(paint.measureText(it)).toInt() } + 1)
        val alignment = when (el.alignment) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "end" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val builder = StaticLayout.Builder
            .obtain(el.text, 0, el.text.length, paint, width.coerceAtLeast(1))
            .setAlignment(alignment)
            .setIncludePad(false)
        if (el.alignment == "fill") {
            @Suppress("DEPRECATION")
            builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        }
        return builder.build()
    }

    /**
     * Rnote names fonts by family ("serif" by default). Android resolves the generic
     * families and falls back to its default face for any it doesn't have installed.
     */
    private fun typefaceFor(el: NativeTextElement): Typeface {
        val family = Typeface.create(el.fontFamily, Typeface.NORMAL)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(family, el.fontWeight.coerceIn(1, 1000), el.italic)
        } else {
            val style = (if (el.fontWeight >= 600) Typeface.BOLD else 0) or
                (if (el.italic) Typeface.ITALIC else 0)
            Typeface.create(family, style)
        }
    }

    companion object {

        /** Whether Rnote draws this beneath the ink: PDF pages and images. */
        fun isUnderlay(el: NativeCanvasElement): Boolean =
            el is NativeVectorImageElement || el is NativeBitmapElement

        /** Whether this is drawn above the ink: text and shapes. */
        fun isOverlay(el: NativeCanvasElement): Boolean =
            el is NativeTextElement || el is NativeShapeElement

        /**
         * The image's pixels as a bitmap; null if they can't be read. Rnote 0.14 stores raw
         * premultiplied RGBA, which is exactly the byte layout of an `ARGB_8888` bitmap, so
         * the bytes are copied in as they are. Slow for large images — call off the UI thread.
         */
        fun decodeBitmap(el: NativeBitmapElement): Bitmap? = try {
            val rgba = el.rgbaBase64
            when {
                rgba != null -> {
                    val bytes = android.util.Base64.decode(rgba, android.util.Base64.DEFAULT)
                    val needed = el.bmpWidth.toLong() * el.bmpHeight * 4
                    if (el.bmpWidth <= 0 || el.bmpHeight <= 0 || bytes.size < needed) {
                        null
                    } else {
                        Bitmap.createBitmap(el.bmpWidth, el.bmpHeight, Bitmap.Config.ARGB_8888).apply {
                            copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 0, needed.toInt()))
                        }
                    }
                }
                el.pixels.isNotEmpty() ->
                    Bitmap.createBitmap(el.pixels, el.bmpWidth, el.bmpHeight, Bitmap.Config.ARGB_8888)
                else -> null
            }
        } catch (e: Throwable) {
            // OutOfMemoryError included: an image too large to show is left out, not fatal.
            e.printStackTrace()
            null
        }

        /** Column-major [a, b, c, d, tx, ty] as an android [Matrix]. */
        fun matrixOf(t: FloatArray): Matrix = Matrix().apply {
            setValues(floatArrayOf(t[0], t[2], t[4], t[1], t[3], t[5], 0f, 0f, 1f))
        }

        fun argb(c: RnoteNativeColor): Int {
            fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            return (ch(c.a) shl 24) or (ch(c.r) shl 16) or (ch(c.g) shl 8) or ch(c.b)
        }

        /** A shape's outline in document coordinates. */
        fun pathOf(shape: NativeShapeKind): Path {
            val path = Path()
            when (shape) {
                is LineShape -> {
                    path.moveTo(shape.x1, shape.y1)
                    path.lineTo(shape.x2, shape.y2)
                }
                is RectShape -> {
                    path.addRect(
                        -shape.halfExtentX, -shape.halfExtentY, shape.halfExtentX, shape.halfExtentY,
                        Path.Direction.CW
                    )
                    path.transform(matrixOf(shape.transform))
                }
                is EllipseShape -> {
                    path.addOval(
                        RectF(-shape.radiusX, -shape.radiusY, shape.radiusX, shape.radiusY),
                        Path.Direction.CW
                    )
                    path.transform(matrixOf(shape.transform))
                }
                is PathShape -> for (op in shape.ops) {
                    when (op) {
                        is PathOp.MoveTo -> path.moveTo(op.x, op.y)
                        is PathOp.LineTo -> path.lineTo(op.x, op.y)
                        is PathOp.QuadTo -> path.quadTo(op.x1, op.y1, op.x, op.y)
                        is PathOp.CubicTo -> path.cubicTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
                        PathOp.Close -> path.close()
                    }
                }
            }
            return path
        }

        /**
         * Rnote's `SmoothOptions::compute_piet_stroke_style`: a unit dash pattern scaled by
         * e · stroke width, with room left for the caps when they are rounded. Dots are
         * zero-length dashes that only the round cap makes visible.
         */
        fun dashPattern(lineStyle: String, strokeWidth: Float, roundCap: Boolean): FloatArray? {
            val unit = when (lineStyle) {
                "dotted" -> floatArrayOf(0f, 0f)
                "dashed_narrow" -> floatArrayOf(1f, 0.618f)
                "dashed_equidistant" -> floatArrayOf(1f, 1f)
                "dashed_wide" -> floatArrayOf(1f, 1.618f)
                else -> return null
            }
            val dotted = lineStyle == "dotted"
            val k = strokeWidth * Math.E.toFloat()
            val pattern = FloatArray(2) { i ->
                var v = if (dotted && roundCap) unit[i] else unit[i] * k
                if (roundCap && i % 2 == 1) v += 2f * strokeWidth
                v
            }
            if (pattern[1] <= 0f) return null
            // A zero-length dash is what draws a dot, but the intervals must be positive.
            pattern[0] = pattern[0].coerceAtLeast(0.01f)
            return pattern
        }

        private fun dashEffect(lineStyle: String, strokeWidth: Float, roundCap: Boolean): PathEffect? =
            dashPattern(lineStyle, strokeWidth, roundCap)?.let { DashPathEffect(it, 0f) }

        /** True for the elements a document's brush-stroke list does not already cover. */
        fun isDrawnByRenderer(el: NativeCanvasElement): Boolean = el !is NativeBrushStroke
    }
}

package io.github.kjly.brna.storage

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.github.kjly.brna.model.EllipseShape
import io.github.kjly.brna.model.LineShape
import io.github.kjly.brna.model.NativeBitmapElement
import io.github.kjly.brna.model.NativeBrushStroke
import io.github.kjly.brna.model.NativeCanvasElement
import io.github.kjly.brna.model.NativeShapeElement
import io.github.kjly.brna.model.NativeTextElement
import io.github.kjly.brna.model.NativeVectorImageElement
import io.github.kjly.brna.model.PathOp
import io.github.kjly.brna.model.PathShape
import io.github.kjly.brna.model.RectShape
import io.github.kjly.brna.model.RnoteNativeColor
import io.github.kjly.brna.model.ShapeKind
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Editing for the desktop elements this app used to only display — text, shapes, images,
 * PDF pages — and creating new shapes. Every element that carries the JSON it was read
 * from ([NativeShapeElement.raw] etc.) gets that JSON changed along with it, so a moved
 * text box is written back moved and still has every attribute the model doesn't know.
 */
object NativeEditing {

    // ── Moving ────────────────────────────────────────────────────────────────

    /** [el] moved by ([dx], [dy]). Brush strokes are returned unchanged: they live in the stroke list. */
    fun translate(el: NativeCanvasElement, dx: Float, dy: Float): NativeCanvasElement = when (el) {
        is NativeTextElement -> el.copy(
            transform = shifted(el.transform, dx, dy),
            minX = el.minX + dx, minY = el.minY + dy, maxX = el.maxX + dx, maxY = el.maxY + dy,
            raw = el.raw?.let { shiftTree(it, dx, dy) }
        )
        is NativeShapeElement -> el.copy(
            shape = when (val s = el.shape) {
                is LineShape -> LineShape(s.x1 + dx, s.y1 + dy, s.x2 + dx, s.y2 + dy)
                is RectShape -> s.copy(transform = shifted(s.transform, dx, dy))
                is EllipseShape -> s.copy(transform = shifted(s.transform, dx, dy))
                is PathShape -> PathShape(s.ops.map { shiftOp(it, dx, dy) })
            },
            minX = el.minX + dx, minY = el.minY + dy, maxX = el.maxX + dx, maxY = el.maxY + dy,
            raw = el.raw?.let { shiftTree(it, dx, dy) }
        )
        is NativeBitmapElement -> el.copy(
            transform = shifted(el.transform, dx, dy),
            rect = el.rect?.let { it.copy(transform = shifted(it.transform, dx, dy)) },
            minX = el.minX + dx, minY = el.minY + dy, maxX = el.maxX + dx, maxY = el.maxY + dy,
            raw = el.raw?.let { shiftTree(it, dx, dy) }
        )
        is NativeVectorImageElement -> NativeVectorImageElement(
            el.svgData, el.intrinsicWidth, el.intrinsicHeight, el.halfExtentX, el.halfExtentY,
            shifted(el.transform, dx, dy), el.layer,
            el.minX + dx, el.minY + dy, el.maxX + dx, el.maxY + dy
        )
        is NativeBrushStroke -> el
    }

    private fun shifted(t: FloatArray, dx: Float, dy: Float) =
        floatArrayOf(t[0], t[1], t[2], t[3], t[4] + dx, t[5] + dy)

    private fun shiftOp(op: PathOp, dx: Float, dy: Float): PathOp = when (op) {
        is PathOp.MoveTo -> PathOp.MoveTo(op.x + dx, op.y + dy)
        is PathOp.LineTo -> PathOp.LineTo(op.x + dx, op.y + dy)
        is PathOp.QuadTo -> PathOp.QuadTo(op.x1 + dx, op.y1 + dy, op.x + dx, op.y + dy)
        is PathOp.CubicTo -> PathOp.CubicTo(op.x1 + dx, op.y1 + dy, op.x2 + dx, op.y2 + dy, op.x + dx, op.y + dy)
        PathOp.Close -> op
    }

    /** Point fields of Rnote's shapes (and the legacy corner/centre forms). */
    private val POINT_KEYS = setOf("start", "end", "tip", "cp", "cp1", "cp2", "top_left", "center")

    /**
     * A copy of an element's JSON with everything positional moved: every `affine`
     * transform's translation, every named point, and the vertices of a polyline's or
     * polygon's `path`. Colours, sizes and radii are left alone — nothing else in Rnote's
     * element JSON is a position. Styles are skipped outright, so a stroke width is never
     * mistaken for a coordinate.
     */
    private fun shiftTree(tree: JsonElement, dx: Float, dy: Float): JsonElement {
        val copy = tree.deepCopy()
        shiftIn(copy, dx, dy)
        return copy
    }

    private fun shiftIn(node: JsonElement, dx: Float, dy: Float) {
        if (!node.isJsonObject) return
        val obj = node.asJsonObject
        for ((key, value) in obj.entrySet()) {
            when {
                key == "style" || key == "text_style" -> Unit
                key == "affine" && value.isJsonArray && value.asJsonArray.size() == 9 -> {
                    val a = value.asJsonArray
                    a.set(6, JsonPrimitive(round3(a[6].asDouble + dx)))
                    a.set(7, JsonPrimitive(round3(a[7].asDouble + dy)))
                }
                key in POINT_KEYS && isPoint(value) -> shiftPoint(value.asJsonArray, dx, dy)
                key == "path" && value.isJsonArray -> value.asJsonArray.forEach {
                    if (isPoint(it)) shiftPoint(it.asJsonArray, dx, dy)
                }
                value.isJsonObject -> shiftIn(value, dx, dy)
            }
        }
    }

    private fun isPoint(e: JsonElement) =
        e.isJsonArray && e.asJsonArray.size() == 2 &&
            e.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }

    private fun shiftPoint(p: JsonArray, dx: Float, dy: Float) {
        p.set(0, JsonPrimitive(round3(p[0].asDouble + dx)))
        p.set(1, JsonPrimitive(round3(p[1].asDouble + dy)))
    }

    /** Rnote writes coordinates to three decimals; so do we. */
    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0

    // ── Hit testing ───────────────────────────────────────────────────────────

    /**
     * Whether the eraser square hits [el]'s outline — Rnote's eraser removes shapes the
     * way it removes ink, and leaves text and images alone (`trash_colliding_strokes`).
     */
    fun eraserHits(el: NativeShapeElement, left: Float, top: Float, right: Float, bottom: Float): Boolean {
        val pad = el.strokeWidth / 2f
        if (el.maxX + pad < left || el.minX - pad > right || el.maxY + pad < top || el.minY - pad > bottom) {
            return false
        }
        val l = left - pad; val t = top - pad; val r = right + pad; val b = bottom + pad
        // A filled shape is hit anywhere inside it, as it looks.
        if (el.fillColor.a > 0f && l <= el.maxX && r >= el.minX && t <= el.maxY && b >= el.minY) {
            val cx = (l + r) / 2f; val cy = (t + b) / 2f
            if (cx in el.minX..el.maxX && cy in el.minY..el.maxY) return true
        }
        for (polyline in outline(el.shape)) {
            for (i in 1 until polyline.size) {
                val (x1, y1) = polyline[i - 1]
                val (x2, y2) = polyline[i]
                if (segmentHitsBox(x1, y1, x2, y2, l, t, r, b)) return true
            }
            if (polyline.size == 1 && polyline[0].first in l..r && polyline[0].second in t..b) return true
        }
        return false
    }

    /** The shape's outline as polylines in document coordinates, curves flattened. */
    fun outline(shape: io.github.kjly.brna.model.NativeShapeKind): List<List<Pair<Float, Float>>> = when (shape) {
        is LineShape -> listOf(listOf(shape.x1 to shape.y1, shape.x2 to shape.y2))
        is RectShape -> {
            val t = shape.transform
            val hx = shape.halfExtentX; val hy = shape.halfExtentY
            listOf(listOf(-hx to -hy, hx to -hy, hx to hy, -hx to hy, -hx to -hy).map { (x, y) ->
                (t[0] * x + t[2] * y + t[4]) to (t[1] * x + t[3] * y + t[5])
            })
        }
        is EllipseShape -> {
            val t = shape.transform
            listOf((0..48).map { i ->
                val a = i * 2.0 * Math.PI / 48
                val x = shape.radiusX * cos(a).toFloat(); val y = shape.radiusY * sin(a).toFloat()
                (t[0] * x + t[2] * y + t[4]) to (t[1] * x + t[3] * y + t[5])
            })
        }
        is PathShape -> {
            val lines = mutableListOf<MutableList<Pair<Float, Float>>>()
            var cx = 0f; var cy = 0f; var sx = 0f; var sy = 0f
            for (op in shape.ops) {
                when (op) {
                    is PathOp.MoveTo -> { lines += mutableListOf(op.x to op.y); cx = op.x; cy = op.y; sx = cx; sy = cy }
                    is PathOp.LineTo -> { lines.lastOrNull()?.add(op.x to op.y); cx = op.x; cy = op.y }
                    is PathOp.QuadTo -> {
                        val line = lines.lastOrNull() ?: continue
                        for (i in 1..16) {
                            val u = i / 16f; val v = 1 - u
                            line += (v * v * cx + 2 * v * u * op.x1 + u * u * op.x) to
                                (v * v * cy + 2 * v * u * op.y1 + u * u * op.y)
                        }
                        cx = op.x; cy = op.y
                    }
                    is PathOp.CubicTo -> {
                        val line = lines.lastOrNull() ?: continue
                        for (i in 1..16) {
                            val u = i / 16f; val v = 1 - u
                            line += (v * v * v * cx + 3 * v * v * u * op.x1 + 3 * v * u * u * op.x2 + u * u * u * op.x) to
                                (v * v * v * cy + 3 * v * v * u * op.y1 + 3 * v * u * u * op.y2 + u * u * u * op.y)
                        }
                        cx = op.x; cy = op.y
                    }
                    PathOp.Close -> { lines.lastOrNull()?.add(sx to sy); cx = sx; cy = sy }
                }
            }
            lines
        }
    }

    /** Liang–Barsky: does the segment cross the box? */
    private fun segmentHitsBox(
        x1: Float, y1: Float, x2: Float, y2: Float,
        l: Float, t: Float, r: Float, b: Float
    ): Boolean {
        var t0 = 0f; var t1 = 1f
        val dx = x2 - x1; val dy = y2 - y1
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(x1 - l, r - x1, y1 - t, b - y1)
        for (i in 0..3) {
            if (p[i] == 0f) {
                if (q[i] < 0f) return false
            } else {
                val u = q[i] / p[i]
                if (p[i] < 0f) { if (u > t1) return false; if (u > t0) t0 = u }
                else { if (u < t0) return false; if (u < t1) t1 = u }
            }
        }
        return true
    }

    /**
     * Whether [el] lies entirely inside the lasso. Entirely, not partly: a lasso drawn
     * across an imported PDF page to catch the notes on it must not pick up the page.
     */
    fun insideLasso(el: NativeCanvasElement, polygon: List<Pair<Float, Float>>): Boolean {
        if (el is NativeBrushStroke || polygon.size < 3) return false
        return listOf(el.minX to el.minY, el.maxX to el.minY, el.maxX to el.maxY, el.minX to el.maxY)
            .all { (x, y) -> pointInPolygon(x, y, polygon) }
    }

    private fun pointInPolygon(x: Float, y: Float, polygon: List<Pair<Float, Float>>): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val (xi, yi) = polygon[i]
            val (xj, yj) = polygon[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    // ── Creating shapes ───────────────────────────────────────────────────────

    /**
     * A new shape dragged from ([x1], [y1]) to ([x2], [y2]), built as the JSON desktop
     * Rnote writes for its own shaper and read back through the normal parser, so a shape
     * drawn here is exactly what the file will hold. Rectangles and ellipses fill the
     * dragged box; null for a drag too short to be a shape.
     */
    fun createShape(
        kind: ShapeKind,
        x1: Float, y1: Float, x2: Float, y2: Float,
        color: RnoteNativeColor,
        strokeWidth: Float
    ): NativeShapeElement? {
        if (abs(x2 - x1) < 1f && abs(y2 - y1) < 1f) return null
        fun n(v: Float) = String.format(Locale.ROOT, "%.3f", v)
        fun point(x: Float, y: Float) = "[${n(x)},${n(y)}]"
        fun affine(cx: Float, cy: Float) = """{"affine":[1.0,0.0,0.0,0.0,1.0,0.0,${n(cx)},${n(cy)},1.0]}"""
        val cx = (x1 + x2) / 2f; val cy = (y1 + y2) / 2f
        val hx = abs(x2 - x1) / 2f; val hy = abs(y2 - y1) / 2f
        val shape = when (kind) {
            ShapeKind.LINE -> """{"line":{"start":${point(x1, y1)},"end":${point(x2, y2)}}}"""
            ShapeKind.ARROW -> """{"arrow":{"start":${point(x1, y1)},"tip":${point(x2, y2)}}}"""
            ShapeKind.RECTANGLE ->
                """{"rect":{"cuboid":{"half_extents":${point(hx, hy)}},"transform":${affine(cx, cy)}}}"""
            ShapeKind.ELLIPSE ->
                """{"ellipse":{"radii":${point(hx, hy)},"transform":${affine(cx, cy)}}}"""
        }
        fun color(c: RnoteNativeColor) = """{"r":${n(c.r)},"g":${n(c.g)},"b":${n(c.b)},"a":${n(c.a)}}"""
        val style = """{"smooth":{"stroke_width":${n(strokeWidth)},"stroke_color":${color(color)},""" +
            """"fill_color":${color(RnoteNativeColor.TRANSPARENT)},"pressure_curve":"const",""" +
            """"line_style":"solid","line_cap":"straight"}}"""
        return RnoteNativeParser.parseElementJson("""{"shapestroke":{"shape":$shape,"style":$style}}""")
            as? NativeShapeElement
    }
}

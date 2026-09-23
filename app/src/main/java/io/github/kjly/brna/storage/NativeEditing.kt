package io.github.kjly.brna.storage

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.github.kjly.brna.model.Affine
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
 * PDF pages — and creating new shapes and text. Every element that carries the JSON it was read
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

    // ── Scaling and rotating ──────────────────────────────────────────────────

    /**
     * [el] with the affine [m] applied — how the selector scales and rotates. Mirrors
     * Rnote's `Transformable` impls: points are mapped, every placement transform (a
     * rect's, an ellipse's, a text box's, an image's) has [m] composed onto it, and a
     * shape's line width is scaled by [Affine.widthFactor], as Rnote's `ShapeStroke::scale`
     * does. Text keeps its font size and is scaled through its transform instead, as in
     * Rnote. Brush strokes are returned unchanged: they live in the stroke list.
     */
    fun transform(el: NativeCanvasElement, m: FloatArray): NativeCanvasElement {
        val widthFactor = Affine.widthFactor(m)
        return when (el) {
            is NativeTextElement -> {
                val tree = (el.raw?.takeIf { it.isJsonObject } ?: textJson(el)).deepCopy()
                transformIn(tree, m, 1.0)
                RnoteNativeParser.parseElementTree(JsonObject().apply { add("textstroke", tree) })
                    as? NativeTextElement ?: el
            }
            is NativeShapeElement -> {
                val raw = el.raw
                if (raw != null) {
                    val tree = raw.deepCopy()
                    transformIn(tree, m, widthFactor.toDouble())
                    RnoteNativeParser.parseElementTree(JsonObject().apply { add("shapestroke", tree) })
                        as? NativeShapeElement ?: el
                } else {
                    val shape = mapShape(el.shape, m)
                    val b = outlineBounds(shape) ?: floatArrayOf(el.minX, el.minY, el.maxX, el.maxY)
                    el.copy(shape = shape, strokeWidth = el.strokeWidth * widthFactor,
                        minX = b[0], minY = b[1], maxX = b[2], maxY = b[3])
                }
            }
            is NativeBitmapElement -> {
                // Not re-read from its JSON: an older file's image would be decoded all
                // over again on every frame of the drag.
                val rect = el.rect?.let { it.copy(transform = Affine.compose(m, it.transform)) }
                val b = if (rect != null) boxBounds(rect.transform, rect.halfExtentX, rect.halfExtentY)
                else mappedBounds(m, el.minX, el.minY, el.maxX, el.maxY)
                el.copy(
                    transform = Affine.compose(m, el.transform), rect = rect,
                    minX = b[0], minY = b[1], maxX = b[2], maxY = b[3],
                    raw = el.raw?.deepCopy()?.also { transformIn(it, m, 1.0) }
                )
            }
            is NativeVectorImageElement -> {
                val t = Affine.compose(m, el.transform)
                val b = boxBounds(t, el.halfExtentX, el.halfExtentY)
                NativeVectorImageElement(
                    el.svgData, el.intrinsicWidth, el.intrinsicHeight, el.halfExtentX, el.halfExtentY,
                    t, el.layer, b[0], b[1], b[2], b[3]
                )
            }
            is NativeBrushStroke -> el
        }
    }

    /** Applies [m] to every position in an element's JSON; see [shiftTree] for what counts. */
    private fun transformIn(node: JsonElement, m: FloatArray, widthFactor: Double) {
        if (!node.isJsonObject) return
        for ((key, value) in node.asJsonObject.entrySet()) {
            when {
                key == "text_style" -> Unit
                key == "style" -> if (widthFactor != 1.0) scaleWidths(value, widthFactor)
                key == "affine" && value.isJsonArray && value.asJsonArray.size() == 9 -> {
                    val a = value.asJsonArray
                    // Composed in double precision and written unrounded: a rotation's
                    // cosines rounded to three places would visibly shrink a large page.
                    val t = doubleArrayOf(a[0].asDouble, a[1].asDouble, a[3].asDouble, a[4].asDouble, a[6].asDouble, a[7].asDouble)
                    val r = composeD(m, t)
                    a.set(0, JsonPrimitive(r[0])); a.set(1, JsonPrimitive(r[1]))
                    a.set(3, JsonPrimitive(r[2])); a.set(4, JsonPrimitive(r[3]))
                    a.set(6, JsonPrimitive(r[4])); a.set(7, JsonPrimitive(r[5]))
                }
                key in POINT_KEYS && isPoint(value) -> mapPoint(value.asJsonArray, m)
                key == "path" && value.isJsonArray -> value.asJsonArray.forEach {
                    if (isPoint(it)) mapPoint(it.asJsonArray, m)
                }
                value.isJsonObject -> transformIn(value, m, widthFactor)
            }
        }
    }

    /** Multiplies every `stroke_width` in a style (Rnote's smooth, rough and textured all have one). */
    private fun scaleWidths(node: JsonElement, factor: Double) {
        if (!node.isJsonObject) return
        val obj = node.asJsonObject
        for ((key, value) in obj.entrySet()) {
            if (key == "stroke_width" && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
                obj.add(key, JsonPrimitive(round3(value.asDouble * factor)))
            } else if (value.isJsonObject) {
                scaleWidths(value, factor)
            }
        }
    }

    private fun composeD(m: FloatArray, t: DoubleArray): DoubleArray {
        val m0 = m[0].toDouble(); val m1 = m[1].toDouble(); val m2 = m[2].toDouble()
        val m3 = m[3].toDouble(); val m4 = m[4].toDouble(); val m5 = m[5].toDouble()
        return doubleArrayOf(
            m0 * t[0] + m2 * t[1], m1 * t[0] + m3 * t[1],
            m0 * t[2] + m2 * t[3], m1 * t[2] + m3 * t[3],
            m0 * t[4] + m2 * t[5] + m4, m1 * t[4] + m3 * t[5] + m5
        )
    }

    private fun mapPoint(p: JsonArray, m: FloatArray) {
        val x = p[0].asDouble; val y = p[1].asDouble
        p.set(0, JsonPrimitive(round3(m[0] * x + m[2] * y + m[4])))
        p.set(1, JsonPrimitive(round3(m[1] * x + m[3] * y + m[5])))
    }

    private fun mapShape(shape: io.github.kjly.brna.model.NativeShapeKind, m: FloatArray) = when (shape) {
        is LineShape -> LineShape(
            Affine.mapX(m, shape.x1, shape.y1), Affine.mapY(m, shape.x1, shape.y1),
            Affine.mapX(m, shape.x2, shape.y2), Affine.mapY(m, shape.x2, shape.y2)
        )
        is RectShape -> shape.copy(transform = Affine.compose(m, shape.transform))
        is EllipseShape -> shape.copy(transform = Affine.compose(m, shape.transform))
        is PathShape -> PathShape(shape.ops.map { op ->
            fun x(px: Float, py: Float) = Affine.mapX(m, px, py)
            fun y(px: Float, py: Float) = Affine.mapY(m, px, py)
            when (op) {
                is PathOp.MoveTo -> PathOp.MoveTo(x(op.x, op.y), y(op.x, op.y))
                is PathOp.LineTo -> PathOp.LineTo(x(op.x, op.y), y(op.x, op.y))
                is PathOp.QuadTo -> PathOp.QuadTo(x(op.x1, op.y1), y(op.x1, op.y1), x(op.x, op.y), y(op.x, op.y))
                is PathOp.CubicTo -> PathOp.CubicTo(
                    x(op.x1, op.y1), y(op.x1, op.y1), x(op.x2, op.y2), y(op.x2, op.y2), x(op.x, op.y), y(op.x, op.y)
                )
                PathOp.Close -> op
            }
        })
    }

    private fun outlineBounds(shape: io.github.kjly.brna.model.NativeShapeKind): FloatArray? {
        val pts = outline(shape).flatten()
        if (pts.isEmpty()) return null
        return floatArrayOf(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second })
    }

    /** Axis-aligned bounds of the box ±([hx], [hy]) placed by [t]. */
    private fun boxBounds(t: FloatArray, hx: Float, hy: Float): FloatArray =
        mappedBounds(t, -hx, -hy, hx, hy)

    private fun mappedBounds(m: FloatArray, l: Float, t: Float, r: Float, b: Float): FloatArray {
        val xs = floatArrayOf(Affine.mapX(m, l, t), Affine.mapX(m, r, t), Affine.mapX(m, r, b), Affine.mapX(m, l, b))
        val ys = floatArrayOf(Affine.mapY(m, l, t), Affine.mapY(m, r, t), Affine.mapY(m, r, b), Affine.mapY(m, l, b))
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

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

    // ── Text (the Typewriter) ─────────────────────────────────────────────────

    /**
     * How wide a new text box may grow before it wraps: Rnote's typewriter default of 600,
     * narrowed so text typed on a page wraps at that page's right edge rather than running
     * off it — unless that would leave a column too narrow to write in.
     */
    fun typewriterWrapWidth(x: Float, pageWidth: Float): Float {
        if (pageWidth <= 0f) return TEXT_WIDTH_DEFAULT
        val pageRight = (kotlin.math.floor(x / pageWidth) + 1f) * pageWidth
        val room = pageRight - x - TEXT_PAGE_MARGIN
        return if (room >= TEXT_WIDTH_MIN) minOf(TEXT_WIDTH_DEFAULT, room) else TEXT_WIDTH_DEFAULT
    }

    /**
     * A new text box with its top-left corner at ([x], [y]), written the way desktop
     * Rnote's typewriter writes one and read back through the normal parser. Null for
     * text that is only whitespace, which Rnote doesn't keep either.
     */
    fun createText(
        text: String,
        x: Float, y: Float,
        fontSize: Float,
        color: RnoteNativeColor,
        maxWidth: Float?
    ): NativeTextElement? {
        if (text.isBlank()) return null
        val style = JsonObject().apply {
            addProperty("font_family", TEXT_FONT_FAMILY)
            addProperty("font_size", round3(fontSize.toDouble()))
            addProperty("font_weight", 500)
            addProperty("font_style", "regular")
            add("color", colorJson(color))
            if (maxWidth != null) addProperty("max_width", round3(maxWidth.toDouble()))
            else add("max_width", JsonNull.INSTANCE)
            addProperty("alignment", "start")
            add("ranged_text_attributes", JsonArray())
        }
        val obj = JsonObject().apply {
            addProperty("text", text)
            add("transform", affineJson(floatArrayOf(1f, 0f, 0f, 1f, x, y)))
            add("text_style", style)
        }
        return parseText(obj)
    }

    /**
     * [el] with its text replaced, everything else kept — font, colour, position, and
     * the bold/italic/underline ranges desktop Rnote put on parts of it, which are moved
     * along with the text around the edit. Null when the new text is blank: the caller
     * deletes the box, as Rnote does with an emptied one.
     */
    fun withText(el: NativeTextElement, newText: String): NativeTextElement? {
        if (newText.isBlank()) return null
        val obj = el.raw?.takeIf { it.isJsonObject }?.deepCopy()?.asJsonObject ?: textJson(el)
        obj.addProperty("text", newText)
        val style = obj.get("text_style")
        if (style != null && style.isJsonObject) {
            val ranges = style.asJsonObject.get("ranged_text_attributes")
            if (ranges != null && ranges.isJsonArray) {
                style.asJsonObject.add("ranged_text_attributes", shiftRanges(ranges.asJsonArray, el.text, newText))
            }
        }
        return parseText(obj)
    }

    /** The topmost text box at ([x], [y]), if any; [slop] widens each box for a fingertip. */
    fun textAt(elements: List<NativeCanvasElement>, x: Float, y: Float, slop: Float = 0f): NativeTextElement? =
        elements.lastOrNull {
            it is NativeTextElement &&
                x >= it.minX - slop && x <= it.maxX + slop && y >= it.minY - slop && y <= it.maxY + slop
        } as NativeTextElement?

    /**
     * Moves Rnote's `ranged_text_attributes` from [old] to [new]. Ranges are UTF-8 byte
     * offsets (Rust string indices). The edit is taken to be the part between the two
     * texts' common prefix and suffix: ranges before it stay, ranges after it shift,
     * a range around it grows or shrinks with it, and whatever of a range lay inside the
     * replaced part is dropped.
     */
    internal fun shiftRanges(ranges: JsonArray, old: String, new: String): JsonArray {
        var p = 0
        val maxP = minOf(old.length, new.length)
        while (p < maxP && old[p] == new[p]) p++
        // Never split a surrogate pair: back off to the start of the character.
        if (p in 1 until old.length && Character.isLowSurrogate(old[p])) p--
        var s = 0
        val maxS = minOf(old.length, new.length) - p
        while (s < maxS && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        if (s > 0 && Character.isLowSurrogate(old[old.length - s])) s--

        val prefixEnd = utf8Length(old, 0, p)
        val oldChangeEnd = utf8Length(old, 0, old.length - s)
        val newLength = utf8Length(new, 0, new.length)
        val delta = newLength - utf8Length(old, 0, old.length)

        val out = JsonArray()
        for (item in ranges) {
            if (!item.isJsonObject) continue
            val range = item.asJsonObject.get("range")
            if (range == null || !range.isJsonObject) { out.add(item); continue }
            val a = range.asJsonObject.get("start")?.asInt ?: continue
            val b = range.asJsonObject.get("end")?.asInt ?: continue
            val na = when {
                a <= prefixEnd -> a
                a >= oldChangeEnd -> a + delta
                else -> oldChangeEnd + delta
            }.coerceIn(0, newLength)
            val nb = when {
                b <= prefixEnd -> b
                b >= oldChangeEnd -> b + delta
                else -> prefixEnd
            }.coerceIn(0, newLength)
            if (nb <= na) continue
            val moved = item.asJsonObject.deepCopy()
            moved.add("range", JsonObject().apply {
                addProperty("start", na)
                addProperty("end", nb)
            })
            out.add(moved)
        }
        return out
    }

    private fun utf8Length(s: String, from: Int, to: Int): Int =
        s.substring(from, to).toByteArray(Charsets.UTF_8).size

    /** JSON for a text element that came without the JSON it was read from. */
    private fun textJson(el: NativeTextElement): JsonObject = JsonObject().apply {
        addProperty("text", el.text)
        add("transform", affineJson(el.transform))
        add("text_style", JsonObject().apply {
            addProperty("font_family", el.fontFamily)
            addProperty("font_size", round3(el.fontSize.toDouble()))
            addProperty("font_weight", el.fontWeight)
            addProperty("font_style", if (el.italic) "italic" else "regular")
            add("color", colorJson(el.color))
            val w = el.maxWidth
            if (w != null) addProperty("max_width", round3(w.toDouble())) else add("max_width", JsonNull.INSTANCE)
            addProperty("alignment", el.alignment)
            add("ranged_text_attributes", JsonArray())
        })
    }

    private fun parseText(inner: JsonObject): NativeTextElement? {
        val wrapper = JsonObject().apply { add("textstroke", inner) }
        return RnoteNativeParser.parseElementJson(wrapper.toString()) as? NativeTextElement
    }

    /** Rnote's column-major 3×3 `affine` for our [a, b, c, d, tx, ty]. */
    private fun affineJson(t: FloatArray) = JsonObject().apply {
        add("affine", JsonArray().apply {
            listOf(t[0], t[1], 0f, t[2], t[3], 0f, t[4], t[5], 1f).forEach { add(round3(it.toDouble())) }
        })
    }

    private fun colorJson(c: RnoteNativeColor) = JsonObject().apply {
        addProperty("r", round3(c.r.toDouble()))
        addProperty("g", round3(c.g.toDouble()))
        addProperty("b", round3(c.b.toDouble()))
        addProperty("a", round3(c.a.toDouble()))
    }

    /** Rnote's `TextStyle::FONT_FAMILY_DEFAULT`. */
    const val TEXT_FONT_FAMILY = "serif"
    /** Rnote's typewriter `text_width` default. */
    const val TEXT_WIDTH_DEFAULT = 600f
    private const val TEXT_WIDTH_MIN = 150f
    private const val TEXT_PAGE_MARGIN = 20f
}

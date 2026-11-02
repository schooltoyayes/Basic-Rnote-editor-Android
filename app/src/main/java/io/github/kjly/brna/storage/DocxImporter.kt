package io.github.kjly.brna.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.TabStopSpan
import android.text.style.UnderlineSpan
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Sets a Word document as the pages of a PDF, which then goes into the note through the
 * PDF import like any other PDF. Android can't show a `.docx`, but it renders a PDF it
 * wrote itself, and this way a Word page lands in the note as a page of paper to write
 * on, just as an imported PDF page does.
 *
 * The pages are laid out here from what [DocxReader] reads: close to Word's, not the same.
 * A font the tablet doesn't have is drawn in its serif, sans or monospace face (or in one
 * loaded through "Fonts…" under the same name), so lines can break in other places.
 */
internal object DocxImporter {

    /**
     * Layout units per point. StaticLayout measures widths and line heights in whole units,
     * too coarse in points at a body text's 11 pt; laid out at 20 to the point (Word's own
     * twips) and drawn scaled back down, the rounding is lost in the print.
     */
    private const val S = 20f

    /** Word's default room between a cell's border and its text, left and right. */
    private const val CELL_SIDE = 5.4f

    /** The thinnest of Word's borders, ½ pt. */
    private const val BORDER = 0.5f

    /** The PDF of the Word document at [uri], written to [out]. Blocking; throws when it can't be read. */
    fun convert(context: Context, uri: Uri, out: File) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot open $uri")
        val document = DocxReader.read(bytes)
        val pdf = PdfDocument()
        try {
            Pages(pdf, document).write()
            out.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    /** Something laid out at a width, [height] points tall, drawn whole with its top left at a point. */
    private interface Laid {
        val height: Float
        fun draw(canvas: Canvas, x: Float, y: Float)
    }

    /**
     * A paragraph set by [layout], whose left edge is [x] from the column's. [labelX] is
     * where the list label starts, on the first line's baseline.
     */
    private class LaidParagraph(
        val layout: StaticLayout,
        val x: Float,
        val before: Float,
        val after: Float,
        val pageBreakBefore: Boolean,
        val label: String?,
        val labelPaint: TextPaint?,
        val labelX: Float,
        val shading: Int?,
        val shadeLeft: Float,
        val shadeRight: Float
    ) : Laid {
        val lineCount get() = layout.lineCount

        override val height get() = before + layout.height / S + after

        /** The height of lines [from] to [to] (exclusive), in points. */
        fun linesHeight(from: Int, to: Int) = (layout.getLineBottom(to - 1) - layout.getLineTop(from)) / S

        /** Lines [from] to [to] (exclusive) with their top at [y], in a column whose left is [columnX]. */
        fun drawLines(canvas: Canvas, columnX: Float, y: Float, from: Int, to: Int) {
            val top = layout.getLineTop(from).toFloat()
            val bottom = layout.getLineBottom(to - 1).toFloat()
            shading?.let {
                canvas.drawRect(columnX + shadeLeft, y, columnX + shadeRight, y + (bottom - top) / S, fill(it))
            }
            canvas.save()
            canvas.translate(columnX + x, y)
            canvas.scale(1f / S, 1f / S)
            canvas.translate(0f, -top)
            // Layout.draw only draws the lines the clip lets through.
            canvas.clipRect(-1e6f, top, 1e6f, bottom)
            layout.draw(canvas)
            canvas.restore()
            if (from == 0 && label != null && labelPaint != null) {
                canvas.save()
                canvas.translate(columnX + labelX, y + (layout.getLineBaseline(0) - top) / S)
                canvas.scale(1f / S, 1f / S)
                canvas.drawText(label, 0f, 0f, labelPaint)
                canvas.restore()
            }
        }

        override fun draw(canvas: Canvas, x: Float, y: Float) {
            drawLines(canvas, x, y + before, 0, lineCount)
        }
    }

    /** A cell [x] from the table's left, [width] wide. [openBelow]: the cell under it continues it. */
    private class LaidCell(
        val x: Float,
        val width: Float,
        val content: List<Laid>,
        val shading: Int?,
        val mergedAbove: Boolean,
        var openBelow: Boolean = false
    )

    private class LaidRow(val height: Float, val cells: List<LaidCell>)

    private class LaidTable(val rows: List<LaidRow>, val borders: Boolean) : Laid {
        override val height get() = rows.sumOf { it.height.toDouble() }.toFloat()

        fun drawRow(canvas: Canvas, row: LaidRow, x: Float, y: Float) {
            for (cell in row.cells) {
                cell.shading?.let {
                    canvas.drawRect(x + cell.x, y, x + cell.x + cell.width, y + row.height, fill(it))
                }
                var cy = y
                for (laid in cell.content) {
                    laid.draw(canvas, x + cell.x + CELL_SIDE, cy)
                    cy += laid.height
                }
            }
            if (!borders) return
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = BORDER
                color = Color.BLACK
            }
            for (cell in row.cells) {
                val l = x + cell.x
                val r = l + cell.width
                val b = y + row.height
                canvas.drawLine(l, y, l, b, line)
                canvas.drawLine(r, y, r, b, line)
                if (!cell.mergedAbove) canvas.drawLine(l, y, r, y, line)
                if (!cell.openBelow) canvas.drawLine(l, b, r, b, line)
            }
        }

        override fun draw(canvas: Canvas, x: Float, y: Float) {
            var top = y
            for (row in rows) {
                drawRow(canvas, row, x, top)
                top += row.height
            }
        }
    }

    private fun fill(rgb: Int) = Paint().apply {
        style = Paint.Style.FILL
        color = opaque(rgb)
    }

    private fun opaque(rgb: Int) = rgb or 0xFF000000.toInt()

    /** The pages of [doc], written into [pdf] one after the other. */
    private class Pages(private val pdf: PdfDocument, private val doc: DocxDocument) {
        private val page = doc.page
        private val columnX = page.marginLeft
        private val columnWidth = (page.width - page.marginLeft - page.marginRight).coerceAtLeast(36f)
        private val top = page.marginTop
        private val bottom = (page.height - page.marginBottom).coerceAtLeast(top + 36f)

        private var current: PdfDocument.Page? = null
        private var pageNumber = 0
        private var y = top

        /** Nothing is on the current page yet: what doesn't fit goes on it all the same. */
        private var empty = true

        private val faces = HashMap<String, Typeface>()

        fun write() {
            for (block in doc.blocks) when (block) {
                is DocxPageBreak -> {
                    canvas()
                    finishPage()
                }
                is DocxParagraph -> place(paragraph(block, columnWidth))
                is DocxTable -> place(table(block, columnWidth))
            }
            if (current != null || pageNumber == 0) {
                canvas()
                finishPage()
            }
        }

        private fun canvas(): Canvas {
            val open = current ?: pdf.startPage(
                PdfDocument.PageInfo.Builder(
                    page.width.roundToInt().coerceAtLeast(1),
                    page.height.roundToInt().coerceAtLeast(1),
                    ++pageNumber
                ).create()
            ).also {
                current = it
                y = top
                empty = true
            }
            return open.canvas
        }

        private fun finishPage() {
            current?.let { pdf.finishPage(it) }
            current = null
        }

        private fun place(p: LaidParagraph) {
            if (p.pageBreakBefore && !empty) finishPage()
            var canvas = canvas()
            y += p.before
            var line = 0
            while (line < p.lineCount) {
                var end = line
                while (end < p.lineCount && y + p.linesHeight(line, end + 1) <= bottom + 0.01f) end++
                if (end == line) {
                    if (!empty) {
                        finishPage()
                        canvas = canvas()
                        continue
                    }
                    // A line taller than the page: it goes on a page of its own, cut off.
                    end = line + 1
                }
                p.drawLines(canvas, columnX, y, line, end)
                empty = false
                y += p.linesHeight(line, end)
                line = end
            }
            y += p.after
        }

        /** A table row by row: a row that doesn't fit goes on the next page whole. */
        private fun place(t: LaidTable) {
            var canvas = canvas()
            for (row in t.rows) {
                if (y + row.height > bottom + 0.01f && !empty) {
                    finishPage()
                    canvas = canvas()
                }
                t.drawRow(canvas, row, columnX, y)
                empty = false
                y += row.height
            }
        }

        private fun laid(block: DocxBlock, width: Float): Laid? = when (block) {
            is DocxParagraph -> paragraph(block, width)
            is DocxTable -> table(block, width)
            is DocxPageBreak -> null
        }

        private fun paragraph(p: DocxParagraph, width: Float): LaidParagraph {
            val text = SpannableStringBuilder()
            for (inline in p.inlines) when (inline) {
                is DocxText -> append(text, inline.text, inline.style)
                is DocxImage -> picture(inline, width - p.indentLeft - p.indentRight)?.let { span ->
                    val start = text.length
                    text.append('￼')
                    text.setSpan(span, start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            // An empty paragraph is a blank line as tall as its mark's font.
            if (text.isEmpty()) append(text, "​", p.markStyle)

            val labelPaint = p.label?.let { label -> paint(label.style) }
            val left = p.indentLeft
            val firstStart = left + p.firstLine
            // After a label comes a tab: to the indent when the label fits before it, as a
            // hanging indent has it, or else to the next default tab stop.
            val firstText = if (p.label != null && labelPaint != null) {
                val labelEnd = firstStart + labelPaint.measureText(p.label.text) / S
                if (labelEnd <= left + 0.01f) left else nextTab(labelEnd)
            } else firstStart
            val origin = min(left, firstText)
            val layoutWidth = (width - origin - p.indentRight).coerceAtLeast(12f)

            val firstMargin = ((firstText - origin) * S).roundToInt()
            val restMargin = ((left - origin) * S).roundToInt()
            if (firstMargin != 0 || restMargin != 0) {
                // Only the paragraph's first line is indented as one: a line break within it
                // starts a new paragraph for StaticLayout, but not for Word.
                val firstBreak = text.indexOf('\n').let { if (it < 0) text.length else it + 1 }
                text.setSpan(LeadingMarginSpan.Standard(firstMargin, restMargin), 0, firstBreak, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                if (firstBreak < text.length) {
                    text.setSpan(LeadingMarginSpan.Standard(restMargin), firstBreak, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                }
            }
            if (text.contains('\t')) {
                var stop = doc.tabStop
                while (stop < layoutWidth) {
                    text.setSpan(TabStopSpan.Standard((stop * S).roundToInt()), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    stop += doc.tabStop.coerceAtLeast(1f)
                }
            }
            text.setSpan(LineSpacingSpan(p.lineSpacing), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

            val builder = StaticLayout.Builder
                .obtain(text, 0, text.length, paint(p.markStyle), (layoutWidth * S).roundToInt())
                .setAlignment(
                    when (p.align) {
                        DocxAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                        DocxAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                        else -> Layout.Alignment.ALIGN_NORMAL
                    }
                )
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
            if (p.align == DocxAlign.JUSTIFY) builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)

            return LaidParagraph(
                layout = builder.build(),
                x = origin,
                before = p.spaceBefore,
                after = p.spaceAfter,
                pageBreakBefore = p.pageBreakBefore,
                label = p.label?.text,
                labelPaint = labelPaint,
                labelX = firstStart,
                shading = p.shading,
                shadeLeft = left,
                shadeRight = width - p.indentRight
            )
        }

        private fun nextTab(x: Float): Float {
            val step = doc.tabStop.coerceAtLeast(1f)
            return ceil((x + 0.01f) / step) * step
        }

        private fun append(text: SpannableStringBuilder, s: String, style: DocxRunStyle) {
            if (s.isEmpty()) return
            val start = text.length
            text.append(s)
            val end = text.length
            val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            text.setSpan(RunSpan(style, typeface(style)), start, end, flags)
            style.color?.let { text.setSpan(ForegroundColorSpan(opaque(it)), start, end, flags) }
            style.highlight?.let { text.setSpan(BackgroundColorSpan(opaque(it)), start, end, flags) }
            if (style.underline) text.setSpan(UnderlineSpan(), start, end, flags)
            if (style.strike) text.setSpan(StrikethroughSpan(), start, end, flags)
        }

        private fun paint(style: DocxRunStyle): TextPaint {
            val run = RunSpan(style, typeface(style))
            return TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).also {
                it.color = style.color?.let(::opaque) ?: Color.BLACK
                run.applyTo(it)
            }
        }

        /**
         * A font by its name: one loaded through "Fonts…" under it, or else the tablet's
         * face of the same kind — serif, sans or monospace.
         */
        private fun typeface(style: DocxRunStyle): Typeface =
            faces.getOrPut("${style.font}|${style.bold}|${style.italic}") {
                val family = CustomFonts.get(style.font)
                    ?: Typeface.create(DocxReader.genericFamily(style.font), Typeface.NORMAL)
                Typeface.create(
                    family,
                    (if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0)
                )
            }

        /**
         * A picture at its size in the document, shrunk to fit [maxWidth] and the page. It is
         * decoded at about three pixels to the point, sharp where the import renders the
         * page at two; one Android can't decode (a Windows metafile, say) is an empty frame.
         */
        private fun picture(image: DocxImage, maxWidth: Float): PictureSpan? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image.data, 0, image.data.size, bounds)
            val pxW = bounds.outWidth
            val pxH = bounds.outHeight
            var w = image.width.takeIf { it > 0f } ?: (pxW * 0.75f)
            var h = image.height.takeIf { it > 0f } ?: (pxH * 0.75f)
            if (w <= 0f || h <= 0f) return null
            val fit = min(1f, min(maxWidth.coerceAtLeast(12f) / w, (bottom - top) / h))
            w *= fit
            h *= fit
            val bitmap = if (pxW > 0 && pxH > 0) {
                var sample = 1
                while (pxW / (sample * 2) >= w * 3 && pxH / (sample * 2) >= h * 3) sample *= 2
                try {
                    BitmapFactory.decodeByteArray(
                        image.data, 0, image.data.size,
                        BitmapFactory.Options().apply { inSampleSize = sample }
                    )
                } catch (e: OutOfMemoryError) {
                    null
                }
            } else null
            return PictureSpan(bitmap, w * S, h * S)
        }

        private fun table(t: DocxTable, width: Float): LaidTable {
            val gridCount = max(t.columns.size, t.rows.maxOfOrNull { row -> row.sumOf { it.span } } ?: 0)
                .coerceAtLeast(1)
            // Columns without a width share what the others leave; all shrink to fit the column.
            val known = (0 until gridCount).map { t.columns.getOrNull(it)?.takeIf { w -> w > 0f } }
            val missing = known.count { it == null }
            val rest = (width - known.sumOf { (it ?: 0f).toDouble() }.toFloat()).coerceAtLeast(0f)
            val raw = known.map { it ?: if (rest > 0f) rest / missing else width / gridCount }
            val scale = min(1f, width / raw.sum().coerceAtLeast(1f))
            val edges = FloatArray(gridCount + 1)
            for (i in 0 until gridCount) edges[i + 1] = edges[i] + raw[i] * scale

            val rows = t.rows.map { row ->
                var grid = 0
                val cells = row.map { cell ->
                    val from = grid.coerceAtMost(gridCount - 1)
                    val to = (grid + cell.span).coerceIn(from + 1, gridCount)
                    grid += cell.span
                    val cellWidth = edges[to] - edges[from]
                    val content = if (cell.mergedAbove) emptyList() else
                        cell.blocks.mapNotNull { laid(it, (cellWidth - 2 * CELL_SIDE).coerceAtLeast(12f)) }
                    LaidCell(edges[from], cellWidth, content, cell.shading, cell.mergedAbove)
                }
                val height = cells.maxOfOrNull { c -> c.content.sumOf { it.height.toDouble() }.toFloat() } ?: 0f
                LaidRow(height.coerceAtLeast(1f), cells)
            }
            for (i in 0 until rows.size - 1) for (cell in rows[i].cells) {
                cell.openBelow = rows[i + 1].cells.any { it.mergedAbove && kotlin.math.abs(it.x - cell.x) < 0.5f }
            }
            return LaidTable(rows, t.borders)
        }
    }

    /** A run's font, size, weight, slant and raise, in layout units. */
    private class RunSpan(private val style: DocxRunStyle, private val typeface: Typeface) : MetricAffectingSpan() {
        override fun updateMeasureState(paint: TextPaint) = applyTo(paint)

        override fun updateDrawState(paint: TextPaint) = applyTo(paint)

        fun applyTo(paint: TextPaint) {
            paint.typeface = typeface
            val size = style.size * S
            when (style.vertAlign) {
                DocxVertAlign.BASELINE -> paint.textSize = size
                DocxVertAlign.SUPER -> {
                    paint.textSize = size * 0.65f
                    paint.baselineShift -= (size * 0.35f).roundToInt()
                }
                DocxVertAlign.SUB -> {
                    paint.textSize = size * 0.65f
                    paint.baselineShift += (size * 0.15f).roundToInt()
                }
            }
            // A face without a bold or italic of its own gets one made up, as StyleSpan does.
            val missing = ((if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0)) and
                typeface.style.inv()
            paint.isFakeBoldText = missing and Typeface.BOLD != 0
            paint.textSkewX = if (missing and Typeface.ITALIC != 0) -0.25f else 0f
        }
    }

    /** Word's line spacing on each line, the extra room going above the text as in Word. */
    private class LineSpacingSpan(private val spacing: DocxLineSpacing) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence?, start: Int, end: Int, spanstartv: Int, lineHeight: Int,
            fm: Paint.FontMetricsInt
        ) {
            val natural = fm.descent - fm.ascent
            val wanted = when (spacing) {
                is DocxLineSpacing.Multiple -> (natural * spacing.factor).roundToInt()
                is DocxLineSpacing.Exact -> (spacing.points * S).roundToInt()
                is DocxLineSpacing.AtLeast -> max(natural, (spacing.points * S).roundToInt())
            }
            fm.ascent -= wanted - natural
            fm.top = min(fm.top, fm.ascent)
        }
    }

    /** A picture standing on the baseline, [width] by [height] layout units. */
    private class PictureSpan(
        private val bitmap: Bitmap?,
        private val width: Float,
        private val height: Float
    ) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let {
                it.ascent = -height.roundToInt()
                it.top = it.ascent
                it.descent = 0
                it.bottom = 0
            }
            return width.roundToInt()
        }

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint
        ) {
            val rect = RectF(x, y - height, x + width, y.toFloat())
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                canvas.drawRect(rect, Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = S
                    color = Color.LTGRAY
                })
            }
        }
    }
}

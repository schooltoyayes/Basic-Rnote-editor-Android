package io.github.kjly.brna.storage

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** A Word document's page, in points (1/72 inch), as its last section sets it. */
internal data class DocxPage(
    val width: Float,
    val height: Float,
    val marginTop: Float,
    val marginRight: Float,
    val marginBottom: Float,
    val marginLeft: Float
)

internal enum class DocxAlign { LEFT, CENTER, RIGHT, JUSTIFY }

internal enum class DocxVertAlign { BASELINE, SUPER, SUB }

/** How a run of text looks. [color] and [highlight] are 0xRRGGBB, null for Word's "auto" and none. */
internal data class DocxRunStyle(
    val font: String,
    val size: Float,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val color: Int? = null,
    val highlight: Int? = null,
    val vertAlign: DocxVertAlign = DocxVertAlign.BASELINE
)

internal sealed interface DocxInline

internal data class DocxText(val text: String, val style: DocxRunStyle) : DocxInline

/** A picture in the text, [width] by [height] points; 0 where the file doesn't say. */
internal class DocxImage(val data: ByteArray, val width: Float, val height: Float) : DocxInline

/** Word's line spacing: a multiple of single spacing, or a height in points. */
internal sealed interface DocxLineSpacing {
    data class Multiple(val factor: Float) : DocxLineSpacing
    data class Exact(val points: Float) : DocxLineSpacing
    data class AtLeast(val points: Float) : DocxLineSpacing
}

internal sealed interface DocxBlock

/**
 * A paragraph. Indents and spacing are in points; [firstLine] is the first line's indent
 * from [indentLeft], negative for a hanging one. [label] is a list item's number or bullet,
 * set where the first line starts. [markStyle] is the paragraph mark's: the height of an
 * empty paragraph.
 */
internal data class DocxParagraph(
    val inlines: List<DocxInline>,
    val markStyle: DocxRunStyle,
    val align: DocxAlign = DocxAlign.LEFT,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 0f,
    val lineSpacing: DocxLineSpacing = DocxLineSpacing.Multiple(1f),
    val indentLeft: Float = 0f,
    val indentRight: Float = 0f,
    val firstLine: Float = 0f,
    val label: DocxText? = null,
    val pageBreakBefore: Boolean = false,
    val shading: Int? = null
) : DocxBlock

/** A hard page break, from a break run or one between paragraphs. */
internal object DocxPageBreak : DocxBlock

/** A table: its grid's column widths in points and its rows of cells. */
internal data class DocxTable(
    val columns: List<Float>,
    val rows: List<List<DocxCell>>,
    val borders: Boolean
) : DocxBlock

/** A cell over [span] grid columns. [mergedAbove] continues the cell above it, empty as Word shows it. */
internal data class DocxCell(
    val blocks: List<DocxBlock>,
    val span: Int = 1,
    val mergedAbove: Boolean = false,
    val shading: Int? = null
)

internal class DocxDocument(val page: DocxPage, val blocks: List<DocxBlock>, val tabStop: Float)

/**
 * Reads a Word `.docx` file for how it looks on paper: the page and its margins, the
 * paragraphs with their styles from `styles.xml`, list numbers and bullets from
 * `numbering.xml`, tables and pictures. What only Word can lay out — headers and
 * footers, footnotes, text boxes, floating shapes, columns — is left out; a floating
 * picture is set in the text where it is anchored.
 */
internal object DocxReader {

    class ParseException(message: String) : IOException(message)

    /** Larger than any real document, so a zip that unpacks without end is turned away. */
    private const val MAX_UNPACKED = 256L * 1024 * 1024

    /** Word's defaults without a `docDefaults`: Times New Roman, 10 pt. */
    private const val FALLBACK_FONT = "Times New Roman"
    private const val FALLBACK_SIZE = 10f

    fun read(docx: ByteArray): DocxDocument {
        val files = unzip(docx)
        val document = files["word/document.xml"]?.let(::parse)
            ?: throw ParseException("Not a Word document: no word/document.xml")
        val theme = files["word/theme/theme1.xml"]?.let(::parse)?.let(::themeFonts) ?: ThemeFonts()
        val styles = Styles(files["word/styles.xml"]?.let(::parse), theme)
        val numbering = Numbering(files["word/numbering.xml"]?.let(::parse), styles)
        val relations = files["word/_rels/document.xml.rels"]?.let(::parse)?.let(::relationTargets).orEmpty()
        val tabStop = files["word/settings.xml"]?.let(::parse)
            ?.child("defaultTabStop")?.attr("val")?.toFloatOrNull()?.let(::twips) ?: 36f

        val body = document.child("body") ?: throw ParseException("No document body")
        val reader = BodyReader(styles, numbering) { id ->
            relations[id]?.let { files[it] }
        }
        val page = (body.child("sectPr") ?: body.descendants("sectPr").lastOrNull())
            ?.let(::page) ?: DEFAULT_PAGE
        return DocxDocument(page, reader.blocks(body), tabStop)
    }

    // ── Package ─────────────────────────────────────────────────────────────

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = HashMap<String, ByteArray>()
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.startsWith("word/")) continue
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNPACKED) throw ParseException("Document too large")
                        out.write(buffer, 0, n)
                    }
                    files[entry.name] = out.toByteArray()
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw ParseException("Not a Word document: ${e.message}")
        }
        return files
    }

    private fun parse(xml: ByteArray): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // A file is data, never a way to reach other files or the network.
            try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            isExpandEntityReferences = false
        }
        return try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml)).documentElement
        } catch (e: Exception) {
            throw ParseException("Broken XML in the document: ${e.message}")
        }
    }

    /** Relationship ids of `document.xml` to the files in the package they point at. */
    private fun relationTargets(root: Element): Map<String, String> =
        root.elements().filter { it.attr("TargetMode") != "External" }.mapNotNull { rel ->
            val id = rel.attr("Id") ?: return@mapNotNull null
            val target = rel.attr("Target") ?: return@mapNotNull null
            id to resolvePath("word/", target)
        }.toMap()

    /** [target] taken from the folder [base], as a path in the zip. */
    internal fun resolvePath(base: String, target: String): String {
        val parts = ArrayList<String>()
        val joined = if (target.startsWith("/")) target.drop(1) else base + target
        for (part in joined.split('/')) when (part) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(part)
        }
        return parts.joinToString("/")
    }

    // ── Page ────────────────────────────────────────────────────────────────

    /** A4 with Word's 2.5 cm margins. */
    private val DEFAULT_PAGE = DocxPage(595.3f, 841.9f, 72f, 72f, 72f, 72f)

    private fun page(sectPr: Element): DocxPage {
        val size = sectPr.child("pgSz")
        val margins = sectPr.child("pgMar")
        fun length(el: Element?, name: String, fallback: Float) =
            el?.attr(name)?.toFloatOrNull()?.let { kotlin.math.abs(twips(it)) } ?: fallback
        val width = length(size, "w", DEFAULT_PAGE.width)
        val height = length(size, "h", DEFAULT_PAGE.height)
        return DocxPage(
            width, height,
            length(margins, "top", 72f), length(margins, "right", 72f),
            length(margins, "bottom", 72f), length(margins, "left", 72f)
        )
    }

    // ── Theme and styles ────────────────────────────────────────────────────

    /** The theme's fonts for body text ("minor") and headings ("major"). */
    private class ThemeFonts(val minor: String? = null, val major: String? = null)

    private fun themeFonts(theme: Element): ThemeFonts {
        fun latin(name: String) = theme.descendants(name).firstOrNull()
            ?.child("latin")?.attr("typeface")?.takeIf { it.isNotBlank() }
        return ThemeFonts(latin("minorFont"), latin("majorFont"))
    }

    /** Run properties as one layer of the cascade sets them; null where it doesn't. */
    private data class RunProps(
        val font: String? = null,
        val size: Float? = null,
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val strike: Boolean? = null,
        /** [NONE] for "auto". */
        val color: Int? = null,
        /** [NONE] for no highlight. */
        val highlight: Int? = null,
        val vertAlign: DocxVertAlign? = null,
        val caps: Boolean? = null,
        val hidden: Boolean? = null
    ) {
        /** These properties over [base]'s. */
        fun over(base: RunProps) = RunProps(
            font ?: base.font, size ?: base.size, bold ?: base.bold, italic ?: base.italic,
            underline ?: base.underline, strike ?: base.strike, color ?: base.color,
            highlight ?: base.highlight, vertAlign ?: base.vertAlign, caps ?: base.caps,
            hidden ?: base.hidden
        )

        fun style() = DocxRunStyle(
            font = font ?: FALLBACK_FONT,
            size = size ?: FALLBACK_SIZE,
            bold = bold == true,
            italic = italic == true,
            underline = underline == true,
            strike = strike == true,
            color = color?.takeIf { it != NONE },
            highlight = highlight?.takeIf { it != NONE },
            vertAlign = vertAlign ?: DocxVertAlign.BASELINE
        )
    }

    /** Paragraph properties as one layer of the cascade sets them. */
    private data class ParaProps(
        val align: DocxAlign? = null,
        val before: Float? = null,
        val after: Float? = null,
        val line: DocxLineSpacing? = null,
        val left: Float? = null,
        val right: Float? = null,
        val firstLine: Float? = null,
        val numId: String? = null,
        val level: Int? = null,
        val pageBreakBefore: Boolean? = null,
        val shading: Int? = null,
        val contextual: Boolean? = null
    ) {
        fun over(base: ParaProps) = ParaProps(
            align ?: base.align, before ?: base.before, after ?: base.after, line ?: base.line,
            left ?: base.left, right ?: base.right, firstLine ?: base.firstLine,
            numId ?: base.numId, level ?: base.level, pageBreakBefore ?: base.pageBreakBefore,
            shading ?: base.shading, contextual ?: base.contextual
        )
    }

    private const val NONE = -1

    private class Styles(private val root: Element?, private val theme: ThemeFonts) {
        private val byId: Map<String, Element> = root?.elements()
            ?.filter { it.localName == "style" }
            ?.mapNotNull { el -> el.attr("styleId")?.let { it to el } }
            ?.toMap().orEmpty()

        private fun default(type: String) = root?.elements()?.firstOrNull {
            it.localName == "style" && it.attr("type") == type && onOff(it.attr("default"))
        }?.attr("styleId")

        val defaultParagraph = default("paragraph")
        val defaultTable = default("table")

        val docRun: RunProps = root?.child("docDefaults")?.child("rPrDefault")?.child("rPr")
            ?.let(::runProps) ?: RunProps()
        val docPara: ParaProps = root?.child("docDefaults")?.child("pPrDefault")?.child("pPr")
            ?.let(::paraProps) ?: ParaProps()

        /** A style's properties with those of the styles it is based on beneath them. */
        private fun <T> chain(id: String?, depth: Int = 0, empty: T, layer: (Element) -> T, over: (T, T) -> T): T {
            val style = id?.let { byId[it] } ?: return empty
            if (depth > 20) return empty
            val base = chain(style.child("basedOn")?.attr("val"), depth + 1, empty, layer, over)
            return over(layer(style), base)
        }

        private val runCache = HashMap<String, RunProps>()
        private val paraCache = HashMap<String, ParaProps>()

        fun run(id: String?): RunProps = id?.let { key ->
            runCache.getOrPut(key) {
                chain(key, 0, RunProps(), { s -> s.child("rPr")?.let(::runProps) ?: RunProps() }) { a, b -> a.over(b) }
            }
        } ?: RunProps()

        fun para(id: String?): ParaProps = id?.let { key ->
            paraCache.getOrPut(key) {
                chain(key, 0, ParaProps(), { s -> s.child("pPr")?.let(::paraProps) ?: ParaProps() }) { a, b -> a.over(b) }
            }
        } ?: ParaProps()

        /** Whether a table style, or one it is based on, draws borders. */
        fun tableBorders(id: String?): Boolean? =
            chain(id, 0, null as Boolean?, { s -> s.child("tblPr")?.child("tblBorders")?.let(::hasBorders) }) { a, b -> a ?: b }

        fun runProps(rPr: Element): RunProps {
            var props = RunProps()
            for (el in rPr.elements()) props = when (el.localName) {
                "rFonts" -> props.copy(font = fontOf(el) ?: props.font)
                "sz" -> props.copy(size = el.attr("val")?.toFloatOrNull()?.div(2f))
                "b" -> props.copy(bold = onOff(el.attr("val")))
                "i" -> props.copy(italic = onOff(el.attr("val")))
                "u" -> props.copy(underline = el.attr("val").let { it != null && it != "none" })
                "strike", "dstrike" -> props.copy(strike = onOff(el.attr("val")))
                "color" -> props.copy(color = el.attr("val")?.let(::hexColor) ?: NONE)
                "highlight" -> props.copy(highlight = el.attr("val")?.let { HIGHLIGHTS[it] } ?: NONE)
                "shd" -> if (props.highlight == null) {
                    props.copy(highlight = el.attr("fill")?.let(::hexColor) ?: NONE)
                } else props
                "vertAlign" -> props.copy(
                    vertAlign = when (el.attr("val")) {
                        "superscript" -> DocxVertAlign.SUPER
                        "subscript" -> DocxVertAlign.SUB
                        else -> DocxVertAlign.BASELINE
                    }
                )
                "caps" -> props.copy(caps = onOff(el.attr("val")))
                "vanish" -> props.copy(hidden = onOff(el.attr("val")))
                else -> props
            }
            return props
        }

        /** The Latin font a `w:rFonts` names, its theme's where it names a theme font. */
        private fun fontOf(el: Element): String? {
            el.attr("ascii")?.let { return it }
            el.attr("hAnsi")?.let { return it }
            val themed = el.attr("asciiTheme") ?: el.attr("hAnsiTheme") ?: return null
            return if (themed.startsWith("major")) theme.major else theme.minor
        }

        fun paraProps(pPr: Element): ParaProps {
            var props = ParaProps()
            for (el in pPr.elements()) props = when (el.localName) {
                "jc" -> props.copy(
                    align = when (el.attr("val")) {
                        "center" -> DocxAlign.CENTER
                        "right", "end" -> DocxAlign.RIGHT
                        "both", "distribute", "thaiDistribute" -> DocxAlign.JUSTIFY
                        else -> DocxAlign.LEFT
                    }
                )
                "spacing" -> props.copy(
                    before = if (onOff(el.attr("beforeAutospacing"), false)) AUTO_SPACING
                    else el.attr("before")?.toFloatOrNull()?.let(::twips) ?: props.before,
                    after = if (onOff(el.attr("afterAutospacing"), false)) AUTO_SPACING
                    else el.attr("after")?.toFloatOrNull()?.let(::twips) ?: props.after,
                    line = el.attr("line")?.toFloatOrNull()?.let { line ->
                        when (el.attr("lineRule")) {
                            "exact" -> DocxLineSpacing.Exact(twips(kotlin.math.abs(line)))
                            "atLeast" -> DocxLineSpacing.AtLeast(twips(line))
                            else -> DocxLineSpacing.Multiple(line / 240f)
                        }
                    } ?: props.line
                )
                "ind" -> props.copy(
                    left = (el.attr("left") ?: el.attr("start"))?.toFloatOrNull()?.let(::twips) ?: props.left,
                    right = (el.attr("right") ?: el.attr("end"))?.toFloatOrNull()?.let(::twips) ?: props.right,
                    firstLine = el.attr("hanging")?.toFloatOrNull()?.let { -twips(it) }
                        ?: el.attr("firstLine")?.toFloatOrNull()?.let(::twips) ?: props.firstLine
                )
                "numPr" -> props.copy(
                    numId = el.child("numId")?.attr("val") ?: props.numId,
                    level = el.child("ilvl")?.attr("val")?.toIntOrNull() ?: props.level
                )
                "pageBreakBefore" -> props.copy(pageBreakBefore = onOff(el.attr("val")))
                "shd" -> props.copy(shading = el.attr("fill")?.let(::hexColor) ?: NONE)
                "contextualSpacing" -> props.copy(contextual = onOff(el.attr("val")))
                else -> props
            }
            return props
        }
    }

    /** Word's "auto" spacing, as a browser spaces paragraphs. */
    private const val AUTO_SPACING = 14f

    private fun hasBorders(borders: Element): Boolean =
        borders.elements().any { it.attr("val").let { v -> v != null && v != "nil" && v != "none" } }

    // ── Numbering ───────────────────────────────────────────────────────────

    private class Level(val start: Int, val format: String, val text: String, val pPr: Element?, val rPr: Element?)

    private class Numbering(root: Element?, private val styles: Styles) {
        private val abstracts: Map<String, Map<Int, Level>> = root?.elements()
            ?.filter { it.localName == "abstractNum" }
            ?.mapNotNull { abs ->
                val id = abs.attr("abstractNumId") ?: return@mapNotNull null
                id to abs.elements().filter { it.localName == "lvl" }.associate { lvl ->
                    (lvl.attr("ilvl")?.toIntOrNull() ?: 0) to Level(
                        start = lvl.child("start")?.attr("val")?.toIntOrNull() ?: 1,
                        format = lvl.child("numFmt")?.attr("val") ?: "decimal",
                        text = lvl.child("lvlText")?.attr("val") ?: "",
                        pPr = lvl.child("pPr"),
                        rPr = lvl.child("rPr")
                    )
                }
            }?.toMap().orEmpty()

        /** Each `w:num`: its abstract numbering, and the levels it restarts at a number of its own. */
        private val nums: Map<String, Pair<String, Map<Int, Int>>> = root?.elements()
            ?.filter { it.localName == "num" }
            ?.mapNotNull { num ->
                val id = num.attr("numId") ?: return@mapNotNull null
                val abstract = num.child("abstractNumId")?.attr("val") ?: return@mapNotNull null
                val overrides = num.elements().filter { it.localName == "lvlOverride" }.mapNotNull { o ->
                    val level = o.attr("ilvl")?.toIntOrNull() ?: return@mapNotNull null
                    val start = o.child("startOverride")?.attr("val")?.toIntOrNull() ?: return@mapNotNull null
                    level to start
                }.toMap()
                id to (abstract to overrides)
            }?.toMap().orEmpty()

        /** Counters per abstract numbering, as lists sharing one count on in Word. */
        private val counters = HashMap<String, IntArray>()
        private val restarted = HashSet<String>()

        fun level(numId: String, level: Int): Level? =
            nums[numId]?.let { (abstract, _) -> abstracts[abstract]?.get(level) }

        fun paraProps(numId: String, level: Int): ParaProps =
            level(numId, level)?.pPr?.let(styles::paraProps) ?: ParaProps()

        fun runProps(numId: String, level: Int): RunProps =
            level(numId, level)?.rPr?.let(styles::runProps) ?: RunProps()

        /** The next label of list [numId] at [level], counting it. */
        fun next(numId: String, level: Int): String? {
            val (abstract, overrides) = nums[numId] ?: return null
            val levels = abstracts[abstract] ?: return null
            val lvl = levels[level] ?: return null
            val count = counters.getOrPut(abstract) { IntArray(9) { (levels[it]?.start ?: 1) - 1 } }
            if (restarted.add(numId)) {
                for ((l, start) in overrides) if (l in 0..8) count[l] = start - 1
            }
            if (level in 0..8) {
                count[level]++
                for (deeper in level + 1..8) count[deeper] = (levels[deeper]?.start ?: 1) - 1
            }
            if (lvl.format == "bullet") return bullet(lvl.text)
            if (lvl.format == "none") return lvl.text.replace(Regex("%[1-9]"), "").ifEmpty { null }
            return Regex("%([1-9])").replace(lvl.text) { m ->
                val l = m.groupValues[1].toInt() - 1
                val format = levels[l]?.format ?: "decimal"
                formatNumber(count.getOrElse(l) { 1 }.coerceAtLeast(levels[l]?.start ?: 1), format)
            }.ifEmpty { null }
        }
    }

    /** A bullet in a symbol font's private-use code, as the Unicode character it shows. */
    internal fun bullet(text: String): String {
        val c = text.firstOrNull() ?: return "•"
        return when (c.code) {
            0xF0B7, 0xF06C, 0x00B7 -> "•"
            0xF0A7, 0xF06E -> "▪"
            0xF0FC -> "✓"
            0xF0D8 -> "➢"
            0xF076 -> "❖"
            0xF0A8 -> "□"
            0xF0E0 -> "➔"
            else -> if (c == 'o') "◦" else if (c.code in 0xF000..0xF0FF) "•" else text
        }
    }

    internal fun formatNumber(n: Int, format: String): String = when (format) {
        "lowerLetter" -> letters(n).lowercase()
        "upperLetter" -> letters(n)
        "lowerRoman" -> roman(n).lowercase()
        "upperRoman" -> roman(n)
        "decimalZero" -> if (n < 10) "0$n" else n.toString()
        else -> n.toString()
    }

    /** Word's letters: A…Z, then AA…ZZ, AAA… */
    private fun letters(n: Int): String {
        if (n <= 0) return n.toString()
        val letter = 'A' + (n - 1) % 26
        return letter.toString().repeat((n - 1) / 26 + 1)
    }

    private fun roman(n: Int): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var rest = n
        return buildString {
            for (i in values.indices) while (rest >= values[i]) { append(symbols[i]); rest -= values[i] }
        }
    }

    // ── Body ────────────────────────────────────────────────────────────────

    private class BodyReader(
        private val styles: Styles,
        private val numbering: Numbering,
        private val media: (String) -> ByteArray?
    ) {
        /** A paragraph with what contextual spacing needs to know of it. */
        private class Read(val paragraph: DocxParagraph, val style: String?, val contextual: Boolean)

        fun blocks(parent: Element): List<DocxBlock> {
            val out = ArrayList<Any>()
            collect(parent, out)
            return settleSpacing(out)
        }

        private fun collect(parent: Element, out: MutableList<Any>) {
            for (el in parent.elements()) when (el.localName) {
                "p" -> paragraph(el, out)
                "tbl" -> out.add(table(el))
                "sdt" -> el.child("sdtContent")?.let { collect(it, out) }
                "customXml" -> collect(el, out)
            }
        }

        /**
         * Word's contextual spacing: a paragraph that has it drops its space before and after
         * against a neighbour of the same style, so a list's items sit close together.
         */
        private fun settleSpacing(items: List<Any>): List<DocxBlock> = items.mapIndexed { i, item ->
            if (item !is Read) return@mapIndexed item as DocxBlock
            if (!item.contextual) return@mapIndexed item.paragraph
            val previous = items.getOrNull(i - 1) as? Read
            val next = items.getOrNull(i + 1) as? Read
            item.paragraph.copy(
                spaceBefore = if (previous?.style == item.style) 0f else item.paragraph.spaceBefore,
                spaceAfter = if (next?.style == item.style) 0f else item.paragraph.spaceAfter
            )
        }

        private fun paragraph(p: Element, out: MutableList<Any>) {
            val pPr = p.child("pPr")
            val styleId = pPr?.child("pStyle")?.attr("val") ?: styles.defaultParagraph
            val direct = pPr?.let(styles::paraProps) ?: ParaProps()
            val styled = styles.para(styleId).over(styles.docPara)
            val numId = direct.numId ?: styled.numId
            val level = direct.level ?: styled.level ?: 0
            val numbered = numId != null && numId != "0"
            val props = direct
                .over(if (numbered) numbering.paraProps(numId!!, level) else ParaProps())
                .over(styled)
            val baseRun = styles.run(styleId).over(styles.docRun)
            val markRun = (pPr?.child("rPr")?.let(styles::runProps) ?: RunProps()).over(baseRun)

            val label = if (numbered) numbering.next(numId!!, level)?.let { text ->
                val labelRun = numbering.runProps(numId, level).let { run ->
                    // The bullet was mapped out of its symbol font, so it is drawn in the text's.
                    if (run.font != null && SYMBOL_FONTS.any { run.font.contains(it, ignoreCase = true) }) run.copy(font = null) else run
                }.over(markRun)
                DocxText(text, labelRun.style())
            } else null

            val segments = ArrayList<MutableList<DocxInline>>().apply { add(ArrayList()) }
            inlines(p, baseRun, segments)

            fun make(inlines: List<DocxInline>, first: Boolean, last: Boolean) = DocxParagraph(
                inlines = inlines,
                markStyle = markRun.style(),
                align = props.align ?: DocxAlign.LEFT,
                spaceBefore = if (first) props.before ?: 0f else 0f,
                spaceAfter = if (last) props.after ?: 0f else 0f,
                lineSpacing = props.line ?: DocxLineSpacing.Multiple(1f),
                indentLeft = props.left ?: 0f,
                indentRight = props.right ?: 0f,
                firstLine = if (first) props.firstLine ?: 0f else 0f,
                label = if (first) label else null,
                pageBreakBefore = first && props.pageBreakBefore == true,
                shading = props.shading?.takeIf { it != NONE }
            )

            // A break run splits the paragraph: the text before it on one page, the rest —
            // with the paragraph mark, even when it is empty — on the next, as in Word.
            segments.forEachIndexed { i, inlines ->
                if (i > 0) out.add(DocxPageBreak)
                val first = i == 0
                val last = i == segments.size - 1
                if (first && !last && inlines.isEmpty()) return@forEachIndexed
                out.add(Read(make(inlines, first, last), styleId, props.contextual == true))
            }
        }

        /** The text and pictures under [parent], into the last of [segments]; a page break starts a new one. */
        private fun inlines(parent: Element, paraRun: RunProps, segments: MutableList<MutableList<DocxInline>>) {
            for (el in parent.elements()) when (el.localName) {
                "r" -> run(el, paraRun, segments)
                "hyperlink", "ins", "smartTag", "customXml", "fldSimple", "dir", "bdo", "moveTo" ->
                    inlines(el, paraRun, segments)
                "sdt" -> el.child("sdtContent")?.let { inlines(it, paraRun, segments) }
                "AlternateContent" -> el.child("Choice")?.let { inlines(it, paraRun, segments) }
                "oMath", "oMathPara" -> {
                    // A formula, as the plain text of it: Word's own layout of it is beyond this.
                    val text = el.descendants("t").joinToString("") { it.textContent }
                    if (text.isNotEmpty()) segments.last().add(DocxText(text, paraRun.style().copy(italic = true)))
                }
            }
        }

        private fun run(r: Element, paraRun: RunProps, segments: MutableList<MutableList<DocxInline>>) {
            val rPr = r.child("rPr")
            val charStyle = rPr?.child("rStyle")?.attr("val")
            val props = (rPr?.let(styles::runProps) ?: RunProps()).over(styles.run(charStyle)).over(paraRun)
            if (props.hidden == true) return
            val style = props.style()
            val text = StringBuilder()
            fun flush() {
                if (text.isEmpty()) return
                val s = if (props.caps == true) text.toString().uppercase() else text.toString()
                segments.last().add(DocxText(s, style))
                text.setLength(0)
            }
            fun content(parent: Element) {
                for (el in parent.elements()) when (el.localName) {
                    "t" -> text.append(el.textContent)
                    "tab", "ptab" -> text.append('\t')
                    "br" -> when (el.attr("type")) {
                        "page" -> { flush(); segments.add(ArrayList()) }
                        else -> text.append('\n')
                    }
                    "cr" -> text.append('\n')
                    "noBreakHyphen" -> text.append('‑')
                    "softHyphen" -> text.append('­')
                    "sym" -> el.attr("char")?.toIntOrNull(16)?.let { text.append(bullet(String(Character.toChars(it)))) }
                    "drawing" -> { flush(); drawing(el)?.let { segments.last().add(it) } }
                    "pict", "object" -> { flush(); vmlImage(el)?.let { segments.last().add(it) } }
                    "AlternateContent" -> el.child("Choice")?.let { content(it) }
                }
            }
            content(r)
            flush()
        }

        /** A DrawingML picture: the image its `a:blip` embeds, at the size `wp:extent` gives. */
        private fun drawing(el: Element): DocxImage? {
            val blip = el.descendants("blip").firstOrNull() ?: return null
            val data = blip.attr("embed")?.let(media) ?: return null
            val extent = el.descendants("extent").firstOrNull()
            return DocxImage(
                data,
                extent?.attr("cx")?.toFloatOrNull()?.let(::emu) ?: 0f,
                extent?.attr("cy")?.toFloatOrNull()?.let(::emu) ?: 0f
            )
        }

        /** An older document's VML picture: `v:imagedata` in a shape sized by its CSS. */
        private fun vmlImage(el: Element): DocxImage? {
            val imageData = el.descendants("imagedata").firstOrNull() ?: return null
            val data = imageData.attr("id")?.let(media) ?: return null
            val css = el.descendants("shape").firstOrNull()?.attr("style").orEmpty()
            return DocxImage(data, cssLength(css, "width"), cssLength(css, "height"))
        }

        private fun table(tbl: Element): DocxTable {
            val tblPr = tbl.child("tblPr")
            val columns = tbl.child("tblGrid")?.elements()
                ?.filter { it.localName == "gridCol" }
                ?.map { it.attr("w")?.toFloatOrNull()?.let(::twips) ?: 0f }
                .orEmpty()
            val borders = tblPr?.child("tblBorders")?.let(::hasBorders)
                ?: styles.tableBorders(tblPr?.child("tblStyle")?.attr("val") ?: styles.defaultTable)
                ?: false
            val rows = tbl.elements().filter { it.localName == "tr" }.map { tr ->
                tr.elements().filter { it.localName == "tc" }.map { tc ->
                    val tcPr = tc.child("tcPr")
                    val vMerge = tcPr?.child("vMerge")
                    DocxCell(
                        blocks = blocks(tc).filter { it !is DocxPageBreak },
                        span = tcPr?.child("gridSpan")?.attr("val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                        mergedAbove = vMerge != null && vMerge.attr("val") != "restart",
                        shading = tcPr?.child("shd")?.attr("fill")?.let(::hexColor)
                    )
                }
            }
            return DocxTable(columns, rows, borders)
        }
    }

    /** Fonts whose bullets are mapped to Unicode ([bullet]). */
    private val SYMBOL_FONTS = listOf("Symbol", "Wingdings", "Webdings")

    private val HIGHLIGHTS = mapOf(
        "yellow" to 0xFFFF00, "green" to 0x00FF00, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF,
        "blue" to 0x0000FF, "red" to 0xFF0000, "darkBlue" to 0x000080, "darkCyan" to 0x008080,
        "darkGreen" to 0x008000, "darkMagenta" to 0x800080, "darkRed" to 0x800000,
        "darkYellow" to 0x808000, "darkGray" to 0x808080, "lightGray" to 0xC0C0C0, "black" to 0x000000
    )

    /**
     * The kind of face — "serif", "monospace" or "sans-serif" — Android draws a font of
     * this name in when it has no font by the name itself.
     */
    internal fun genericFamily(font: String): String {
        val name = font.lowercase()
        return when {
            MONO_HINTS.any { it in name } -> "monospace"
            "sans" in name -> "sans-serif"
            SERIF_HINTS.any { it in name } -> "serif"
            else -> "sans-serif"
        }
    }

    /**
     * The font bundled with the app (`assets/fonts`) whose every letter is as wide as
     * [font]'s, so text in it breaks into lines and pages where Word breaks it; null for
     * a font without one. Calibri Light has no match of its own and gets Calibri's.
     */
    internal fun metricCompatible(font: String): String? = METRIC_COMPATIBLE[font.trim().lowercase()]

    private val METRIC_COMPATIBLE = mapOf(
        "arial" to "LiberationSans", "helvetica" to "LiberationSans",
        "liberation sans" to "LiberationSans", "arimo" to "LiberationSans",
        "times new roman" to "LiberationSerif", "times" to "LiberationSerif",
        "liberation serif" to "LiberationSerif", "tinos" to "LiberationSerif",
        "courier new" to "LiberationMono", "courier" to "LiberationMono",
        "liberation mono" to "LiberationMono", "cousine" to "LiberationMono",
        "calibri" to "Carlito", "calibri light" to "Carlito", "carlito" to "Carlito",
        "cambria" to "Caladea", "caladea" to "Caladea"
    )

    private val MONO_HINTS = listOf("mono", "courier", "consolas", "menlo", "code", "typewriter")
    private val SERIF_HINTS = listOf(
        "serif", "times", "cambria", "georgia", "garamond", "book", "palatino", "century",
        "constantia", "baskerville", "minion", "caslon", "bodoni", "didot", "roman"
    )

    // ── Values ──────────────────────────────────────────────────────────────

    /** Twentieths of a point, Word's unit for page and paragraph lengths. */
    internal fun twips(v: Float) = v / 20f

    /** English Metric Units, DrawingML's: 12700 to the point. */
    internal fun emu(v: Float) = v / 12700f

    /** Word's on/off values: present without a value means on. */
    private fun onOff(value: String?, absent: Boolean = true): Boolean = when (value) {
        null -> absent
        "", "1", "true", "on" -> true
        else -> false
    }

    /** "RRGGBB" as 0xRRGGBB; null for "auto" or anything else. */
    internal fun hexColor(value: String): Int? =
        if (value.length == 6) value.toIntOrNull(16) else null

    /** A length out of CSS like "width:120pt;height:3in", in points. */
    internal fun cssLength(css: String, name: String): Float {
        val value = Regex("(?:^|;)\\s*$name\\s*:\\s*([0-9.]+)\\s*([a-z]*)").find(css) ?: return 0f
        val n = value.groupValues[1].toFloatOrNull() ?: return 0f
        return when (value.groupValues[2]) {
            "in" -> n * 72f
            "cm" -> n * 72f / 2.54f
            "mm" -> n * 72f / 25.4f
            "px" -> n * 0.75f
            else -> n
        }
    }

    // ── DOM ─────────────────────────────────────────────────────────────────
    // By local name, so Word's "transitional" and "strict" namespaces read alike.

    private fun Element.elements(): List<Element> {
        val out = ArrayList<Element>()
        var node = firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE) out.add(node as Element)
            node = node.nextSibling
        }
        return out
    }

    private fun Element.child(name: String): Element? {
        var node = firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).localName == name) return node
            node = node.nextSibling
        }
        return null
    }

    private fun Element.descendants(name: String): List<Element> {
        val list = getElementsByTagNameNS("*", name)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun Element.attr(name: String): String? {
        val attrs = attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if ((a.localName ?: a.nodeName) == name) return a.nodeValue
        }
        return null
    }
}

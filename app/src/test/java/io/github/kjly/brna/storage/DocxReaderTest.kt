package io.github.kjly.brna.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Word documents read for their look, as the import sets them as pages. */
class DocxReaderTest {

    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val wp = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private val a = "http://schemas.openxmlformats.org/drawingml/2006/main"

    private fun document(body: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="$w" xmlns:r="$r" xmlns:wp="$wp" xmlns:a="$a"><w:body>$body</w:body></w:document>"""

    private val styles = """<?xml version="1.0" encoding="UTF-8"?>
<w:styles xmlns:w="$w">
  <w:docDefaults>
    <w:rPrDefault><w:rPr><w:rFonts w:asciiTheme="minorHAnsi"/><w:sz w:val="22"/></w:rPr></w:rPrDefault>
    <w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault>
  </w:docDefaults>
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
  <w:style w:type="paragraph" w:styleId="Heading1">
    <w:basedOn w:val="Normal"/>
    <w:pPr><w:spacing w:before="240" w:after="0"/></w:pPr>
    <w:rPr><w:rFonts w:asciiTheme="majorHAnsi"/><w:b/><w:sz w:val="32"/><w:color w:val="2F5496"/></w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="ListParagraph">
    <w:basedOn w:val="Normal"/>
    <w:pPr><w:ind w:left="720"/><w:contextualSpacing/></w:pPr>
  </w:style>
  <w:style w:type="character" w:styleId="Strong"><w:rPr><w:b/></w:rPr></w:style>
  <w:style w:type="table" w:default="1" w:styleId="TableNormal"/>
  <w:style w:type="table" w:styleId="TableGrid">
    <w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4"/><w:insideH w:val="single" w:sz="4"/></w:tblBorders></w:tblPr>
  </w:style>
</w:styles>"""

    private val theme = """<?xml version="1.0" encoding="UTF-8"?>
<a:theme xmlns:a="$a"><a:themeElements><a:fontScheme>
  <a:majorFont><a:latin typeface="Calibri Light"/></a:majorFont>
  <a:minorFont><a:latin typeface="Calibri"/></a:minorFont>
</a:fontScheme></a:themeElements></a:theme>"""

    private val numbering = """<?xml version="1.0" encoding="UTF-8"?>
<w:numbering xmlns:w="$w">
  <w:abstractNum w:abstractNumId="0">
    <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/>
      <w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl>
    <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%1.%2)"/>
      <w:pPr><w:ind w:left="1440" w:hanging="360"/></w:pPr></w:lvl>
  </w:abstractNum>
  <w:abstractNum w:abstractNumId="1">
    <w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="&#xF0B7;"/>
      <w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr>
      <w:rPr><w:rFonts w:ascii="Symbol" w:hAnsi="Symbol"/></w:rPr></w:lvl>
  </w:abstractNum>
  <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
  <w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
</w:numbering>"""

    private fun docx(body: String, vararg extra: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, data: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
            put("[Content_Types].xml", "<Types/>".toByteArray())
            put("word/document.xml", document(body).toByteArray())
            put("word/styles.xml", styles.toByteArray())
            put("word/theme/theme1.xml", theme.toByteArray())
            put("word/numbering.xml", numbering.toByteArray())
            for ((name, data) in extra) put(name, data)
        }
        return out.toByteArray()
    }

    private fun read(body: String, vararg extra: Pair<String, ByteArray>) = DocxReader.read(docx(body, *extra))

    private fun DocxDocument.paragraphs() = blocks.filterIsInstance<DocxParagraph>()

    private fun DocxParagraph.text() = inlines.filterIsInstance<DocxText>().joinToString("") { it.text }

    @Test
    fun `the page and its margins come from the section, in points`() {
        val doc = read(
            """<w:p/><w:sectPr><w:pgSz w:w="12240" w:h="15840"/>
               <w:pgMar w:top="1440" w:right="1080" w:bottom="720" w:left="1800"/></w:sectPr>"""
        )
        assertEquals(DocxPage(612f, 792f, 72f, 54f, 36f, 90f), doc.page)
    }

    @Test
    fun `a document without a section is A4`() {
        val page = read("<w:p/>").page
        assertEquals(595.3f, page.width, 0.1f)
        assertEquals(841.9f, page.height, 0.1f)
    }

    @Test
    fun `runs take the document defaults, the paragraph style and their own properties`() {
        val doc = read(
            """<w:p><w:r><w:t xml:space="preserve">Plain </w:t></w:r>
               <w:r><w:rPr><w:i/><w:u w:val="single"/><w:color w:val="FF0000"/></w:rPr><w:t>red</w:t></w:r>
               <w:r><w:rPr><w:rStyle w:val="Strong"/></w:rPr><w:t>!</w:t></w:r></w:p>
               <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Title</w:t></w:r></w:p>"""
        )
        val (body, heading) = doc.paragraphs()
        val runs = body.inlines.filterIsInstance<DocxText>()
        assertEquals("Plain ", runs[0].text)
        assertEquals(DocxRunStyle(font = "Calibri", size = 11f), runs[0].style)
        assertEquals(
            DocxRunStyle(font = "Calibri", size = 11f, italic = true, underline = true, color = 0xFF0000),
            runs[1].style
        )
        assertTrue(runs[2].style.bold)
        assertEquals(8f, body.spaceAfter, 0.001f)
        assertEquals(DocxLineSpacing.Multiple(259f / 240f), body.lineSpacing)

        val title = heading.inlines.single() as DocxText
        assertEquals(DocxRunStyle(font = "Calibri Light", size = 16f, bold = true, color = 0x2F5496), title.style)
        assertEquals(12f, heading.spaceBefore, 0.001f)
        assertEquals(0f, heading.spaceAfter, 0.001f)
    }

    @Test
    fun `a property switched off in the run beats the style's`() {
        val doc = read(
            """<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
               <w:r><w:rPr><w:b w:val="0"/></w:rPr><w:t>thin</w:t></w:r></w:p>"""
        )
        assertFalse((doc.paragraphs().single().inlines.single() as DocxText).style.bold)
    }

    @Test
    fun `alignment, indents and exact line spacing are read`() {
        val p = read(
            """<w:p><w:pPr><w:jc w:val="both"/><w:ind w:left="360" w:right="180" w:hanging="200"/>
               <w:spacing w:line="300" w:lineRule="exact"/></w:pPr><w:r><w:t>x</w:t></w:r></w:p>"""
        ).paragraphs().single()
        assertEquals(DocxAlign.JUSTIFY, p.align)
        assertEquals(18f, p.indentLeft, 0.001f)
        assertEquals(9f, p.indentRight, 0.001f)
        assertEquals(-10f, p.firstLine, 0.001f)
        assertEquals(DocxLineSpacing.Exact(15f), p.lineSpacing)
    }

    @Test
    fun `tabs, line breaks and hidden text`() {
        val p = read(
            """<w:p><w:r><w:t>a</w:t><w:tab/><w:t>b</w:t><w:br/><w:t>c</w:t></w:r>
               <w:r><w:rPr><w:vanish/></w:rPr><w:t>hidden</w:t></w:r>
               <w:r><w:rPr><w:caps/></w:rPr><w:t>loud</w:t></w:r>
               <w:del><w:r><w:delText>gone</w:delText></w:r></w:del>
               <w:ins><w:r><w:t>new</w:t></w:r></w:ins>
               <w:hyperlink r:id="rId9"><w:r><w:t>link</w:t></w:r></w:hyperlink></w:p>"""
        ).paragraphs().single()
        assertEquals("a\tb\ncLOUDnewlink", p.text())
    }

    @Test
    fun `a page break splits the paragraph, the rest going on the next page`() {
        val blocks = read(
            """<w:p><w:r><w:t>before</w:t><w:br w:type="page"/><w:t>after</w:t></w:r></w:p>"""
        ).blocks
        assertEquals(3, blocks.size)
        assertEquals("before", (blocks[0] as DocxParagraph).text())
        assertEquals(0f, (blocks[0] as DocxParagraph).spaceAfter, 0.001f)
        assertTrue(blocks[1] === DocxPageBreak)
        assertEquals("after", (blocks[2] as DocxParagraph).text())
        assertEquals(8f, (blocks[2] as DocxParagraph).spaceAfter, 0.001f)
    }

    @Test
    fun `a paragraph of nothing but a page break leaves its mark on the next page`() {
        val blocks = read("""<w:p><w:r><w:br w:type="page"/></w:r></w:p><w:p><w:r><w:t>x</w:t></w:r></w:p>""").blocks
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] === DocxPageBreak)
        assertTrue((blocks[1] as DocxParagraph).inlines.isEmpty())
    }

    @Test
    fun `numbered lists count on, with nested levels starting over`() {
        fun item(level: Int, text: String) =
            """<w:p><w:pPr><w:pStyle w:val="ListParagraph"/><w:numPr><w:ilvl w:val="$level"/><w:numId w:val="1"/></w:numPr></w:pPr>
               <w:r><w:t>$text</w:t></w:r></w:p>"""
        val items = read(item(0, "a") + item(1, "b") + item(1, "c") + item(0, "d") + item(1, "e")).paragraphs()
        assertEquals(listOf("1.", "1.a)", "1.b)", "2.", "2.a)"), items.map { it.label?.text })
        // The level's indent: text at 36 pt, the label hanging 18 pt before it.
        assertEquals(36f, items[0].indentLeft, 0.001f)
        assertEquals(-18f, items[0].firstLine, 0.001f)
        assertEquals(72f, items[1].indentLeft, 0.001f)
    }

    @Test
    fun `list items of one style sit close together, by contextual spacing`() {
        fun item(text: String) =
            """<w:p><w:pPr><w:pStyle w:val="ListParagraph"/><w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr></w:pPr>
               <w:r><w:t>$text</w:t></w:r></w:p>"""
        val items = read(item("a") + item("b") + item("c") + "<w:p><w:r><w:t>after</w:t></w:r></w:p>").paragraphs()
        assertEquals(listOf(0f, 0f, 8f, 8f), items.map { it.spaceAfter })
    }

    @Test
    fun `a bullet from the Symbol font is drawn as a Unicode bullet in the text's font`() {
        val p = read(
            """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr></w:pPr><w:r><w:t>x</w:t></w:r></w:p>"""
        ).paragraphs().single()
        assertEquals("•", p.label?.text)
        assertEquals("Calibri", p.label?.style?.font)
    }

    @Test
    fun `numId 0 switches a style's list off`() {
        val p = read(
            """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="0"/></w:numPr></w:pPr><w:r><w:t>x</w:t></w:r></w:p>"""
        ).paragraphs().single()
        assertNull(p.label)
    }

    @Test
    fun `tables keep their grid, spans, merges and the style's borders`() {
        val table = read(
            """<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/></w:tblPr>
               <w:tblGrid><w:gridCol w:w="2000"/><w:gridCol w:w="3000"/><w:gridCol w:w="1000"/></w:tblGrid>
               <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/><w:shd w:fill="D9D9D9"/></w:tcPr><w:p><w:r><w:t>wide</w:t></w:r></w:p></w:tc>
                     <w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>tall</w:t></w:r></w:p></w:tc></w:tr>
               <w:tr><w:tc><w:p/></w:tc><w:tc><w:p/></w:tc><w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc></w:tr>
               </w:tbl><w:p/>"""
        ).blocks.first() as DocxTable
        assertEquals(listOf(100f, 150f, 50f), table.columns)
        assertTrue(table.borders)
        val (first, second) = table.rows
        assertEquals(2, first[0].span)
        assertEquals(0xD9D9D9, first[0].shading)
        assertEquals("wide", (first[0].blocks.single() as DocxParagraph).text())
        assertFalse(first[1].mergedAbove)
        assertTrue(second[2].mergedAbove)
    }

    @Test
    fun `a table without borders in its style has none`() {
        val table = read(
            """<w:tbl><w:tblGrid><w:gridCol w:w="2000"/></w:tblGrid><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>"""
        ).blocks.first() as DocxTable
        assertFalse(table.borders)
    }

    @Test
    fun `a picture is read from the package at the size its extent gives`() {
        val png = byteArrayOf(1, 2, 3)
        val rels = """<?xml version="1.0"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId5" Type="image" Target="media/image1.png"/>
</Relationships>""".toByteArray()
        val p = read(
            """<w:p><w:r><w:drawing><wp:inline><wp:extent cx="1270000" cy="635000"/>
               <a:graphic><a:graphicData><a:blip r:embed="rId5"/></a:graphicData></a:graphic>
               </wp:inline></w:drawing></w:r></w:p>""",
            "word/_rels/document.xml.rels" to rels,
            "word/media/image1.png" to png
        ).paragraphs().single()
        val image = p.inlines.single() as DocxImage
        assertEquals(100f, image.width, 0.001f)
        assertEquals(50f, image.height, 0.001f)
        assertTrue(png.contentEquals(image.data))
    }

    @Test(expected = DocxReader.ParseException::class)
    fun `a file that isn't a zip is turned away`() {
        DocxReader.read("{\\rtf1 not a docx}".toByteArray())
    }

    @Test
    fun `numbers, letters and roman numerals`() {
        assertEquals("iv", DocxReader.formatNumber(4, "lowerRoman"))
        assertEquals("MCMXCIX", DocxReader.formatNumber(1999, "upperRoman"))
        assertEquals("AA", DocxReader.formatNumber(27, "upperLetter"))
        assertEquals("c", DocxReader.formatNumber(3, "lowerLetter"))
        assertEquals("07", DocxReader.formatNumber(7, "decimalZero"))
    }

    @Test
    fun `fonts the tablet lacks map to the right kind of face`() {
        assertEquals("serif", DocxReader.genericFamily("Times New Roman"))
        assertEquals("serif", DocxReader.genericFamily("Cambria"))
        assertEquals("sans-serif", DocxReader.genericFamily("Calibri"))
        assertEquals("sans-serif", DocxReader.genericFamily("Microsoft Sans Serif"))
        assertEquals("monospace", DocxReader.genericFamily("Courier New"))
        assertEquals("monospace", DocxReader.genericFamily("Consolas"))
    }

    @Test
    fun `relationship targets resolve within the package`() {
        assertEquals("word/media/a.png", DocxReader.resolvePath("word/", "media/a.png"))
        assertEquals("media/a.png", DocxReader.resolvePath("word/", "../media/a.png"))
        assertEquals("word/media/a.png", DocxReader.resolvePath("word/", "/word/media/a.png"))
    }

    @Test
    fun `lengths out of a VML shape's style`() {
        assertEquals(144f, DocxReader.cssLength("position:absolute;width:2in;height:72pt", "width"), 0.001f)
        assertEquals(72f, DocxReader.cssLength("position:absolute;width:2in;height:72pt", "height"), 0.001f)
    }
}

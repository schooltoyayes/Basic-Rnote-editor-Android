package io.github.kjly.brna.storage

import io.github.kjly.brna.model.NativeBitmapElement
import io.github.kjly.brna.model.NativeShapeElement
import io.github.kjly.brna.model.NativeTextElement
import io.github.kjly.brna.model.PathShape
import io.github.kjly.brna.model.RectShape
import io.github.kjly.brna.model.RnoteNativeColor
import io.github.kjly.brna.model.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeEditingTest {

    private val black = RnoteNativeColor(0f, 0f, 0f, 1f)

    @Test
    fun `a dragged rectangle fills the dragged box`() {
        val rect = NativeEditing.createShape(ShapeKind.RECTANGLE, 10f, 20f, 110f, 70f, black, 2f)!!
        val shape = rect.shape as RectShape
        assertEquals(50f, shape.halfExtentX, 1e-3f)
        assertEquals(25f, shape.halfExtentY, 1e-3f)
        assertEquals(60f, shape.transform[4], 1e-3f)
        assertEquals(45f, shape.transform[5], 1e-3f)
        assertEquals(10f, rect.minX, 1e-3f)
        assertEquals(70f, rect.maxY, 1e-3f)
    }

    @Test
    fun `a new arrow is written as Rnote's arrow, not a polyline`() {
        val arrow = NativeEditing.createShape(ShapeKind.ARROW, 0f, 0f, 100f, 0f, black, 2f)!!
        assertTrue(arrow.shape is PathShape)
        val raw = arrow.raw!!.asJsonObject.getAsJsonObject("shape")
        assertTrue(raw.has("arrow"))
    }

    @Test
    fun `a tap is not a shape`() {
        assertNull(NativeEditing.createShape(ShapeKind.LINE, 5f, 5f, 5.2f, 5.1f, black, 2f))
    }

    @Test
    fun `moving a shape moves its written JSON too, but not its style`() {
        val line = NativeEditing.createShape(ShapeKind.LINE, 0f, 0f, 10f, 0f, black, 3f)!!
        val moved = NativeEditing.translate(line, 5f, 7f) as NativeShapeElement
        val shape = moved.raw!!.asJsonObject.getAsJsonObject("shape").getAsJsonObject("line")
        assertEquals(5.0, shape.getAsJsonArray("start")[0].asDouble, 1e-6)
        assertEquals(7.0, shape.getAsJsonArray("start")[1].asDouble, 1e-6)
        assertEquals(15.0, shape.getAsJsonArray("end")[0].asDouble, 1e-6)
        val style = moved.raw!!.asJsonObject.getAsJsonObject("style").getAsJsonObject("smooth")
        assertEquals(3.0, style.get("stroke_width").asDouble, 1e-6)
        // The original is untouched: undo keeps pointing at it.
        assertEquals(0.0, line.raw!!.asJsonObject.getAsJsonObject("shape")
            .getAsJsonObject("line").getAsJsonArray("start")[0].asDouble, 1e-6)
    }

    @Test
    fun `moving a text box shifts its transform and keeps its style`() {
        val json = """{"textstroke":{"text":"Hi","transform":{"affine":[1.0,0.0,0.0,0.0,1.0,0.0,10.0,20.0,1.0]},""" +
            """"text_style":{"font_family":"serif","font_size":32.0,"font_weight":700,"font_style":"regular",""" +
            """"color":{"r":0.0,"g":0.0,"b":0.0,"a":1.0},"max_width":null,"alignment":"start","ranged_text_attributes":[]}}}"""
        val text = RnoteNativeParser.parseElementJson(json) as NativeTextElement
        val moved = NativeEditing.translate(text, 100f, 50f) as NativeTextElement
        assertEquals(110f, moved.transform[4], 1e-3f)
        assertEquals(70f, moved.transform[5], 1e-3f)
        val affine = moved.raw!!.asJsonObject.getAsJsonObject("transform").getAsJsonArray("affine")
        assertEquals(110.0, affine[6].asDouble, 1e-6)
        assertEquals(70.0, affine[7].asDouble, 1e-6)
        assertEquals(32.0, moved.raw!!.asJsonObject.getAsJsonObject("text_style").get("font_size").asDouble, 1e-6)
    }

    @Test
    fun `moving an image moves both of its rectangles`() {
        val rect = """{"cuboid":{"half_extents":[50.0,50.0]},"transform":{"affine":[1.0,0.0,0.0,0.0,1.0,0.0,300.0,300.0,1.0]}}"""
        val json = """{"bitmapimage":{"image":{"data":"AAAAAAAAAAAAAAAAAAAAAA==","rectangle":$rect,""" +
            """"pixel_width":2,"pixel_height":2,"memory_format":"R8g8b8a8Premultiplied"},"rectangle":$rect}}"""
        val image = RnoteNativeParser.parseElementJson(json) as NativeBitmapElement
        val moved = NativeEditing.translate(image, -100f, 10f) as NativeBitmapElement
        assertEquals(200f, moved.rect!!.transform[4], 1e-3f)
        val raw = moved.raw!!.asJsonObject
        for (r in listOf(raw.getAsJsonObject("rectangle"), raw.getAsJsonObject("image").getAsJsonObject("rectangle"))) {
            val affine = r.getAsJsonObject("transform").getAsJsonArray("affine")
            assertEquals(200.0, affine[6].asDouble, 1e-6)
            assertEquals(310.0, affine[7].asDouble, 1e-6)
            assertEquals(50.0, r.getAsJsonObject("cuboid").getAsJsonArray("half_extents")[0].asDouble, 1e-6)
        }
    }

    @Test
    fun `the eraser hits a rectangle's outline but not its empty inside`() {
        val rect = NativeEditing.createShape(ShapeKind.RECTANGLE, 0f, 0f, 100f, 100f, black, 2f)!!
        assertTrue(NativeEditing.eraserHits(rect, -3f, 40f, 3f, 46f))
        assertFalse(NativeEditing.eraserHits(rect, 45f, 45f, 55f, 55f))
        assertFalse(NativeEditing.eraserHits(rect, 200f, 200f, 210f, 210f))
    }

    @Test
    fun `the lasso takes only what lies wholly inside it`() {
        val small = NativeEditing.createShape(ShapeKind.RECTANGLE, 10f, 10f, 20f, 20f, black, 2f)!!
        val big = NativeEditing.createShape(ShapeKind.RECTANGLE, 0f, 0f, 500f, 500f, black, 2f)!!
        val lasso = listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)
        assertTrue(NativeEditing.insideLasso(small, lasso))
        assertFalse(NativeEditing.insideLasso(big, lasso))
    }

    @Test
    fun `a shape without colours is read as transparent rather than failing`() {
        val json = """{"shapestroke":{"shape":{"line":{"start":[0.0,0.0],"end":[5.0,5.0]}},""" +
            """"style":{"smooth":{"stroke_width":2.0,"stroke_color":{"r":0.0,"g":0.0,"b":0.0,"a":1.0},""" +
            """"fill_color":null,"pressure_curve":"const","line_style":"solid","line_cap":"straight"}}}}"""
        val shape = RnoteNativeParser.parseElementJson(json) as NativeShapeElement
        assertEquals(0f, shape.fillColor.a, 0f)
    }

    // ── Typewriter ────────────────────────────────────────────────────────────

    @Test
    fun `new text is written the way Rnote's typewriter writes it`() {
        val text = NativeEditing.createText("Hallo \"Welt\"\nZeile 2", 100f, 200f, 32f, black, 600f)!!
        assertEquals("Hallo \"Welt\"\nZeile 2", text.text)
        assertEquals(32f, text.fontSize, 0f)
        assertEquals(100f, text.transform[4], 1e-3f)
        assertEquals(200f, text.transform[5], 1e-3f)
        assertEquals(100f, text.minX, 1e-3f)
        assertEquals(200f, text.minY, 1e-3f)
        val raw = text.raw!!.asJsonObject
        val style = raw.getAsJsonObject("text_style")
        assertEquals("serif", style.get("font_family").asString)
        assertEquals(600.0, style.get("max_width").asDouble, 1e-6)
        assertEquals(9, raw.getAsJsonObject("transform").getAsJsonArray("affine").size())
        assertTrue(style.getAsJsonArray("ranged_text_attributes").isEmpty)
    }

    @Test
    fun `blank text makes no text box`() {
        assertNull(NativeEditing.createText("  \n ", 0f, 0f, 32f, black, null))
    }

    @Test
    fun `short text is only as wide as it is, not as wide as its wrap width`() {
        val text = NativeEditing.createText("Hi", 0f, 0f, 20f, black, 600f)!!
        assertTrue(text.maxX < 100f)
    }

    @Test
    fun `editing text keeps its position, style and formatting`() {
        val json = """{"textstroke":{"text":"ab fett cd","transform":{"affine":[1.0,0.0,0.0,0.0,1.0,0.0,5.0,7.0,1.0]},""" +
            """"text_style":{"font_family":"Cantarell","font_size":18.0,"font_weight":500,"font_style":"regular",""" +
            """"color":{"r":1.0,"g":0.0,"b":0.0,"a":1.0},"max_width":300.0,"alignment":"center",""" +
            """"ranged_text_attributes":[{"range":{"start":3,"end":7},"attribute":{"font_weight":700}},""" +
            """{"range":{"start":8,"end":10},"attribute":{"underline":true}}]}}}"""
        val original = RnoteNativeParser.parseElementJson(json) as NativeTextElement
        val edited = NativeEditing.withText(original, "XYab fett cd")!!
        assertEquals("XYab fett cd", edited.text)
        assertEquals("Cantarell", edited.fontFamily)
        assertEquals("center", edited.alignment)
        assertEquals(5f, edited.transform[4], 1e-3f)
        val ranges = edited.raw!!.asJsonObject.getAsJsonObject("text_style").getAsJsonArray("ranged_text_attributes")
        assertEquals(2, ranges.size())
        val bold = ranges[0].asJsonObject.getAsJsonObject("range")
        assertEquals(5, bold.get("start").asInt)
        assertEquals(9, bold.get("end").asInt)
        // The original is left as it was: undo holds on to it.
        assertEquals("ab fett cd", original.raw!!.asJsonObject.get("text").asString)
    }

    @Test
    fun `typing inside a formatted range extends it, deleting it drops it`() {
        val ranges = com.google.gson.JsonParser.parseString(
            """[{"range":{"start":3,"end":7},"attribute":{"font_weight":700}}]"""
        ).asJsonArray
        val grown = NativeEditing.shiftRanges(ranges, "ab fett cd", "ab feeett cd")
        assertEquals(9, grown[0].asJsonObject.getAsJsonObject("range").get("end").asInt)
        assertEquals(0, NativeEditing.shiftRanges(ranges, "ab fett cd", "ab  cd").size())
    }

    @Test
    fun `ranges count UTF-8 bytes, as Rust does`() {
        // "text" starts at byte 4 of "abc text" and at byte 5 of "äbc text": ä is two bytes.
        val shifted = NativeEditing.shiftRanges(
            com.google.gson.JsonParser.parseString("""[{"range":{"start":4,"end":8},"attribute":{"underline":true}}]""").asJsonArray,
            "abc text", "äbc text"
        )
        assertEquals(5, shifted[0].asJsonObject.getAsJsonObject("range").get("start").asInt)
        assertEquals(9, shifted[0].asJsonObject.getAsJsonObject("range").get("end").asInt)
    }

    @Test
    fun `emptying a text box removes it`() {
        val text = NativeEditing.createText("weg", 0f, 0f, 32f, black, null)!!
        assertNull(NativeEditing.withText(text, "   "))
    }

    @Test
    fun `a tap finds the topmost text box under it`() {
        val below = NativeEditing.createText("unten", 0f, 0f, 32f, black, null)!!
        val above = NativeEditing.createText("oben", 10f, 5f, 32f, black, null)!!
        assertTrue(NativeEditing.textAt(listOf(below, above), 20f, 20f) === above)
        assertNull(NativeEditing.textAt(listOf(below, above), 500f, 500f))
    }

    @Test
    fun `text typed on a page wraps at its right edge`() {
        assertEquals(600f, NativeEditing.typewriterWrapWidth(50f, 793.7f), 0f)
        assertEquals(793.7f - 400f - 20f, NativeEditing.typewriterWrapWidth(400f, 793.7f), 1e-3f)
        // Too close to the edge for a useful column: the default width instead.
        assertEquals(600f, NativeEditing.typewriterWrapWidth(700f, 793.7f), 0f)
        // Pages repeat: the same holds on the page to the right.
        assertEquals(793.7f - 400f - 20f, NativeEditing.typewriterWrapWidth(793.7f + 400f, 793.7f), 1e-2f)
    }

    @Test
    fun `new text survives a save and a reload`() {
        val text = NativeEditing.createText("Mathe\nAufgabe 1", 30f, 40f, 24f, black, 500f)!!
        val reread = RnoteNativeParser.parseElementJson(
            com.google.gson.JsonObject().apply { add("textstroke", text.raw) }.toString()
        ) as NativeTextElement
        assertEquals(text.text, reread.text)
        assertEquals(24f, reread.fontSize, 0f)
        assertEquals(500f, reread.maxWidth!!, 0f)
    }
}

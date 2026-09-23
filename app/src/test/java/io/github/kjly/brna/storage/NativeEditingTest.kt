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
}

package io.github.kjly.brna.ui.canvas

import androidx.compose.ui.graphics.Color
import io.github.kjly.brna.model.NativeVectorImageElement
import io.github.kjly.brna.model.Stroke
import io.github.kjly.brna.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalSpaceTest {

    private fun stroke(id: String, fromY: Float, toY: Float, width: Float = 4f) = Stroke(
        id = id,
        points = listOf(StrokePoint(10f, fromY), StrokePoint(20f, toY)),
        color = Color.Black,
        strokeWidth = width
    )

    /** A picture covering top..bottom. */
    private fun image(top: Float, bottom: Float) = NativeVectorImageElement(
        "<svg/>", 100f, bottom - top, 50f, (bottom - top) / 2f,
        floatArrayOf(1f, 0f, 0f, 1f, 50f, (top + bottom) / 2f), "image",
        0f, top, 100f, bottom
    )

    @Test
    fun `everything reaching the line or below moves, a stroke crossing it included`() {
        val below = VerticalSpace.strokesBelow(
            listOf(stroke("above", 10f, 40f), stroke("crossing", 80f, 120f), stroke("below", 150f, 160f)),
            100f
        )
        assertEquals(setOf("crossing", "below"), below)
    }

    @Test
    fun `a stroke's width counts, as its bounds do in Rnote`() {
        // Ends at 98, but 4 wide: its outline reaches 100.
        assertEquals(setOf("s"), VerticalSpace.strokesBelow(listOf(stroke("s", 50f, 98f)), 100f))
        assertTrue(VerticalSpace.strokesBelow(listOf(stroke("s", 50f, 97f)), 100f).isEmpty())
    }

    @Test
    fun `desktop elements move by the same rule and are told apart by identity`() {
        val above = image(0f, 90f)
        val crossing = image(50f, 150f)
        val twin = image(50f, 150f)
        val below = VerticalSpace.nativesBelow(listOf(above, crossing), 100f)
        assertTrue(crossing in below)
        assertFalse(above in below)
        // Another element in the same place is not the one that moves.
        assertFalse(twin in below)
    }

    @Test
    fun `close to where the drag started nothing moves`() {
        assertEquals(0f, VerticalSpace.offset(100f, 105f), 0f)
        assertEquals(0f, VerticalSpace.offset(100f, 91f), 0f)
        assertEquals(40f, VerticalSpace.offset(100f, 140f), 0f)
        assertEquals(-30f, VerticalSpace.offset(100f, 70f), 0f)
    }
}

package io.github.kjly.brna.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolConfigPressureTest {

    @Test
    fun `the solid brush starts linear, as Rnote's SolidOptions do`() {
        assertEquals(PressureCurve.LINEAR, ToolConfig().strokePressureCurve)
    }

    @Test
    fun `the solid brush draws with the curve picked`() {
        for (curve in PressureCurve.entries) {
            assertEquals(curve, ToolConfig(pressureCurve = curve).strokePressureCurve)
        }
    }

    @Test
    fun `the marker keeps a constant width whatever curve was picked`() {
        val marker = ToolConfig(brushStyle = BrushStyle.MARKER, pressureCurve = PressureCurve.POW3)
        assertEquals(PressureCurve.CONST, marker.strokePressureCurve)
    }
}

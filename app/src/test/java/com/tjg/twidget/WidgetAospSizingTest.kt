package com.tjg.twidget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetAospSizingTest {
    @Test
    fun `pixel launcher 2x2 uses centered count artwork`() {
        assertEquals(
            TwidgetWidget.LAYOUT_MODE_COMPACT_2X1,
            TwidgetWidget.layoutModeForAosp(width = 179, height = 99),
        )
    }

    @Test
    fun `pixel launcher one-row sizes stay compact`() {
        assertEquals(
            TwidgetWidget.LAYOUT_MODE_COMPACT_2X1,
            TwidgetWidget.layoutModeForAosp(width = 179, height = 48),
        )
        assertEquals(
            TwidgetWidget.LAYOUT_MODE_COMPACT_STRIP,
            TwidgetWidget.layoutModeForAosp(width = 360, height = 48),
        )
        assertEquals(
            TwidgetWidget.LAYOUT_MODE_COMPACT_STRIP,
            TwidgetWidget.layoutModeForAosp(width = 360, height = 99),
        )
    }

    @Test
    fun `larger pixel launcher allocation uses large artwork`() {
        assertEquals(
            TwidgetWidget.LAYOUT_MODE_LARGE,
            TwidgetWidget.layoutModeForAosp(width = 373, height = 210),
        )
    }
}

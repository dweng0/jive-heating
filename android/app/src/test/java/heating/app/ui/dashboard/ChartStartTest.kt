package heating.app.ui.dashboard

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartStartTest {
    private fun series(vararg xs: Double) = Series("r", "Room", Color.Black, xs.map { it to 20.0 })

    @Test
    fun `a few hours of history starts the chart at the first reading's hour`() {
        // 24 h = 1440 minutes; readings began at minute 1290 (21.5 h in).
        assertEquals(1260.0, chartStart(listOf(series(1290.0, 1400.0)), 1440.0, "24h"), 0.0)
    }

    @Test
    fun `at least two hours stay on screen`() {
        assertEquals(1320.0, chartStart(listOf(series(1435.0)), 1440.0, "24h"), 0.0)
    }

    @Test
    fun `a full range starts at the beginning`() {
        assertEquals(0.0, chartStart(listOf(series(2.0, 1400.0)), 1440.0, "24h"), 0.0)
        assertEquals(0.0, chartStart(emptyList(), 1440.0, "24h"), 0.0)
    }

    @Test
    fun `week and month ranges round down to the day`() {
        assertEquals(4320.0, chartStart(listOf(series(5000.0)), 10080.0, "7d"), 0.0)
    }
}

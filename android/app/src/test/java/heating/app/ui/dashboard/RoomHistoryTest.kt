package heating.app.ui.dashboard

import androidx.compose.ui.graphics.Color
import heating.app.data.History
import heating.app.data.HistoryPoint
import heating.app.data.RoomHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomHistoryTest {
    private val history = History(
        from = "2026-10-18T00:00:00Z",
        to = "2026-10-19T00:00:00Z",
        rooms = listOf(
            RoomHistory("lounge", "Lounge", listOf(
                HistoryPoint("2026-10-18T06:00:00Z", current = 19.5, target = 21.0),
                HistoryPoint("2026-10-18T06:05:00Z", current = null, target = 21.0),
                HistoryPoint("2026-10-18T06:10:00Z", current = 19.8, target = 18.0),
            )),
            RoomHistory("empty", "Spare", emptyList()),
        ),
        house = emptyList(),
        boiler = emptyList(),
    )

    // BDD: A room's page shows its temperature and target over time
    @Test
    fun `a room's history becomes a temperature line and a target line`() {
        val (temperature, target) = roomSeries(history, "lounge", Color.Blue, Color.Gray)!!
        assertEquals("Temperature", temperature.name)
        assertEquals(listOf(360.0 to 19.5, 370.0 to 19.8), temperature.points)
        assertEquals("Target", target.name)
        assertEquals(listOf(360.0 to 21.0, 365.0 to 21.0, 370.0 to 18.0), target.points)
    }

    @Test
    fun `a room with no history yet has no chart`() {
        assertNull(roomSeries(history, "empty", Color.Blue, Color.Gray))
        assertNull(roomSeries(history, "missing", Color.Blue, Color.Gray))
    }
}

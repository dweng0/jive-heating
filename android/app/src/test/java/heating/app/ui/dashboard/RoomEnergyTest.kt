package heating.app.ui.dashboard

import heating.app.data.EnergyBar
import heating.app.data.RoomEnergyHistory
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class RoomEnergyTest {
    private val london = ZoneId.of("Europe/London")

    // BDD: A room's electricity is charted over time
    @Test
    fun `a room's use becomes a bar per hour with gaps and faded incomplete bars`() {
        val history = RoomEnergyHistory(
            room = "kitchen",
            from = "2026-10-12T12:30:00+01:00",
            to = "2026-10-12T16:30:00+01:00",
            points = listOf(
                EnergyBar("2026-10-12T13:00:00+01:00", 0.1, incomplete = false),
                EnergyBar("2026-10-12T14:00:00+01:00", 0.087, incomplete = true),
                EnergyBar("2026-10-12T16:00:00+01:00", null, incomplete = true),
            ),
        )
        val bars = energyBars(history, "24h", london)
        // Every hour in the range, from the one it starts in.
        assertEquals(listOf("12:00", "13:00", "14:00", "15:00", "16:00"), bars.map { it.label })
        assertEquals(listOf(null, 0.1, 0.087, null, null), bars.map { it.kwh })
        assertEquals(listOf(false, false, true, false, true), bars.map { it.incomplete })
        assertEquals("Some readings missing", SOME_MISSING)
        assertEquals("0.09 kWh", barKwh(0.087))
        assertEquals("1.6 kWh", barKwh(1.587))
    }

    @Test
    fun `a week is a bar per day from midnight`() {
        val history = RoomEnergyHistory(
            room = "kitchen",
            from = "2026-10-06T16:00:00+01:00",
            to = "2026-10-12T16:00:00+01:00",
            points = listOf(EnergyBar("2026-10-12T00:00:00+01:00", 1.587, incomplete = true)),
        )
        val bars = energyBars(history, "7d", london)
        assertEquals(7, bars.size)
        assertEquals("Mon", bars.last().label)
        assertEquals(1.587, bars.last().kwh!!, 1e-9)
    }
}

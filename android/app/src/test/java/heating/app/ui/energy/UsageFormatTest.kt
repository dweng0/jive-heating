package heating.app.ui.energy

import heating.app.data.EnergyUsage
import heating.app.data.PlugUsage
import heating.app.data.RoomPlug
import heating.app.data.RoomPlugs
import heating.app.data.UsagePoint
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UsageFormatTest {
    private fun p(at: String, watts: Double? = 100.0, kwh: Double? = 0.008, cost: Double? = 0.21, incomplete: Boolean = false) =
        UsagePoint("2026-10-12T${at}:00Z", watts, kwh, cost, incomplete)

    private fun t(at: String) = Instant.parse("2026-10-12T${at}:00Z")

    private val five = Duration.ofMinutes(5)

    private fun plug(meter: String, power: Double?, kwh: Double, cost: Double, offlineSince: String? = null) =
        RoomPlug(meter, power, kwh, cost, offlineSince = offlineSince, incomplete = offlineSince != null)

    private fun room(kwh: Double, cost: Double, vararg plugs: RoomPlug) = RoomPlugs("kitchen", "GBP", kwh, cost, 0, false, plugs.toList())

    // BDD: A room card opens the room's energy dashboard
    @Test
    fun `room cards are told apart by their key, to open the right dashboard`() {
        val live = heating.app.data.LivePower(
            watts = 500.0,
            rooms = listOf(heating.app.data.RoomPower("utility", "Utility", 10.0)),
            devices = listOf(heating.app.data.DevicePower("Fridge Plug", 20.0)),
            elseWatts = 470.0,
        )
        assertEquals(listOf("room-utility", "device-Fridge Plug", "rest"), liveCards(live, null).map { it.key })
    }

    // BDD: The room dashboard leads with what it is using and costing
    @Test
    fun `now shows the live power, or no reading, never an old figure`() {
        assertEquals("42 W", nowText(true, 42.0))
        assertEquals("No reading", nowText(true, null))
        assertEquals("–", nowText(false, null))
    }

    // BDD: The room dashboard charts each plug in a carousel
    @Test
    fun `the carousel has the room then each plug, and one plug alone`() {
        assertEquals(listOf(WHOLE to "Kitchen", "Fridge" to "Fridge", "Kettle" to "Kettle"), usageSlides("Kitchen", listOf("Fridge", "Kettle")))
        assertEquals(listOf("Fridge" to "Fridge"), usageSlides("Kitchen", listOf("Fridge")))
        assertEquals(listOf(WHOLE to "Kitchen"), usageSlides("Kitchen", emptyList()))
    }

    // BDD: A chart scrolls back through time
    @Test
    fun `scrolling stops at the earliest reading and at now`() {
        val length = Duration.ofHours(24)
        val now = Instant.parse("2026-10-12T12:00:00Z")
        val earliest = now.minus(Duration.ofDays(3))
        assertEquals(now, clampEnd(now.plusSeconds(60), now, earliest, length))
        assertEquals(earliest.plus(length), clampEnd(earliest, now, earliest, length))
        assertEquals(now.minusSeconds(3600), clampEnd(now.minusSeconds(3600), now, earliest, length))
        // Less than a window recorded: nowhere to go.
        assertEquals(now, clampEnd(now.minusSeconds(3600), now, now.minusSeconds(60), length))
        assertEquals(now, clampEnd(now.minusSeconds(3600), now, null, length))
    }

    // BDD: A chart scrolls back through time
    @Test
    fun `earlier pages join what is read, and an hour cut in two waits for the next page`() {
        val hour = Duration.ofHours(1)
        val page = EnergyUsage(
            from = "2026-10-12T10:30:00Z",
            to = "2026-10-12T13:00:00Z",
            earliest = "2026-10-11T00:00:00Z",
            room = listOf(p("10:00", kwh = 0.05), p("11:00"), p("12:00")),
            plugs = listOf(PlugUsage("Fridge", listOf(p("10:00"), p("11:00")))),
        )
        val first = withPage(null, page, hour)
        assertEquals(t("11:00"), first.loadedFrom)
        assertEquals(2, first.series.getValue(WHOLE).size)
        assertEquals(1, first.series.getValue("Fridge").size)

        val earlier = EnergyUsage(from = "2026-10-12T08:00:00Z", to = "2026-10-12T11:00:00Z", earliest = "2026-10-11T00:00:00Z", room = listOf(p("08:00"), p("09:00"), p("10:00", kwh = 0.1)))
        val both = withPage(first, earlier, hour)
        assertEquals(t("08:00"), both.loadedFrom)
        assertEquals(listOf(t("08:00"), t("09:00"), t("10:00"), t("11:00"), t("12:00")), both.series.getValue(WHOLE).map { Instant.parse(it.at) })
        assertEquals(0.1, both.series.getValue(WHOLE)[2].kwh)
    }

    // BDD: A chart says what it shows at any point
    @Test
    fun `a point says its figures, and a gap breaks the line`() {
        val points = listOf(p("10:00"), p("10:05"), p("10:10", watts = null, kwh = null, cost = null, incomplete = true), p("10:15"), p("10:20"), p("11:00"))
        val rows = usageRows(points, five, t("09:00"), t("12:00"))
        assertEquals(listOf(2, 2, 1), lineRuns(rows, UsageMetric.WATTS).map { it.size })

        assertEquals(listOf("100 W · 0.008 kWh · 0.21p"), pointLines(rowAt(rows, t("10:01"), five), "GBP"))
        assertEquals(listOf("No reading"), pointLines(rowAt(rows, t("10:10"), five), "GBP"))
        // Nothing within a step of it.
        assertNull(rowAt(rows, t("10:40"), five))
        assertEquals(listOf("No reading"), pointLines(null, "GBP"))
        val part = UsageRow(t("10:00"), p("10:00", watts = 20.0, incomplete = true))
        assertEquals(listOf("20 W · 0.008 kWh · 0.21p", "Some readings missing"), pointLines(part, "GBP"))
    }

    // BDD: A chart with nothing to draw says so
    @Test
    fun `nothing to draw has no runs, and samples from before power draw under use alone`() {
        assertEquals(emptyList(), usageRows(emptyList(), five, t("09:00"), t("12:00")))
        assertEquals(emptyList(), usageRows(listOf(p("08:00")), five, t("09:00"), t("12:00")))
        val old = usageRows(listOf(p("10:00", watts = null), p("10:05", watts = null)), five, t("09:00"), t("12:00"))
        assertEquals(emptyList(), lineRuns(old, UsageMetric.WATTS))
        assertEquals(1, lineRuns(old, UsageMetric.KWH).size)
    }

    // BDD: A table compares the plugs in the room
    @Test
    fun `the table starts by cost, and a heading sorts then reverses`() {
        val fridge = plug("Fridge", 40.0, 1.2, 30.0)
        val kettle = plug("Kettle", 2.0, 0.4, 10.0)
        val fryer = plug("Fryer", 300.0, 0.5, 15.0)
        val plugs = listOf(fridge, kettle, fryer)
        assertEquals(listOf("Fridge", "Fryer", "Kettle"), sortPlugs(plugs, PlugSort()).map { it.meter })
        val byPower = nextSort(PlugSort(), PlugColumn.POWER)
        assertEquals(true, byPower.descending)
        assertEquals(listOf("Fryer", "Fridge", "Kettle"), sortPlugs(plugs, byPower).map { it.meter })
        assertEquals(listOf("Kettle", "Fridge", "Fryer"), sortPlugs(plugs, nextSort(byPower, PlugColumn.POWER)).map { it.meter })
        assertEquals(false, nextSort(PlugSort(), PlugColumn.METER).descending)
        assertEquals("75%", plugShare(1.2, room(1.6, 40.0, fridge)))
        assertEquals("–", plugShare(0.0, room(0.0, 0.0)))
    }

    // BDD: The table says when a plug's figures cannot be trusted
    @Test
    fun `an offline plug sorts below those with power, whichever way`() {
        val tv = plug("Aerial TV", null, 3.0, 75.0, offlineSince = "2026-10-12T14:20:00+01:00")
        val lamp = plug("Lamp", 60.0, 0.1, 2.5)
        val fridge = plug("Fridge", 40.0, 0.4, 10.0)
        assertEquals("Offline", plugState(tv))
        assertEquals("Online", plugState(lamp))
        listOf(true, false).forEach { d ->
            assertEquals("Aerial TV", sortPlugs(listOf(tv, lamp, fridge), PlugSort(PlugColumn.POWER, d)).last().meter)
        }
        assertEquals("Offline since 14:20. Cost will update when it's back.", plugWarning(tv))
    }

    // BDD: A plug moved out of the room still adds up
    @Test
    fun `what a moved plug used is what the plugs here do not add up to`() {
        val left = assertNotNull(movedOut(room(1.7, 42.5, plug("Fridge", 40.0, 1.2, 30.0))))
        assertEquals(0.5, left.kwh, 1e-9)
        assertEquals(12.5, left.cost, 1e-9)
        assertNull(movedOut(room(1.2, 30.0, plug("Fridge", 40.0, 1.2, 30.0))))
    }

    // BDD: A room with no plugs left has nothing to chart
    @Test
    fun `a room with no plugs left has its own chart, and all its use is moved out`() {
        assertEquals(listOf(WHOLE to "Utility"), usageSlides("Utility", emptyList()))
        assertEquals(0.5, movedOut(room(0.5, 12.5))!!.kwh, 1e-9)
    }
}

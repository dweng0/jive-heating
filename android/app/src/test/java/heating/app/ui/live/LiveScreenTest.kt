package heating.app.ui.live

import heating.app.data.LivePower
import heating.app.data.PowerFlow
import heating.app.ui.bottomTabs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LiveScreenTest {

    // BDD: The Live tab shows the house with its flows
    // BDD: The Live picture shows the weather
    @Test
    fun `the Live tab shows the same house view as the web app, fed by the app's own connection`() {
        // A Live tab for admins and energy access, beside Energy; none for other members.
        assertTrue(bottomTabs(admin = false, energy = true).any { it.route == "live" })
        assertFalse(bottomTabs(admin = false, energy = false).any { it.route == "live" })

        // Each /ws/power message is handed to the page whole, flows and battery included.
        val script = pushScript(LivePower(watts = 900.0, flows = listOf(PowerFlow("solar", "home", 900.0)), batteryPct = 64.0))
        assertTrue(script.startsWith("window.liveFeed && window.liveFeed.push("), script)
        assertTrue(script.contains("\"flows\":[{\"from\":\"solar\",\"to\":\"home\",\"watts\":900.0}]"), script)
        assertTrue(script.contains("\"batteryPct\":64.0"), script)
        // Stopped (no flows) must reach the page as an empty list, not be left out.
        assertTrue(pushScript(LivePower(watts = 950.0)).contains("\"flows\":[]"))
        // The weather reaches the page untouched.
        assertTrue(pushScript(LivePower(watts = 500.0, weather = heating.app.data.LiveWeather("snow", -1.0, "2026-11-15T09:00:00Z"))).contains("\"weather\":{\"look\":\"snow\""))
        assertEquals("window.liveFeed && window.liveFeed.connected(false)", connectedScript(false))

        // The view ships inside the app (built by `npm run build:live-embed`), so it works on any network.
        val html = File("src/main/assets/live/live-embed.html")
        assertTrue(html.isFile, "run npm run build:live-embed in web/")
        assertTrue(LIVE_URL.endsWith("/assets/live/live-embed.html"))
        val js = Regex("src=\"\\./(assets/[^\"]+\\.js)\"").find(html.readText())!!.groupValues[1]
        assertTrue(File("src/main/assets/live/$js").isFile, js)
    }

    // BDD: The Android Live tab keeps its connection when the app comes back
    @Test
    fun `a stream that goes quiet or drops is reconnected`() = runTest {
        var opened = 0
        val got = mutableListOf<Double?>()
        var lost = 0
        // First socket: one reading, then silence for ever (a socket the phone's sleep left dead).
        // Second: drops 5 s after one reading. Third: one reading, then quiet again.
        val stream = {
            val n = ++opened
            flow {
                emit(LivePower(watts = n * 100.0))
                if (n == 2) { kotlinx.coroutines.delay(5000); error("dropped") }
                awaitCancellation()
            }
        }
        val job = launch { followLive(stream, onPower = { got += it.watts }, onLost = { lost++ }) }
        runCurrent()
        assertEquals(listOf<Double?>(100.0), got)
        // Quiet for 30 s: given up and reconnected after a second.
        advanceTimeBy(LIVE_SILENCE.inWholeMilliseconds + 1)
        assertEquals(1, lost)
        advanceTimeBy(1001)
        assertEquals(listOf<Double?>(100.0, 200.0), got)
        // A dropped socket: reconnected after 1 s again, since the last one gave a reading.
        advanceTimeBy(5000)
        assertEquals(2, lost)
        advanceTimeBy(1001)
        assertEquals(listOf<Double?>(100.0, 200.0, 300.0), got)
        assertEquals(3, opened)
        job.cancel()

        // A reading is handed over only once the page is listening, and says whether it was.
        val hand = handOverScript(LivePower(watts = 900.0))
        assertTrue(hand.contains("if (!window.liveFeed) return false"), hand)
        assertTrue(hand.contains("window.liveFeed.push("), hand)
    }
}

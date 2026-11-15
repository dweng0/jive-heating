package heating.app.ui.live

import heating.app.data.LivePower
import heating.app.data.PowerFlow
import heating.app.ui.bottomTabs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveScreenTest {

    // BDD: The Live tab shows the house with its flows
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
        assertEquals("window.liveFeed && window.liveFeed.connected(false)", connectedScript(false))

        // The view ships inside the app (built by `npm run build:live-embed`), so it works on any network.
        val html = File("src/main/assets/live/live-embed.html")
        assertTrue(html.isFile, "run npm run build:live-embed in web/")
        assertTrue(LIVE_URL.endsWith("/assets/live/live-embed.html"))
        val js = Regex("src=\"\\./(assets/[^\"]+\\.js)\"").find(html.readText())!!.groupValues[1]
        assertTrue(File("src/main/assets/live/$js").isFile, js)
    }
}

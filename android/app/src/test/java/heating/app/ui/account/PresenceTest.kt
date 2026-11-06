package heating.app.ui.account

import heating.app.data.AccountUser
import heating.app.data.ActivityEvent
import heating.app.ui.activityText
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class PresenceTest {
    private val now = Instant.parse("2026-11-06T17:00:00Z")

    // BDD: The People page shows who is signed in
    @Test
    fun `each person says whether they are active, when last seen, or that they are not signed in`() {
        assertEquals("Active now", presence(AccountUser("1", "jane", true, activeNow = true, sessions = 1, lastSeen = "2026-11-06T16:59:00Z"), now))
        assertEquals("Last seen 3 hours ago", presence(AccountUser("2", "sam", false, activeNow = false, sessions = 1, lastSeen = "2026-11-06T14:00:00Z"), now))
        assertEquals("Not signed in · last seen 3 days ago", presence(AccountUser("3", "kim", false, activeNow = false, sessions = 0, lastSeen = "2026-11-03T17:00:00Z"), now))
        assertEquals("Not signed in", presence(AccountUser("4", "new", false), now))
    }

    // BDD: Signing in and out is in the activity feed
    @Test
    fun `signing in and out is worded in the feed`() {
        fun event(value: String, reason: String?) = ActivityEvent(1, "2026-11-06T17:00:00Z", "session", "Sam", value = JsonPrimitive(value), reason = reason)
        assertEquals("Sam signed in on Chrome on Android", activityText(event("in", "Chrome on Android"), brief = false))
        assertEquals("Sam signed out", activityText(event("out", null), brief = false))
        assertEquals("Jane signed Sam out", activityText(event("out", "Jane"), brief = false))
    }
}

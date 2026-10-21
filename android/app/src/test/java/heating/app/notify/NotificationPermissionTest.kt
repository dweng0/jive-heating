package heating.app.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionTest {
    // BDD: The Android app asks to notify and has a switch for each notification
    @Test
    fun `the app asks once, only when it may not notify yet`() {
        assertEquals(true, shouldAskToNotify(granted = false, askedBefore = false))
        assertEquals(false, shouldAskToNotify(granted = false, askedBefore = true))
        assertEquals(false, shouldAskToNotify(granted = true, askedBefore = false))
    }
}

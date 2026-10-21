package heating.app.ui

import heating.app.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class AppVersionTest {
    // BDD: The account screen shows the app version
    @Test
    fun `the account screen shows the app version`() {
        assertEquals("Version ${BuildConfig.VERSION_NAME}", appVersionLabel())
        assertEquals("Version 0.7.0", appVersionLabel("0.7.0"))
    }
}

package heating.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BottomTabsTest {
    // BDD: Energy has its own tab
    @Test
    fun energyHasItsOwnTabBesideLive() {
        assertEquals(listOf("Rooms", "Live", "Energy", "Holiday", "Account"), bottomTabs(admin = true).map { it.label })
        assertEquals(listOf("rooms", "live", "energy", null, "account"), bottomTabs(admin = true).map { it.route })
        // Members have no Energy tab (nor holiday). The Charts tab was dropped; its history is still recorded.
        assertEquals(listOf("Rooms", "Account"), bottomTabs(admin = false).map { it.label })
        // The bar shows on each tab's own screen.
        assertEquals(setOf("rooms", "live", "energy"), TAB_SCREENS)
    }

    // BDD: The Energy tab shows for people with energy access
    @Test
    fun aMemberWithEnergyAccessHasTheEnergyTabOnly() {
        assertEquals(listOf("Rooms", "Live", "Energy", "Account"), bottomTabs(admin = false, energy = true).map { it.label })
    }

    @Test
    fun `the Echo Show keeps its tabs in a tray, phones and tablets do not`() {
        kotlin.test.assertTrue(tabsInTray(961, 601)) // Echo Show 8, landscape
        kotlin.test.assertFalse(tabsInTray(411, 915)) // phone, portrait
        kotlin.test.assertFalse(tabsInTray(915, 411)) // phone, landscape
        kotlin.test.assertFalse(tabsInTray(1280, 800)) // tablet
    }
}

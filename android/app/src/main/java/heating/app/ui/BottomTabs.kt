package heating.app.ui

/** A bottom bar item: [route] is the screen it opens; null for Holiday, which opens a sheet instead. */
data class BottomTab(val label: String, val route: String?)

/** Screens that show the bottom bar: the tabs' own, not the screens opened from them. */
val TAB_SCREENS = setOf("rooms", "dashboard", "energy")

/** The bottom bar. The charts and holiday are the whole house's: admins only. Energy: admins and members with energy access. */
fun bottomTabs(admin: Boolean, energy: Boolean = admin): List<BottomTab> = buildList {
    add(BottomTab("Rooms", "rooms"))
    if (admin) add(BottomTab("Charts", "dashboard"))
    if (admin || energy) add(BottomTab("Energy", "energy"))
    if (admin) add(BottomTab("Holiday", null))
    add(BottomTab("Account", "account"))
}

/** A wide, short screen (an Echo Show 8 is about 960×600 dp) keeps the tabs in a tray behind an up arrow, to leave room for the page. */
fun tabsInTray(widthDp: Int, heightDp: Int): Boolean = widthDp >= 900 && heightDp in 500 until 700

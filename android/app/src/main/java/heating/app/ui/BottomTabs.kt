package heating.app.ui

/** A bottom bar item: [route] is the screen it opens; null for Holiday, which opens a sheet instead. */
data class BottomTab(val label: String, val route: String?)

/** Screens that show the bottom bar: the tabs' own, not the screens opened from them. */
val TAB_SCREENS = setOf("rooms", "dashboard", "energy")

/** The bottom bar. The charts, energy and holiday are the whole house's: admins only. */
fun bottomTabs(admin: Boolean): List<BottomTab> = buildList {
    add(BottomTab("Rooms", "rooms"))
    if (admin) {
        add(BottomTab("Charts", "dashboard"))
        add(BottomTab("Energy", "energy"))
        add(BottomTab("Holiday", null))
    }
    add(BottomTab("Account", "account"))
}

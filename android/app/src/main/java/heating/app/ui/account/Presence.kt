package heating.app.ui.account

import heating.app.data.AccountUser
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

private fun instant(iso: String): Instant = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrElse { Instant.parse(iso) }

/** "just now", "5 minutes ago", "3 hours ago", "2 days ago". */
fun ago(iso: String, now: Instant = Instant.now()): String {
    val minutes = Duration.between(instant(iso), now).toMinutes().coerceAtLeast(0)
    if (minutes < 1) return "just now"
    if (minutes < 60) return "$minutes minute${if (minutes == 1L) "" else "s"} ago"
    val hours = Math.round(minutes / 60.0)
    if (hours < 24) return "$hours hour${if (hours == 1L) "" else "s"} ago"
    val days = Math.round(hours / 24.0)
    return "$days day${if (days == 1L) "" else "s"} ago"
}

/** "Active now", "Last seen 3 hours ago", or "Not signed in". */
fun presence(user: AccountUser, now: Instant = Instant.now()): String = when {
    user.activeNow == true -> "Active now"
    (user.sessions ?: 0) > 0 -> user.lastSeen?.let { "Last seen ${ago(it, now)}" } ?: "Signed in"
    else -> "Not signed in" + (user.lastSeen?.let { " · last seen ${ago(it, now)}" } ?: "")
}

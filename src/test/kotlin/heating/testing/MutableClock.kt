package heating.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock tests move by hand. Never sleep in tests; call [advance] or [set]. */
class MutableClock(
    private var instant: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
    override fun instant(): Instant = instant

    fun advance(duration: Duration) {
        instant = instant.plus(duration)
    }

    fun set(to: Instant) {
        instant = to
    }
}

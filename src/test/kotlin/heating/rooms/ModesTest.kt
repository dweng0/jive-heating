package heating.rooms

import heating.testing.TestHeating
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class ModesTest {

    @Test
    // BDD: Manual mode holds the target until changed
    fun manual_mode_holds_the_target_until_changed() {
        val t = TestHeating()
        val settings = ZoneSettings(mode = Mode.MANUAL, manualTarget = 21.0)
        
        val target = EffectiveTarget.of(settings, t.clock.instant().atZone(t.clock.zone))
        assertEquals(21.0, target)

        t.clock.advance(Duration.ofDays(3))
        
        val targetAfterTime = EffectiveTarget.of(settings, t.clock.instant().atZone(t.clock.zone))
        assertEquals(21.0, targetAfterTime)
    }
}

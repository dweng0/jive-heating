package heating.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class FriendlyErrorTest {
    @Test
    fun `an error with no message never shows as a bare full stop`() {
        assertEquals("The heating service doesn't know about that yet. It may need updating.", friendlyError(HeatingApiException(404, "")))
        assertEquals("The heating service refused that (500).", friendlyError(HeatingApiException(500, "")))
        assertEquals("Meter not found.", friendlyError(HeatingApiException(404, "meter not found")))
    }
}

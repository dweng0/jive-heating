package heating.app.ui.schedule

import heating.app.data.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayEditorTest {

    private val day = listOf(Block("00:00", 16.0), Block("06:30", 20.0), Block("22:30", 16.0))

    @Test
    fun `warmer and cooler step half a degree within range`() {
        assertEquals(20.5, DayEditor.warmer(day, 1)[1].target)
        assertEquals(15.5, DayEditor.cooler(day, 0)[0].target)
        assertEquals(30.0, DayEditor.warmer(listOf(Block("00:00", 30.0)), 0)[0].target)
    }

    @Test
    fun `adding a change splits the longest gap at a 15 minute boundary`() {
        val added = DayEditor.add(day)
        // Longest gap is 06:30-22:30 (16 h); midpoint 14:30.
        assertEquals(listOf("00:00", "06:30", "14:30", "22:30"), added.map { it.start })
        assertEquals(20.0, added[2].target)
    }

    @Test
    fun `a day holds at most six changes`() {
        var blocks = day
        repeat(10) { blocks = DayEditor.add(blocks) }
        assertEquals(6, blocks.size)
        assertFalse(DayEditor.canAdd(blocks))
    }

    @Test
    fun `the midnight change cannot be removed`() {
        assertEquals(day, DayEditor.remove(day, 0))
        assertEquals(listOf("00:00", "22:30"), DayEditor.remove(day, 1).map { it.start })
    }

    @Test
    fun `moving a change keeps order and stays between neighbours`() {
        assertEquals("07:00", DayEditor.move(day, 1, +30)[1].start)
        // Cannot move past the next change (22:30) or before the previous (00:00).
        assertEquals("22:15", DayEditor.move(day, 1, +24 * 60)[1].start)
        assertEquals("00:15", DayEditor.move(day, 1, -24 * 60)[1].start)
        // Midnight block never moves.
        assertEquals("00:00", DayEditor.move(day, 0, +60)[0].start)
    }

    @Test
    fun `validation matches the service rules`() {
        assertNull(DayEditor.problem(day))
        assertEquals("first change must start at 00:00", DayEditor.problem(listOf(Block("06:00", 16.0))))
        assertEquals("changes must be in time order", DayEditor.problem(listOf(Block("00:00", 16.0), Block("09:00", 20.0), Block("08:00", 16.0))))
        assertEquals("a day may have at most 6 changes", DayEditor.problem(List(7) { Block("00:0$it", 16.0) }))
        assertTrue(DayEditor.canAdd(day))
    }
}

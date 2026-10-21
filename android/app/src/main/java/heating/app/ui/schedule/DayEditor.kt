package heating.app.ui.schedule

import heating.app.data.Block
import heating.app.ui.minutes
import heating.app.ui.stepTarget

/** Pure edits on one day's schedule. Rules match the service: 1-6 changes, first at 00:00, in time order. */
object DayEditor {
    const val MAX_CHANGES = 6
    private const val STEP = 15
    private const val DAY = 24 * 60

    fun canAdd(blocks: List<Block>) = blocks.size < MAX_CHANGES

    fun warmer(blocks: List<Block>, i: Int) = update(blocks, i) { it.copy(target = stepTarget(it.target, +1)) }

    fun cooler(blocks: List<Block>, i: Int) = update(blocks, i) { it.copy(target = stepTarget(it.target, -1)) }

    /** Add a change in the middle of the longest gap, copying the temperature already in force there. */
    fun add(blocks: List<Block>): List<Block> {
        if (!canAdd(blocks) || blocks.isEmpty()) return blocks
        val ends = blocks.drop(1).map { minutes(it.start) } + DAY
        val gaps = blocks.indices.map { ends[it] - minutes(blocks[it].start) }
        val i = gaps.indices.maxBy { gaps[it] }
        if (gaps[i] < STEP * 2) return blocks
        val mid = (minutes(blocks[i].start) + gaps[i] / 2) / STEP * STEP
        return blocks.toMutableList().apply { add(i + 1, Block(hhmm(mid), blocks[i].target)) }
    }

    fun remove(blocks: List<Block>, i: Int): List<Block> =
        if (i == 0) blocks else blocks.filterIndexed { j, _ -> j != i }

    /** Shift change [i] by [delta] minutes, staying at least 15 minutes from its neighbours. */
    fun move(blocks: List<Block>, i: Int, delta: Int): List<Block> {
        if (i == 0) return blocks
        val lo = minutes(blocks[i - 1].start) + STEP
        val hi = (blocks.getOrNull(i + 1)?.let { minutes(it.start) } ?: DAY) - STEP
        val moved = (minutes(blocks[i].start) + delta).coerceIn(lo, hi) / STEP * STEP
        return update(blocks, i) { it.copy(start = hhmm(moved)) }
    }

    fun problem(blocks: List<Block>): String? = when {
        blocks.isEmpty() || blocks[0].start != "00:00" -> "first change must start at 00:00"
        blocks.size > MAX_CHANGES -> "a day may have at most $MAX_CHANGES changes"
        blocks.zipWithNext().any { (a, b) -> minutes(b.start) <= minutes(a.start) } -> "changes must be in time order"
        else -> null
    }

    private fun update(blocks: List<Block>, i: Int, change: (Block) -> Block) =
        blocks.mapIndexed { j, b -> if (j == i) change(b) else b }

    private fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
}

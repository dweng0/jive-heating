package heating.app.ui.energy

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import heating.app.data.LivePower

/** Whether a league row is greyed (no reading), for tests. */
val LeagueGreyed = SemanticsPropertyKey<Boolean>("LeagueGreyed")
private var SemanticsPropertyReceiver.leagueGreyed by LeagueGreyed

/** Whether a league row is still sliding to its new place, for tests. */
val LeagueSliding = SemanticsPropertyKey<Boolean>("LeagueSliding")
private var SemanticsPropertyReceiver.leagueSliding by LeagueSliding

/** "up" or "down" while a row's share flashes after changing, else null, for tests. */
val LeagueFlash = SemanticsPropertyKey<String?>("LeagueFlash")
private var SemanticsPropertyReceiver.leagueFlash by LeagueFlash

/** How long a row takes to slide to its new place, and a number to count to its new value. */
private const val MOVE_MS = 500
/** How long a share stays green or red after it changes. */
private const val FLASH_MS = 700L

/**
 * What is using power right now, biggest first: a row per meter, with a bar behind it as long as its share of the house.
 * Rows slide to their new places, but none changes place more than once every 2 s; [now] is the clock that rule reads.
 */
@Composable
fun LeagueTable(live: LivePower?, now: () -> Long = { SystemClock.uptimeMillis() }) {
    val rows = leagueRows(live)
    val desired = rows.map { it.key }
    val placing = remember { arrayOf(Placing()) }
    var tick by remember { mutableIntStateOf(0) }
    val order = remember(desired, tick) { placeRows(placing[0], desired, now()).also { placing[0] = it }.order }
    // A held row is let go when its 2 s are up, without waiting for the next reading.
    LaunchedEffect(order, desired) {
        val t = now()
        val at = nextUnlock(placing[0], desired, t) ?: return@LaunchedEffect
        delay(at - t)
        tick++
    }
    if (rows.isEmpty()) return
    val byKey = rows.associateBy { it.key }
    val slides = remember { mutableMapOf<String, Slide>() }
    Card(Modifier.fillMaxWidth().testTag("league-table")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Using power now", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
            order.forEach { k -> byKey[k]?.let { row -> key(k) { LeagueRowView(row, slides.getOrPut(k) { Slide() }) } } }
        }
    }
}

/**
 * A row's moving parts, kept by the table per meter so they carry on when the row changes place: where it was last laid
 * out and how far it is still drawn from there, the figures it is counting from, and its last whole percent and flash.
 */
private class Slide(val offset: Animatable<Float, *> = Animatable(0f)) {
    var lastY: Float? = null
    var sliding by mutableStateOf(false)
    var watts: Animatable<Float, *>? = null
    var share: Animatable<Float, *>? = null
    var lastPct: Int? = null
    var flash by mutableStateOf<String?>(null)
}

/** Draws the row where it was, then eases it to where it is now, whenever its place in the column changes. */
private fun Modifier.slideToPlace(slide: Slide): Modifier = composed {
    val scope = rememberCoroutineScope()
    onPlaced { c ->
        val y = c.positionInParent().y
        val was = slide.lastY
        slide.lastY = y
        if (was != null && was != y) scope.launch {
            slide.sliding = true
            slide.offset.snapTo(slide.offset.value + was - y)
            slide.offset.animateTo(0f, tween(MOVE_MS))
            slide.sliding = false
        }
    }
        .graphicsLayer { translationY = slide.offset.value }
        .semantics { leagueSliding = slide.sliding }
}

/** [value], counting to each new value over half a second instead of jumping; [anim] holds where it has got to. */
@Composable
private fun countTo(value: Double?, anim: Animatable<Float, *>?, keep: (Animatable<Float, *>?) -> Unit): Double? {
    if (value == null) {
        keep(null)
        return null
    }
    val shown = anim ?: Animatable(value.toFloat()).also(keep)
    LaunchedEffect(value) { shown.animateTo(value.toFloat(), tween(MOVE_MS)) }
    return shown.value.toDouble()
}

@Composable
private fun LeagueRowView(row: LeagueRow, slide: Slide) {
    val greyed = row.state == LeagueState.NO_READING
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val barColor = if (row.state == LeagueState.ELSE) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val watts = countTo(row.watts, slide.watts) { slide.watts = it }
    val shownShare = countTo(row.share, slide.share) { slide.share = it }
    val share = (shownShare ?: 0.0).toFloat().coerceIn(0f, 1f)
    // The share flashes green for a moment when its whole percent rises, red when it falls.
    val pct = row.share?.let { (it * 100).roundToInt() }
    LaunchedEffect(pct) {
        val before = slide.lastPct
        slide.lastPct = pct
        if (before == null || pct == null || before == pct) return@LaunchedEffect
        slide.flash = if (pct > before) "up" else "down"
    }
    // Cleared on its own effect, so a row recomposed in a new place mid-flash still clears.
    LaunchedEffect(slide.flash) {
        if (slide.flash == null) return@LaunchedEffect
        delay(FLASH_MS)
        slide.flash = null
    }
    val flash = slide.flash
    val flashColor = when (flash) {
        "up" -> Color(0xFF2E7D32)
        "down" -> Color(0xFFC62828)
        else -> null
    }
    Box(
        Modifier.slideToPlace(slide).fillMaxWidth().clip(RoundedCornerShape(6.dp)).alpha(if (greyed) 0.5f else 1f)
            // The bar behind the row, as long as its share of the house, like the depth on one side of an order book.
            .drawBehind { drawRect(barColor, size = Size(size.width * share, size.height)) }
            .testTag("league-row").semantics { leagueGreyed = greyed },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.name, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (row.state == LeagueState.ELSE) muted else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                leagueWatts(row.copy(watts = watts)), Modifier.testTag("league-watts"),
                color = if (row.state == LeagueState.ON || row.state == LeagueState.ELSE) MaterialTheme.colorScheme.onSurface else muted,
            )
            Text(
                leagueShare(row.copy(share = shownShare)),
                Modifier.width(44.dp).testTag("league-share").semantics { leagueFlash = flash }
                    .then(if (flashColor != null) Modifier.clip(RoundedCornerShape(4.dp)).background(flashColor.copy(alpha = 0.2f)) else Modifier),
                textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall, color = flashColor ?: muted,
            )
        }
    }
}

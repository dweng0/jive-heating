package heating.app.ui.energy

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import heating.app.data.EnergyTotals
import kotlinx.coroutines.delay
import java.util.Locale

private val WHEEL = 30.dp
private val WHEEL_WIDTH = 21.dp

/** How often the usage counter moves on: often enough that its last wheel is seen to turn. */
private const val TICK_MS = 250L

/** One wheel of a mechanical counter, rolling to [digit] over [roll] ms. */
@Composable
private fun Wheel(digit: Int, tenths: Boolean, roll: Int, fraction: Color? = null) {
    val pos by animateFloatAsState(digit.toFloat(), tween(roll), label = "wheel")
    Box(
        Modifier.width(WHEEL_WIDTH).height(WHEEL).clipToBounds()
            .background(if (tenths) (fraction ?: zoneColor(PowerZone.HIGH, false).copy(alpha = 0.85f)) else MaterialTheme.colorScheme.onSurface),
    ) {
        // The strip of ten digits is taller than the wheel's window: let it keep its own height, or only the 0 is laid out.
        Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).offset(y = -WHEEL * pos)) {
            (0..9).forEach { d ->
                Box(Modifier.height(WHEEL).width(WHEEL_WIDTH), contentAlignment = Alignment.Center) {
                    Text("$d", color = MaterialTheme.colorScheme.surface, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
            }
        }
    }
}

/** A car's counter: [value] on wheels, its last [decimals] wheels the fraction, [prefix] such as "£" before them. */
@Composable
private fun Rolling(value: Double, digits: Int, decimals: Int, description: String, prefix: String? = null, roll: Int = 700, fraction: Color? = null) {
    val text = String.format(Locale.UK, "%.${decimals}f", value).padStart(digits + if (decimals > 0) 1 else 0, '0')
    Row(
        Modifier.background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(2.dp)).padding(2.dp).semantics { contentDescription = "$description ${String.format(Locale.UK, "%.${decimals}f", value)}" },
        verticalAlignment = Alignment.Bottom,
    ) {
        prefix?.let {
            Box(Modifier.height(WHEEL).padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
                Text(it, color = MaterialTheme.colorScheme.surface, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
        }
        text.forEachIndexed { i, ch ->
            if (ch == '.') Text(".", color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(horizontal = 1.dp))
            else Wheel(ch - '0', tenths = i >= text.length - decimals, roll = roll, fraction = fraction)
        }
    }
}

/** Whole-number wheels and fraction wheels for each period: enough to hold it, and the last turning while watched. */
private fun wheels(period: UsagePeriod): Pair<Int, Int> = when (period) {
    UsagePeriod.TODAY, UsagePeriod.WEEK -> 3 to 4
    UsagePeriod.MONTH -> 4 to 3
    UsagePeriod.YEAR -> 5 to 3
}

/**
 * The dashboard's counters, like a car's: the house's usage today, this week, this month or this year (a tap moves on
 * through them), in kWh or, with the "£" switch, its cost, rolling on at the last 5 minutes' average power; the gas
 * meter's latest whole day; and today against yesterday at the same time. The day is the household's, from the
 * service, and turns over at its midnight whatever the phone's clock says. [now] is the phone's clock.
 */
@Composable
fun TripMeters(totals: EnergyTotals?, readings: List<Pair<Long, Double?>>, dark: Boolean, now: () -> Long = System::currentTimeMillis) {
    var period by rememberSaveable { mutableStateOf(UsagePeriod.TODAY) }
    var showsCost by rememberSaveable { mutableStateOf(false) }
    var counter by remember { mutableStateOf<Counter?>(null) }
    val latestReadings by rememberUpdatedState(readings)

    // Every quarter second the counter rolls on; new totals are taken at once.
    LaunchedEffect(totals) {
        val t = totals ?: return@LaunchedEffect
        while (true) {
            val at = now() + serviceSkew(t)
            counter = counterAt(counter, t, fiveMinuteAverage(latestReadings, at), at)
            delay(TICK_MS)
        }
    }

    val kwh = counter?.get(period) ?: totals?.let { Counter("", it.today.kwh, it.week.kwh, it.month.kwh, it.year.kwh, 0)[period] } ?: 0.0
    val todayKwh = counter?.today ?: totals?.today?.kwh ?: 0.0
    val (digits, decimals) = wheels(period)
    val price = totals?.pencePerKwh ?: 0.0
    val currency = totals?.currency ?: "GBP"
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val what = if (showsCost) "Cost" else "Usage"

    val usage: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$what ${period.label}".uppercase(Locale.UK), style = MaterialTheme.typography.labelSmall, color = muted)
                Box(
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
                        .clickable(onClickLabel = if (showsCost) "Show usage" else "Show cost") { showsCost = !showsCost }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(if (showsCost) "kWh" else symbol(currency), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.clickable(onClickLabel = "Show usage ${period.next().label}") { period = period.next() },
            ) {
                if (showsCost) {
                    Rolling(kwh * price / 100, digits = digits - 1, decimals = decimals, description = "$what ${period.label}", prefix = symbol(currency), roll = (TICK_MS * 2).toInt())
                    Text(String.format(Locale.UK, "%.1f kWh", kwh), style = MaterialTheme.typography.labelMedium, color = muted)
                } else {
                    Rolling(kwh, digits = digits, decimals = decimals, description = "$what ${period.label} kWh", roll = (TICK_MS * 2).toInt())
                    Text("kWh · ${formatCost(kwh * price, currency)}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    // The gas meter's latest whole day, a day late, against the day before.
    val gasColumn: (@Composable () -> Unit)? = totals?.gas?.let { gas ->
        {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(gasDayLabel(gas.date), style = MaterialTheme.typography.labelSmall, color = muted)
                Rolling(gas.kwh, digits = 3, decimals = 1, description = "Gas kWh", fraction = heating.app.ui.theme.HeatingTheme.colors.heat.copy(alpha = 0.9f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("kWh · ${formatCost(gas.cost, currency)}", style = MaterialTheme.typography.labelMedium)
                    gasDelta(gas)?.let { (text, more) ->
                        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = zoneColor(if (more) PowerZone.MID else PowerZone.LOW, dark))
                    }
                }
            }
        }
    }
    val vs = totals?.let { versusYesterday(todayKwh, it.yesterdaySoFar) }
    val versus: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("TODAY VS YESTERDAY", style = MaterialTheme.typography.labelSmall, color = muted)
            Text(
                vs?.first ?: "Nothing recorded yesterday",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    vs == null -> muted
                    vs.second -> zoneColor(PowerZone.MID, dark)
                    else -> zoneColor(PowerZone.LOW, dark)
                },
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        androidx.compose.foundation.layout.BoxWithConstraints {
            if (maxWidth >= WIDE_WIDTH) {
                // Wide, like the browser: every counter in one row.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                    usage()
                    gasColumn?.invoke()
                    versus()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                        usage()
                        gasColumn?.invoke()
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { versus() }
                }
            }
        }
    }
}

/** At least this wide the counters sit in one row, like the browser's. */
val WIDE_WIDTH = 720.dp

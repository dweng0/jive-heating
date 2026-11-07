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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
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
import java.time.LocalDate
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
        Column(Modifier.offset(y = -WHEEL * pos)) {
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

/**
 * The dashboard's counters, like a car's: today's usage (or, tapped, its cost) rolling on at the last 5 minutes' average power, an
 * odometer for the day, week, month or year, and today against yesterday at the same time.
 */
@Composable
fun TripMeters(totals: EnergyTotals?, readings: List<Pair<Long, Double?>>, dark: Boolean, now: () -> Long = System::currentTimeMillis) {
    var period by rememberSaveable { mutableStateOf(OdometerPeriod.WEEK) }
    var showsCost by rememberSaveable { mutableStateOf(false) }
    var trip by remember { mutableStateOf<Trip?>(null) }
    val latestReadings by rememberUpdatedState(readings)

    // Every quarter second the usage counter rolls on; a new recorded figure is taken at once.
    LaunchedEffect(totals) {
        val t = totals ?: return@LaunchedEffect
        while (true) {
            val at = now()
            trip = tripAt(trip, LocalDate.now().toString(), t.today.kwh, fiveMinuteAverage(latestReadings, at), at)
            delay(TICK_MS)
        }
    }

    val todayKwh = trip?.kwh ?: totals?.today?.kwh ?: 0.0
    val rolled = totals?.let { maxOf(0.0, todayKwh - it.today.kwh) } ?: 0.0
    val odometer = totals?.let {
        when (period) {
            OdometerPeriod.TODAY -> it.today
            OdometerPeriod.WEEK -> it.week
            OdometerPeriod.MONTH -> it.month
            OdometerPeriod.YEAR -> it.year
        }.kwh + rolled
    } ?: 0.0
    val price = totals?.pencePerKwh ?: 0.0
    val currency = totals?.currency ?: "GBP"
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.clickable(onClickLabel = if (showsCost) "Show usage" else "Show cost") { showsCost = !showsCost },
            ) {
                Text(if (showsCost) "COST TODAY" else "USAGE TODAY", style = MaterialTheme.typography.labelSmall, color = muted)
                if (showsCost) {
                    Rolling(todayKwh * price / 100, digits = 2, decimals = 5, description = "Cost today", prefix = symbol(currency), roll = (TICK_MS * 2).toInt())
                    Text(String.format(Locale.UK, "%.1f kWh", todayKwh), style = MaterialTheme.typography.labelMedium, color = muted)
                } else {
                    Rolling(todayKwh, digits = 3, decimals = 3, description = "Usage today kWh", roll = (TICK_MS * 2).toInt())
                    Text("kWh · ${formatCost(todayKwh * price, currency)}", style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ODOMETER · ${period.label.uppercase()}", style = MaterialTheme.typography.labelSmall, color = muted)
                Rolling(odometer, digits = 5, decimals = 1, description = "Odometer kWh")
                Text("kWh · ${formatCost(odometer * price, currency)}", style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
            OdometerPeriod.entries.forEach { p -> FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) }) }
        }
        // The gas meter's latest whole day, a day late, against the day before.
        totals?.gas?.let { gas ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
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
        val vs = totals?.let { versusYesterday(todayKwh, it.yesterdaySoFar) }
        Text(
            vs?.first ?: "Nothing recorded yesterday",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = when {
                vs == null -> muted
                vs.second -> zoneColor(PowerZone.MID, dark)
                else -> zoneColor(PowerZone.LOW, dark)
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

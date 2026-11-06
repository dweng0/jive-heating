package heating.app.ui.energy

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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

private val WHEEL = 26.dp

/** One wheel of a mechanical counter, rolling to [digit]. */
@Composable
private fun Wheel(digit: Int, tenths: Boolean) {
    val pos by animateFloatAsState(digit.toFloat(), tween(700), label = "wheel")
    Box(
        Modifier.width(18.dp).height(WHEEL).clipToBounds()
            .background(if (tenths) zoneColor(PowerZone.HIGH, false).copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurface),
    ) {
        Column(Modifier.offset(y = -WHEEL * pos)) {
            (0..9).forEach { d ->
                Box(Modifier.height(WHEEL).width(18.dp), contentAlignment = Alignment.Center) {
                    Text("$d", color = MaterialTheme.colorScheme.surface, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }
        }
    }
}

/** A car's counter: [value] on wheels, its last [decimals] wheels the fraction. */
@Composable
private fun Rolling(value: Double, digits: Int, decimals: Int, description: String) {
    val text = String.format(Locale.UK, "%.${decimals}f", value).padStart(digits + if (decimals > 0) 1 else 0, '0')
    Row(
        Modifier.background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp)).padding(2.dp).semantics { contentDescription = "$description ${String.format(Locale.UK, "%.${decimals}f", value)}" },
        verticalAlignment = Alignment.Bottom,
    ) {
        text.forEachIndexed { i, ch ->
            if (ch == '.') Text(".", color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(horizontal = 1.dp))
            else Wheel(ch - '0', tenths = i >= text.length - decimals)
        }
    }
}

/**
 * The dashboard's counters, like a car's: today's trip meter rolling on at the last 5 minutes' average power, an
 * odometer for the day, week, month or year, and today against yesterday at the same time.
 */
@Composable
fun TripMeters(totals: EnergyTotals?, readings: List<Pair<Long, Double?>>, dark: Boolean, now: () -> Long = System::currentTimeMillis) {
    var period by rememberSaveable { mutableStateOf(OdometerPeriod.WEEK) }
    var trip by remember { mutableStateOf<Trip?>(null) }
    val latestReadings by rememberUpdatedState(readings)

    // Every second the trip meter rolls on; a new recorded figure is taken at once.
    LaunchedEffect(totals) {
        val t = totals ?: return@LaunchedEffect
        while (true) {
            val at = now()
            trip = tripAt(trip, LocalDate.now().toString(), t.today.kwh, fiveMinuteAverage(latestReadings, at), at)
            delay(1_000)
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
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("TRIP · TODAY", style = MaterialTheme.typography.labelSmall, color = muted)
                Rolling(todayKwh, digits = 3, decimals = 2, description = "Trip today kWh")
                Text("kWh · ${formatCost(todayKwh * price, currency)}", style = MaterialTheme.typography.labelMedium)
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

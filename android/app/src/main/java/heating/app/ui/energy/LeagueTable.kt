package heating.app.ui.energy

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

/** What is using power right now, biggest first: a row per meter, with a bar behind it as long as its share of the house. */
@Composable
fun LeagueTable(live: LivePower?) {
    val rows = leagueRows(live)
    if (rows.isEmpty()) return
    Card(Modifier.fillMaxWidth().testTag("league-table")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Using power now", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
            rows.forEach { row -> LeagueRowView(row) }
        }
    }
}

@Composable
private fun LeagueRowView(row: LeagueRow) {
    val greyed = row.state == LeagueState.NO_READING
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val barColor = if (row.state == LeagueState.ELSE) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val share = (row.share ?: 0.0).toFloat().coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).alpha(if (greyed) 0.5f else 1f)
            // The bar behind the row, as long as its share of the house, like the depth on one side of an order book.
            .drawBehind { drawRect(barColor, size = Size(size.width * share, size.height)) }
            .testTag("league-row").semantics { leagueGreyed = greyed },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.name, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (row.state == LeagueState.ELSE) muted else MaterialTheme.colorScheme.onSurface,
            )
            Text(leagueWatts(row), color = if (row.state == LeagueState.ON || row.state == LeagueState.ELSE) MaterialTheme.colorScheme.onSurface else muted)
            Text(leagueShare(row), Modifier.width(44.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

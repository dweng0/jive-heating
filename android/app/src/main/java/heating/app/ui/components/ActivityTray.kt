package heating.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.PowerOff
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import heating.app.data.ActivityEvent
import heating.app.ui.ACTIVITY_WIDE_DP
import heating.app.ui.activityDescription
import heating.app.ui.activityEmptyText
import heating.app.ui.activityText
import heating.app.ui.agoText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

const val ACTIVITY_PAGE = 50

/** [fresh] events joined onto [current], newest first, without repeating one already in the list. */
fun mergeActivity(fresh: List<ActivityEvent>, current: List<ActivityEvent>): List<ActivityEvent> {
    val known = current.map { it.id }.toSet()
    return (fresh.filter { it.id !in known } + current).sortedByDescending { it.id }
}

/**
 * What changed in the heating, newest first. Loads when it opens, adds events pushed live ([latest]),
 * and "Load older" pages back. A phone gets a few words per line; a tablet-width screen gets sentences.
 * TalkBack always reads the full sentence with its time.
 */
@Composable
fun ActivityTray(
    load: suspend (limit: Int, before: Long?) -> List<ActivityEvent>,
    latest: ActivityEvent?,
    modifier: Modifier = Modifier,
) {
    val brief = LocalConfiguration.current.screenWidthDp < ACTIVITY_WIDE_DP
    var events by remember { mutableStateOf<List<ActivityEvent>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var now by remember { mutableStateOf(Instant.now()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(attempt) {
        runCatching { load(ACTIVITY_PAGE, null) }
            .onSuccess { page ->
                events = mergeActivity(page, events)
                more = page.size == ACTIVITY_PAGE
                failed = false
                loaded = true
            }
            .onFailure { failed = true }
    }
    LaunchedEffect(latest?.id) { latest?.let { events = mergeActivity(listOf(it), events) } }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = Instant.now()
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Activity", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (failed) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Couldn't load activity", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { failed = false; attempt++ }) { Text("Retry") }
            }
        }
        activityEmptyText(loaded, failed, events)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn {
            items(events, key = { it.id }) { e ->
                val full = activityDescription(e, now)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) { contentDescription = full },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(iconFor(e), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        activityText(e, brief),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (brief) 1 else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clearAndSetSemantics { },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        agoText(e.at, now),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
            if (more) {
                item {
                    TextButton(onClick = {
                        scope.launch {
                            runCatching { load(ACTIVITY_PAGE, events.lastOrNull()?.id) }
                                .onSuccess { page ->
                                    events = mergeActivity(page, events)
                                    more = page.size == ACTIVITY_PAGE
                                }
                                .onFailure { failed = true }
                        }
                    }) { Text("Load older") }
                }
            }
        }
    }
}

private fun iconFor(e: ActivityEvent) = when (e.kind) {
    "setpoint", "knob" -> Icons.Rounded.Thermostat
    "mode" -> Icons.Rounded.Power
    "boost" -> Icons.Rounded.Bolt
    "holiday" -> Icons.Rounded.Flight
    "schedule" -> Icons.Rounded.CalendarMonth
    "offline" -> if (e.device != null) Icons.Rounded.PowerOff else Icons.Rounded.CloudOff
    else -> when (e.value?.content) {
        "heating" -> Icons.Rounded.LocalFireDepartment
        "cycling", "cycled" -> Icons.Rounded.Timer
        "resting" -> Icons.Rounded.HourglassBottom
        else -> Icons.Rounded.Bedtime
    }
}

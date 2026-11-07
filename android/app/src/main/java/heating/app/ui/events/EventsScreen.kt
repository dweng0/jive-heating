package heating.app.ui.events

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import heating.app.data.EventFilter
import heating.app.data.EventSort
import heating.app.data.EventSuggestion
import heating.app.data.EventTopic
import heating.app.data.FieldValue
import heating.app.data.HeatingApi
import heating.app.data.LoggedEvent
import heating.app.data.TopicNode
import heating.app.data.eventParams
import heating.app.data.filterablePath
import heating.app.data.friendlyError
import heating.app.data.isStale
import heating.app.data.mergeNewest
import heating.app.data.payloadFields
import heating.app.data.prettyPayload
import heating.app.data.shortPayload
import heating.app.data.suggest
import heating.app.data.toggleSort
import heating.app.data.topicTree
import heating.app.data.valueText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val LIVE_MILLIS = 3000L
private val TIME = DateTimeFormatter.ofPattern("d MMM HH:mm:ss")

private fun eventTime(iso: String): String = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(TIME)

private fun ago(iso: String, now: Instant = Instant.now()): String {
    val seconds = Duration.between(Instant.parse(iso), now).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds s ago"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86400 -> "${seconds / 3600} h ago"
        else -> "${seconds / 86400} days ago"
    }
}

/**
 * Settings > Events (admins): the newest events with chips to filter them, a Topics tree, and a payload view for the one
 * tapped. [onNotify] opens the rule builder with the topic and, from a field, its path and value.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(api: HeatingApi, onNotify: (topic: String, field: String?, value: String?) -> Unit, onError: (String) -> Unit = {}, contentPadding: PaddingValues = PaddingValues()) {
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(EventFilter()) }
    var sort by remember { mutableStateOf(EventSort.AT) }
    var descending by remember { mutableStateOf(true) }
    var showTopics by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf<List<LoggedEvent>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var total by remember { mutableStateOf<Long?>(null) }
    var topics by remember { mutableStateOf<List<EventTopic>>(emptyList()) }
    var paused by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<LoggedEvent?>(null) }
    var search by remember { mutableStateOf("") }
    var loadingMore by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(showTopics) { runCatching { api.eventTopics() }.onSuccess { topics = it } }

    // The first page whenever the chips or sort change.
    LaunchedEffect(filter, sort, descending) {
        runCatching { api.events(eventParams(filter, sort, descending)) }
            .onSuccess { events = it.events; next = it.next; total = it.total }
            .onFailure { onError(friendlyError(it)) }
    }

    // Live: new events join the top, unless paused, scrolled down, or sorted some other way.
    val live = !showTopics && sort == EventSort.AT && descending
    LaunchedEffect(live, paused, filter) {
        while (live && !paused) {
            delay(LIVE_MILLIS)
            if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) continue
            runCatching { api.events(eventParams(filter, sort, descending)) }.onSuccess {
                events = mergeNewest(events, it.events)
                total = it.total
            }
        }
    }

    // The next 100 load as the end of the list comes into view.
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= events.size - 5 } ?: false } }
    LaunchedEffect(nearEnd, next) {
        val cursor = next
        if (nearEnd && cursor != null && !loadingMore && !showTopics) {
            loadingMore = true
            runCatching { api.events(eventParams(filter, sort, descending, cursor)) }
                .onSuccess { page -> events = events + page.events.filter { e -> events.none { it.id == e.id } }; next = page.next }
                .onFailure { onError(friendlyError(it)) }
            loadingMore = false
        }
    }

    val suggestions = remember(search, topics) { if (search.isBlank()) emptyList() else suggest(search, topics) }
    fun add(s: EventSuggestion) {
        filter = filter.with(s.add)
        search = ""
        showTopics = false
    }

    Column(Modifier.padding(contentPadding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !showTopics, onClick = { showTopics = false }, label = { Text("Events") })
            FilterChip(selected = showTopics, onClick = { showTopics = true }, label = { Text("Topics") })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("event-chips")) {
            filter.chips().forEach { chip ->
                InputChip(
                    selected = true,
                    onClick = { filter = filter.without(chip.kind) },
                    label = { Text(chip.label) },
                    trailingIcon = { Icon(Icons.Rounded.Close, "Remove ${chip.label}") },
                )
            }
            if (filter.chips().isNotEmpty()) TextButton(onClick = { filter = EventFilter() }) { Text("Clear") }
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search topics, devices, fields") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggestions.isNotEmpty()) {
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                suggestions.forEach { s ->
                    Text(s.label, Modifier.fillMaxWidth().clickable { add(s) }.padding(vertical = 10.dp, horizontal = 8.dp))
                }
            }
        } else if (search.isBlank() && !showTopics) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggest("", topics).forEach { s -> FilterChip(selected = false, onClick = { add(s) }, label = { Text(s.label) }) }
            }
        }

        if (showTopics) {
            TopicTree(topics, onShow = { filter = filter.with(EventFilter(topic = it)); showTopics = false })
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(total?.let { "$it event${if (it == 1L) "" else "s"}" }.orEmpty(), Modifier.testTag("event-count"), style = MaterialTheme.typography.bodyMedium)
                FilterChip(
                    selected = sort == EventSort.AT,
                    onClick = { toggleSort(sort, descending, EventSort.AT).let { sort = it.first; descending = it.second } },
                    label = { Text(if (sort == EventSort.AT && !descending) "Time ↑" else "Time ↓") },
                )
                FilterChip(
                    selected = sort == EventSort.TOPIC,
                    onClick = { toggleSort(sort, descending, EventSort.TOPIC).let { sort = it.first; descending = it.second } },
                    label = { Text(if (sort == EventSort.TOPIC && descending) "Topic ↓" else "Topic ↑") },
                )
                if (live) TextButton(onClick = { paused = !paused }) { Text(if (paused) "Resume" else "Pause") }
            }
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("event-list")) {
                items(events, key = { it.id }) { e ->
                    Card(Modifier.fillMaxWidth().clickable { open = e }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(e.topic, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(shortPayload(e.payload, 120), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                eventTime(e.at) + if (e.retained) " · retained" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (total == 0L) item { Text("No events.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    open?.let { e ->
        PayloadDialog(
            event = e,
            onDismiss = { open = null },
            onFilter = { field, value -> open = null; filter = filter.with(EventFilter(fieldValue = FieldValue(field, value))) },
            onTopic = { open = null; filter = filter.with(EventFilter(topic = it)) },
            onNotify = { topic, field, value -> open = null; onNotify(topic, field, value) },
        )
    }
}

/** One event's whole payload, field by field, with what can be done with the event. */
@Composable
private fun PayloadDialog(
    event: LoggedEvent,
    onDismiss: () -> Unit,
    onFilter: (field: String, value: String) -> Unit,
    onTopic: (String) -> Unit,
    onNotify: (topic: String, field: String?, value: String?) -> Unit,
) {
    val context = LocalContext.current
    val text = prettyPayload(event)
    val fields = if (event.json) payloadFields(event.payload) else emptyList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.topic, fontFamily = FontFamily.Monospace, fontSize = 15.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("payload"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(eventTime(event.at) + if (event.retained) " · retained" else "", style = MaterialTheme.typography.bodySmall)
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                fields.forEach { f ->
                    Column(Modifier.testTag("payload-field")) {
                        Text("${f.path}: ${shortPayload(f.value, 60)}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row {
                            if (filterablePath(f.path)) TextButton(onClick = { onFilter(f.path, valueText(f.value)) }) { Text("Filter by this") }
                            TextButton(onClick = { onNotify(event.topic, f.path, valueText(f.value)) }) { Text("Notify me when…") }
                        }
                    }
                }
                Row {
                    TextButton(onClick = { onTopic(event.topic) }) { Text("Show this topic") }
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("payload", text))
                    }) { Text("Copy") }
                }
                TextButton(onClick = { onNotify(event.topic, null, null) }) { Text("Notify me when…") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Topics split on "/" into a tree: message count, last seen and last payload; tap a name to filter to it. Quiet topics are greyed. */
@Composable
private fun TopicTree(topics: List<EventTopic>, onShow: (String) -> Unit) {
    val tree = remember(topics) { topicTree(topics) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val now = Instant.now()
    val rows = visibleTopicRows(tree, expanded)
    LazyColumn(Modifier.testTag("topic-tree")) {
        items(rows, key = { it.first.path }) { (node, depth) ->
            val stale = isStale(node.lastSeen, now)
            Row(Modifier.fillMaxWidth().alpha(if (stale) 0.5f else 1f).padding(start = (depth * 16).dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
                if (node.children.isNotEmpty()) {
                    val isOpen = node.path in expanded
                    IconButton(onClick = { expanded = if (isOpen) expanded - node.path else expanded + node.path }) {
                        Icon(if (isOpen) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowRight, "${if (isOpen) "Collapse" else "Expand"} ${node.path}")
                    }
                } else {
                    Row(Modifier.padding(start = 48.dp)) {}
                }
                Column(Modifier.clickable { onShow(node.filter) }.weight(1f).padding(vertical = 4.dp)) {
                    Text(node.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Text("${node.count} message${if (node.count == 1L) "" else "s"} · ${ago(node.lastSeen, now)}", style = MaterialTheme.typography.bodySmall)
                    node.own?.let { Text(shortPayload(it.lastPayload, 80), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

private fun visibleTopicRows(nodes: List<TopicNode>, expanded: Set<String>, depth: Int = 0): List<Pair<TopicNode, Int>> =
    nodes.flatMap { n -> listOf(n to depth) + if (n.path in expanded) visibleTopicRows(n.children, expanded, depth + 1) else emptyList() }

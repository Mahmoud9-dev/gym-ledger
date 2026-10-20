package com.mahmoud.gymledger

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val Ink = Color(0xFF101916)
private val Panel = Color(0xFF1A2621)
private val Lime = Color(0xFFC6F36B)
private val Muted = Color(0xFFABB9AF)
private val Scheme = darkColorScheme(primary = Lime, onPrimary = Ink, background = Ink, surface = Panel,
    onSurface = Color(0xFFF2F5EF), onBackground = Color(0xFFF2F5EF), secondary = Color(0xFF9DCEBD),
    secondaryContainer = Color(0xFF354A2F), onSecondaryContainer = Lime, onSurfaceVariant = Muted, outline = Color(0xFF4A5B50))

@Composable
fun GymLedger(model: GymViewModel) {
    MaterialTheme(colorScheme = Scheme) {
        val routines by model.routines.collectAsStateWithLifecycle()
        val workouts by model.workouts.collectAsStateWithLifecycle()
        val entries by model.entries.collectAsStateWithLifecycle()
        val cardio by model.cardio.collectAsStateWithLifecycle()
        val wifi by model.wifi.collectAsStateWithLifecycle()
        val busy by model.busy.collectAsStateWithLifecycle()
        val saving by model.saving.collectAsStateWithLifecycle()
        val message by model.message.collectAsStateWithLifecycle()
        val localError by model.localError.collectAsStateWithLifecycle()
        val connected by model.connected.collectAsStateWithLifecycle()
        val refreshed by model.lastRefresh.collectAsStateWithLifecycle()
        var page by rememberSaveable { mutableStateOf("Train") }
        var selectedCardio by rememberSaveable { mutableStateOf<String?>(null) }
        val cardioSession = cardio.firstOrNull { it.id == selectedCardio }
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
        val workout = workouts.firstOrNull { it.id == selected }
        val context = LocalContext.current
        BackHandler(selected != null || selectedCardio != null) { selected = null; selectedCardio = null }
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = Ink,
            bottomBar = {
                if (selected == null && selectedCardio == null) NavigationBar(containerColor = Panel) {
                    listOf("Train", "Cardio", "History", "Connection").forEachIndexed { index, tab ->
                        NavigationBarItem(selected = page == tab, onClick = { page = tab },
                            icon = { Text(listOf("▤", "♡", "◷", "⇄")[index], fontSize = 23.sp) }, label = { Text(tab) })
                    }
                } else if (cardioSession?.status == "DRAFT") {
                    Surface(color = Panel) {
                        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                            Button(onClick = { model.finishCardio(cardioSession.id) },
                                enabled = saving == 0 && CardioRules.ready(cardioSession),
                                modifier = Modifier.fillMaxWidth().height(54.dp).testTag("finish-cardio")) {
                                Text(if (saving > 0) "Saving…" else "Finish cardio", fontWeight = FontWeight.Bold)
                            }
                            if (!CardioRules.ready(cardioSession)) Text("Enter a duration greater than zero to finish.", color = Muted, fontSize = 12.sp)
                        }
                    }
                } else if (workout?.status == "DRAFT") {
                    Surface(color = Panel) {
                        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                            var confirm by remember { mutableStateOf(false) }
                            Button(onClick = {
                                val current = entries.filter { it.sessionId == workout.id }
                                if (current.any { !it.done || it.reps.isBlank() || it.weight.isBlank() }) confirm = true
                                else model.finish(workout.id)
                            }, enabled = saving == 0, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                                Text(if (saving > 0) "Saving…" else "Finish workout", fontWeight = FontWeight.Bold)
                            }
                            if (confirm) AlertDialog(onDismissRequest = { confirm = false },
                                title = { Text("Finish with skipped sets?") },
                                text = { Text("Some sets are incomplete or not marked done. All entered values will be saved; blank fields will stay blank.") },
                                confirmButton = { TextButton(onClick = { confirm = false; model.finish(workout.id) }) { Text("Finish anyway") } },
                                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep logging") } })
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Lime)
                if (localError.isNotBlank()) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(localError)
                        TextButton(onClick = model::retry) { Text("Retry local save") }
                    }
                }
                if (message.isNotBlank()) Surface(color = Color(0xFF3C3825)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { model.message.value = "" }) { Text("Dismiss") }
                    }
                }
                if (cardioSession != null) {
                    val linked = workouts.firstOrNull { it.id == cardioSession.workoutId }
                    CardioScreen(cardioSession, cardio.filter { it.id != cardioSession.id && it.status != "DRAFT" && it.activity == cardioSession.activity && it.createdAt < cardioSession.createdAt }.maxByOrNull { it.createdAt },
                        linked?.let { Json.routine(it.routineJson).title }, wifi, busy, saving,
                        back = { selectedCardio = null }, onField = { field, value -> model.cardioField(cardioSession.id, field, value) })
                } else if (workout != null) {
                    val ownEntries = entries.filter { it.sessionId == workout.id }
                    val previous = model.previous(workout)
                    WorkoutScreen(workout, ownEntries, entries.filter { it.sessionId == previous?.id }, saving,
                        wifi, busy, back = { selected = null }, onField = { row, field, value -> model.field(workout.id, row, field, value) },
                        onDone = { row, done -> model.done(workout.id, row, done) },
                        addCardio = { model.startCardio(workout.date, workout.id) { selected = null; selectedCardio = it } })
                } else when (page) {
                    "Train" -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("GYM / LEDGER", color = Lime, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                StatusPill(if (wifi) "WI-FI" else "OFFLINE READY")
                            }
                            Spacer(Modifier.height(30.dp))
                            Text("Make every\nset count.", fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Text("Your workout, saved on your phone.\nYour sheet, updated on Wi-Fi.", color = Muted, lineHeight = 22.sp)
                            Spacer(Modifier.height(18.dp))
                            TextButton(onClick = {
                                val day = LocalDate.parse(date)
                                DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d).toString() }, day.year, day.monthValue - 1, day.dayOfMonth).show()
                            }) { Text("${prettyDate(date)}  ·  Change date") }
                        }
                        val drafts = workouts.filter { it.status == "DRAFT" }
                        if (drafts.isNotEmpty()) item { SectionLabel("PICK UP WHERE YOU LEFT OFF") }
                        items(drafts, key = { it.id }) { draft ->
                            Card(onClick = { selected = draft.id }, colors = CardDefaults.cardColors(containerColor = Color(0xFF293C2B))) {
                                Column(Modifier.padding(18.dp)) {
                                    Text("Resume ${Json.routine(draft.routineJson).title}", fontWeight = FontWeight.Bold, color = Lime)
                                    Text("${prettyDate(draft.date)} · Saved locally", color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item {
                            OutlinedButton(onClick = { page = "Cardio" }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Log cardio →") }
                        }
                        item { SectionLabel("CHOOSE YOUR WORKOUT") }
                        items(routines, key = { it.sheetId }) { routine ->
                            val index = routines.indexOf(routine) + 1
                            Card(onClick = {
                                val draft = drafts.firstOrNull { it.sheetId == routine.sheetId }
                                if (draft != null) selected = draft.id else model.start(routine, date) { selected = it }
                            }, modifier = Modifier.testTag("routine-${routine.sheetId}"), colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(index.toString().padStart(2, '0'), color = Lime, fontSize = 18.sp, modifier = Modifier.width(40.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(routine.title, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                                        Spacer(Modifier.height(5.dp))
                                        Text("${routine.exercises.size} exercises · ${routine.exercises.sumOf { it.sets.size }} sets", color = Muted, fontSize = 13.sp)
                                    }
                                    Text("→", color = Lime, fontSize = 26.sp)
                                }
                            }
                        }
                        if (!connected) item {
                            TextButton(onClick = { page = "Connection" }) { Text("Connect Google to enable sheet sync →") }
                        }
                        item { Text("Every edit saves locally. Finish a workout to queue it for sync.", color = Muted, fontSize = 12.sp) }
                    }
                    "Cardio" -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item {
                            Text("Cardio", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("A walk, a ride, or a run. Every session saved offline.", color = Muted)
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = {
                                val day = LocalDate.parse(date)
                                DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d).toString() }, day.year, day.monthValue - 1, day.dayOfMonth).show()
                            }) { Text("${prettyDate(date)}  ·  Change date") }
                            Button(onClick = { model.startCardio(date) { selectedCardio = it } }, enabled = saving == 0,
                                modifier = Modifier.fillMaxWidth().height(54.dp).testTag("start-cardio")) { Text("Start cardio") }
                        }
                        if (cardio.any { it.status == "DRAFT" }) item { SectionLabel("RESUME CARDIO") }
                        items(cardio.filter { it.status == "DRAFT" }, key = { it.id }) { session ->
                            CardioSummary(session, wifi, busy, saving) { selectedCardio = session.id }
                        }
                        item { SectionLabel("CARDIO HISTORY") }
                        if (cardio.none { it.status != "DRAFT" }) item { Text("Finished cardio will appear here and in History.", color = Muted) }
                        items(cardio.filter { it.status != "DRAFT" }, key = { it.id }) { session ->
                            CardioSummary(session, wifi, busy, saving) { selectedCardio = session.id }
                        }
                    }
                    "History" -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Text("Your sessions", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Progress you can take offline.", color = Muted)
                        }
                        val history = workouts.filter { it.status != "DRAFT" }
                        if (history.isEmpty() && cardio.none { it.status != "DRAFT" }) item { Text("Finish your first workout, or connect Google to download existing sessions.", color = Muted, modifier = Modifier.padding(vertical = 30.dp)) }
                        if (cardio.any { it.status != "DRAFT" }) item { SectionLabel("CARDIO") }
                        items(cardio.filter { it.status != "DRAFT" }, key = { "cardio-${it.id}" }) { session ->
                            CardioSummary(session, wifi, busy, saving) { selectedCardio = session.id }
                        }
                        if (history.isNotEmpty()) item { SectionLabel("STRENGTH") }
                        items(history, key = { it.id }) { session ->
                            Card(onClick = { selected = session.id }, colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Text(Json.routine(session.routineJson).title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                    Text(prettyDate(session.date), color = Muted)
                                    Text(status(session, wifi, busy, saving), color = if (session.error.isNotBlank()) Color(0xFFFFCC91) else Lime, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        item {
                            Text("Your sheet.\nConnected.", fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            Text("Workouts stay on this phone until they are safely saved to Google Sheets.", color = Muted)
                        }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    StatusPill(if (connected) "GOOGLE AUTHORIZED" else "GOOGLE NOT CONNECTED")
                                    Text(if (wifi) "Wi-Fi ready" else "Waiting for Wi-Fi", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                    Text("${workouts.count { it.status == "PENDING" }} workouts and ${cardio.count { it.status == "PENDING" }} cardio sessions waiting to upload\nLast refresh: $refreshed", color = Muted, lineHeight = 23.sp)
                                    Button(onClick = model::requestConnect, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Connect Google") }
                                    OutlinedButton(onClick = model::retry, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Sync / Retry") }
                                }
                            }
                        }
                        items(workouts.filter { it.status == "PENDING" && it.error.isNotBlank() }, key = { it.id }) { session ->
                            Text("${Json.routine(session.routineJson).title} · ${prettyDate(session.date)}\n${session.error}", color = Color(0xFFFFCC91), lineHeight = 22.sp)
                        }
                        items(cardio.filter { it.status == "PENDING" && it.error.isNotBlank() }, key = { "cardio-error-${it.id}" }) { session ->
                            Text("Cardio · ${prettyDate(session.date)}\n${session.error}", color = Color(0xFFFFCC91), lineHeight = 22.sp)
                        }
                        item {
                            SectionLabel("ONE-TIME GOOGLE SETUP")
                            Spacer(Modifier.height(10.dp))
                            Text("Enable Google Sheets API and register this Android app in your Google Cloud project using the package and signing SHA-1 in the included setup guide. Then connect the account that can edit your tracker.", color = Muted, lineHeight = 23.sp)
                            Spacer(Modifier.height(16.dp))
                            Text("Sync runs only while this app is open on Wi-Fi, after you finish a workout. Tutorial videos need internet. Clearing app data or uninstalling deletes workouts that have not synced.", color = Muted, lineHeight = 23.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun WorkoutScreen(workout: Workout, entries: List<SetEntry>, previous: List<SetEntry>, saving: Int, wifi: Boolean, busy: Boolean,
    back: () -> Unit, onField: (Int, String, String) -> Unit, onDone: (Int, Boolean) -> Unit, addCardio: () -> Unit) {
    val routine = remember(workout.routineJson) { Json.routine(workout.routineJson) }
    val editable = workout.status == "DRAFT"
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.testTag("workout-list")) {
        item {
            TextButton(onClick = back) { Text("← Back") }
            Text(routine.title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("${prettyDate(workout.date)} · ${entries.count { it.done }} / ${entries.size} sets", color = Muted)
            Spacer(Modifier.height(8.dp))
            Text(status(workout, wifi, busy, saving), color = if (workout.error.isNotBlank()) Color(0xFFFFCC91) else Lime, fontSize = 13.sp)
            if (!editable) {
                Text("Finished session · Read only", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                OutlinedButton(onClick = addCardio, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(52.dp).testTag("add-cardio")) { Text("Add cardio after this workout") }
            }
        }
        if (entries.size != routine.exercises.sumOf { it.sets.size }) item {
            Text("Loading saved sets…", color = Muted)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else routine.exercises.forEachIndexed { index, exercise ->
            item(key = "exercise-$index") {
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        if (exercise.group.isNotBlank()) SectionLabel(exercise.group.uppercase())
                        Spacer(Modifier.height(6.dp))
                        Text(exercise.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 27.sp)
                        if (exercise.tutorial.startsWith("https://")) TextButton(onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(exercise.tutorial))) }
                            catch (_: android.content.ActivityNotFoundException) { android.widget.Toast.makeText(context, "No app is available to open the tutorial.", android.widget.Toast.LENGTH_SHORT).show() }
                        }) { Text("Watch tutorial ↗", fontSize = 12.sp) }
                        exercise.sets.forEach { spec ->
                            val entry = entries.firstOrNull { it.row == spec.row } ?: SetEntry(workout.id, spec.row)
                            val last = previous.firstOrNull { it.row == spec.row }
                            SetEditor(entry, spec, routine.efforts, editable, last, onField, onDone)
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SetEditor(entry: SetEntry, spec: SetSpec, efforts: List<String>, editable: Boolean, previous: SetEntry?,
    onField: (Int, String, String) -> Unit, onDone: (Int, Boolean) -> Unit) {
    var details by rememberSaveable(entry.sessionId, entry.row) { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(if (entry.done) Color(0xFF263829) else Ink, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(spec.label, fontSize = 12.sp, color = Muted)
        if (previous?.hasData() == true) Text("Last: ${previous.reps.ifBlank { "—" }} reps · ${previous.weight.ifBlank { "—" }} kg${if (previous.effort.isBlank()) "" else " · ${previous.effort}"}", fontSize = 11.sp, color = Lime, modifier = Modifier.padding(top = 5.dp))
        if (editable) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EntryField(entry, "reps", entry.reps, "Reps", KeyboardType.Number, Modifier.weight(1f), onField)
                EntryField(entry, "weight", entry.weight, "kg", KeyboardType.Decimal, Modifier.weight(1f), onField)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Checkbox(checked = entry.done, onCheckedChange = { onDone(entry.row, it) }, modifier = Modifier.testTag("done-${entry.row}").semantics { contentDescription = "${spec.label}, done" })
                    Text("Done", color = Muted, fontSize = 10.sp)
                }
            }
            TextButton(onClick = { details = !details }, modifier = Modifier.testTag("details-${entry.row}"), contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(if (details) "Hide details ↑" else listOf(entry.effort, entry.rest.takeIf(String::isNotBlank)?.let { "${it}s rest" }.orEmpty(), if (entry.notes.isNotBlank()) "Note" else "").filter(String::isNotBlank).joinToString(" · ").ifBlank { "Effort / rest / note ↓" }, fontSize = 12.sp)
            }
            if (details) {
                Box {
                    OutlinedButton(onClick = { effortMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(entry.effort.ifBlank { "Choose effort (optional)" }) }
                    DropdownMenu(expanded = effortMenu, onDismissRequest = { effortMenu = false }) {
                        (listOf("") + efforts).forEach { effort ->
                            DropdownMenuItem(text = { Text(effort.ifBlank { "No effort recorded" }) }, onClick = { onField(entry.row, "effort", effort); effortMenu = false })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                EntryField(entry, "rest", entry.rest, "Rest (seconds)", KeyboardType.Number, Modifier.fillMaxWidth(), onField)
                Spacer(Modifier.height(10.dp))
                EntryField(entry, "notes", entry.notes, "Notes (optional)", KeyboardType.Text, Modifier.fillMaxWidth(), onField)
            }
        } else {
            Text("${entry.reps.ifBlank { "—" }} reps   ×   ${entry.weight.ifBlank { "—" }} kg", fontWeight = FontWeight.SemiBold, fontSize = 20.sp, modifier = Modifier.padding(top = 8.dp))
            val detail = listOf(entry.effort, entry.rest.takeIf(String::isNotBlank)?.let { "Rest: ${it}s" }.orEmpty(), entry.notes).filter(String::isNotBlank)
            if (detail.isNotEmpty()) Text(detail.joinToString("\n"), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable private fun EntryField(entry: SetEntry, field: String, value: String, label: String, keyboard: KeyboardType, modifier: Modifier,
    onField: (Int, String, String) -> Unit) {
    var text by rememberSaveable(entry.sessionId, entry.row, field) { mutableStateOf(value) }
    var invalid by remember { mutableStateOf(false) }
    OutlinedTextField(value = text, onValueChange = { next ->
        invalid = !InputRules.accepts(field, next)
        if (!invalid) { text = next; onField(entry.row, field, next) }
    }, label = { Text(label) }, singleLine = field != "notes", minLines = if (field == "notes") 2 else 1,
        isError = invalid, supportingText = if (invalid) {{ Text(if (field == "notes") "Maximum 5,000 characters" else "Use a positive number or leave blank") }} else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.testTag("$field-${entry.row}"), shape = RoundedCornerShape(10.dp))
}

@Composable private fun StatusPill(text: String) {
    Text(text, color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.background(Color(0xFF2B3B28), RoundedCornerShape(30.dp)).padding(horizontal = 11.dp, vertical = 7.dp))
}
@Composable private fun SectionLabel(text: String) { Text(text, color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp) }

private fun prettyDate(date: String): String = try { LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")) } catch (_: Exception) { date }
private fun status(workout: Workout, wifi: Boolean, busy: Boolean, saving: Int): String = when {
    workout.status == "DRAFT" -> if (saving > 0) "Saving…" else "Saved locally"
    workout.status in listOf("SYNCED", "IMPORTED") -> "Synced"
    workout.error.isNotBlank() -> workout.error
    busy -> "Syncing"
    !wifi -> "Saved locally · Waiting for Wi-Fi"
    else -> "Saved locally · Waiting to sync"
}

@Composable private fun CardioSummary(session: CardioSession, wifi: Boolean, busy: Boolean, saving: Int, open: () -> Unit) {
    Card(onClick = open, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("${if (session.status == "DRAFT") "Resume " else ""}${session.activity}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("${prettyDate(session.date)} · ${session.duration.ifBlank { "—" }} min${if (session.distance.isNotBlank()) " · ${session.distance} km" else ""}", color = Muted)
            Text(cardioStatus(session, wifi, busy, saving), color = if (session.error.isNotBlank()) Color(0xFFFFCC91) else Lime, fontSize = 12.sp)
        }
    }
}

@Composable private fun CardioScreen(session: CardioSession, previous: CardioSession?, strengthTitle: String?, wifi: Boolean, busy: Boolean, saving: Int,
    back: () -> Unit, onField: (String, String) -> Unit) {
    val editable = session.status == "DRAFT"
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("cardio-list")) {
        item {
            TextButton(onClick = back) { Text("← Back") }
            Text("Cardio session", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(prettyDate(session.date), color = Muted, modifier = Modifier.padding(top = 8.dp))
            if (session.workoutId.isNotBlank()) Text("After ${strengthTitle ?: "a strength workout"}", color = Muted)
            Text(cardioStatus(session, wifi, busy, saving), color = Lime, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (previous != null) Text("Previous ${session.activity.lowercase()}: ${previous.duration} min${if (previous.distance.isBlank()) "" else " · ${previous.distance} km"}", color = Lime, fontSize = 13.sp)
                    if (editable) {
                        CardioChoice("Activity", session.activity, CardioRules.activities) { onField("activity", it) }
                        CardioField(session, "duration", session.duration, "Duration (minutes)", KeyboardType.Decimal, onField)
                        CardioField(session, "distance", session.distance, "Distance (km, optional)", KeyboardType.Decimal, onField)
                        CardioChoice("Effort", session.effort, listOf("") + CardioRules.efforts) { onField("effort", it) }
                        CardioField(session, "notes", session.notes, "Notes (optional)", KeyboardType.Text, onField)
                    } else {
                        Text(session.activity, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("${session.duration} min${if (session.distance.isBlank()) "" else " · ${session.distance} km"}", fontSize = 22.sp)
                        if (session.effort.isNotBlank()) Text(session.effort, color = Muted)
                        if (session.notes.isNotBlank()) Text(session.notes, color = Muted)
                        Text("Finished cardio · Read only", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable private fun CardioChoice(label: String, value: String, choices: List<String>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("cardio-${label.lowercase()}")) {
            Text("$label: ${value.ifBlank { "Not recorded" }}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(choice.ifBlank { "Not recorded" }) }, onClick = { change(choice); expanded = false }) }
        }
    }
}

@Composable private fun CardioField(session: CardioSession, field: String, value: String, label: String, keyboard: KeyboardType, onField: (String, String) -> Unit) {
    var text by rememberSaveable(session.id, field) { mutableStateOf(value) }
    var invalid by remember { mutableStateOf(false) }
    OutlinedTextField(value = text, onValueChange = { next ->
        invalid = !CardioRules.accepts(field, next)
        if (!invalid) { text = next; onField(field, next) }
    }, label = { Text(label) }, singleLine = field != "notes", minLines = if (field == "notes") 2 else 1,
        isError = invalid, supportingText = if (invalid) {{ Text(if (field == "notes") "Maximum 5,000 characters" else "Use a positive number or leave blank") }} else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth().testTag("cardio-$field"), shape = RoundedCornerShape(10.dp))
}

private fun cardioStatus(session: CardioSession, wifi: Boolean, busy: Boolean, saving: Int): String = when {
    session.status == "DRAFT" -> if (saving > 0) "Saving…" else "Saved locally"
    session.status in listOf("SYNCED", "IMPORTED") -> "Synced"
    session.error.isNotBlank() -> session.error
    busy -> "Syncing"
    !wifi -> "Saved locally · Waiting for Wi-Fi"
    else -> "Saved locally · Waiting to sync"
}

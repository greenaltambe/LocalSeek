package com.augt.localseek.ui.study

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.augt.localseek.R
import com.augt.localseek.retrieval.FileResult
import com.augt.localseek.ui.ResultLeading
import com.augt.localseek.ui.components.LsButton
import com.augt.localseek.ui.components.LsOutlinedButton
import com.augt.localseek.ui.components.LsTextButton
import com.augt.localseek.ui.components.LsTonalButton
import kotlinx.coroutines.delay

private val BigButton = Modifier.fillMaxWidth().heightIn(min = 56.dp)

/**
 * The whole study flow in one screen: consent, indexing, practice, searches + judging, free use, SUS, export. Only reachable in
 * the study build type (see [StudyGate]).
 */
@Composable
fun StudyScreen(
    onBack: () -> Unit,
    onOpenResult: (FileResult) -> Unit,
    onOpenSearch: () -> Unit,
    viewModel: StudyViewModel = viewModel()
) {
    if (!StudyGate.isAvailable()) return
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val session = ui.session
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text(
                    "Research study" + (session?.let { "  ·  ${it.participantCode}" } ?: ""),
                    style = MaterialTheme.typography.titleLarge
                )
            }
            ui.message?.let { m ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        LsTextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                    }
                }
            }
            when {
                ui.loading -> Text("Loading…", Modifier.padding(16.dp))
                session == null -> ConsentStep(onAgree = viewModel::acceptConsent)
                ui.judging != null -> JudgingStep(ui, viewModel, onOpenResult)
                else -> when (StudyStage.valueOf(session.stage)) {
                    StudyStage.INDEXING -> IndexingStep(ui, viewModel)
                    StudyStage.PRACTICE -> PracticeStep(ui, viewModel)
                    StudyStage.QUERIES -> QueryStep(ui, viewModel)
                    StudyStage.FREE_USE -> FreeUseStep(ui, viewModel, onOpenSearch)
                    StudyStage.SUS -> SusStep(viewModel)
                    StudyStage.EXPORT -> ExportStep(ui, viewModel)
                }
            }
        }
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) { content() }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

// ---- consent ------------------------------------------------------------------------------------------------------------

/** Renders **bold** spans of the consent markdown; everything else is shown as written. */
internal fun boldMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, p ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(p) } else append(p)
    }
}

@Composable
private fun ConsentStep(onAgree: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        context.resources.openRawResource(R.raw.study_consent).bufferedReader().use { it.readText() }
            .lines().filterNot { it.startsWith("# ") }.joinToString("\n").trim()
    }
    var agreed by rememberSaveable { mutableStateOf(false) }
    Page {
        Heading("Welcome, and thank you")
        Text(boldMarkdown(text), style = MaterialTheme.typography.bodyLarge)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = agreed, role = Role.Checkbox) { agreed = !agreed },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = agreed, onCheckedChange = null)
            Text("I agree", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
        }
        LsButton(onClick = onAgree, enabled = agreed, modifier = BigButton) { Text("Continue") }
    }
}

// ---- indexing -----------------------------------------------------------------------------------------------------------

private fun filesGranted(context: android.content.Context) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

private fun contactsGranted(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun IndexingStep(ui: StudyUiState, vm: StudyViewModel) {
    val context = LocalContext.current
    val session = ui.session ?: return
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val files = remember(refresh) { filesGranted(context) }
    val contacts = remember(refresh) { contactsGranted(context) }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val started = session.indexStartMs > 0
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(started) { while (started) { delay(15_000); now = System.currentTimeMillis() } }

    Page {
        Heading("Step 1: let LocalSeek read your phone")
        Text("Your participant code is ${session.participantCode}. Write it down; it is the only thing that identifies you.", style = MaterialTheme.typography.bodyLarge)
        Text(
            "LocalSeek needs to read the files and contacts on your phone so it can search them. This happens only on your phone. " +
                "Nothing is sent anywhere.",
            style = MaterialTheme.typography.bodyLarge
        )
        PermissionRow("Files", files) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                filesLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null)))
            } else permLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        PermissionRow("Contacts", contacts) { permLauncher.launch(Manifest.permission.READ_CONTACTS) }

        if (!started) {
            LsButton(onClick = vm::startIndexing, enabled = files, modifier = BigButton) { Text("Start indexing") }
            if (!files) Text("Please allow access to files first.", style = MaterialTheme.typography.bodyMedium)
            Text("Best: plug in the charger and leave the phone charging while it indexes.", style = MaterialTheme.typography.bodyMedium)
        } else {
            val idx = ui.indexing
            val total = idx.estimatedTotal
            if (idx.running || !idx.finishedOk) {
                if (total > 0) LinearProgressIndicator(progress = { (idx.documents.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (idx.running) "Indexing… ${idx.documents} files so far" + (if (total > 0) " of about $total" else "")
                    else "Indexing is not running. Tap the button below to start it again.",
                    style = MaterialTheme.typography.bodyLarge
                )
                if (!idx.running) LsOutlinedButton(onClick = vm::startIndexing, modifier = BigButton) { Text("Start indexing again") }
                if (StudyRules.shouldAdviseOvernight(total, now - session.indexStartMs)) {
                    Text("This can take a long time. Best: leave the phone charging overnight and come back to this screen. You can close the app; your progress is kept.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                Text("Indexing finished: ${session.files} files, ${session.apps} apps, ${session.contacts} contacts.", style = MaterialTheme.typography.bodyLarge)
            }
            LsButton(onClick = vm::finishIndexingStep, enabled = idx.finishedOk && !idx.running, modifier = BigButton) { Text("Continue") }
        }
    }
}

@Composable
private fun PermissionRow(name: String, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$name: " + if (granted) "allowed" else "not allowed yet", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (!granted) LsTonalButton(onClick = onGrant) { Text("Allow") }
    }
}

// ---- practice -----------------------------------------------------------------------------------------------------------

@Composable
private fun PracticeStep(ui: StudyUiState, vm: StudyViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    Page {
        Heading("Step 2: a practice search")
        Text(
            "Type any search to see how the next screen works. This one is for practice only and is not recorded.",
            style = MaterialTheme.typography.bodyLarge
        )
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Practice search") }, singleLine = true)
        LsButton(onClick = { vm.startPractice(text) }, enabled = text.isNotBlank() && !ui.searching, modifier = BigButton) {
            Text(if (ui.searching) "Searching…" else "Search")
        }
        LsOutlinedButton(onClick = vm::endPractice, modifier = BigButton) { Text("Skip practice, start the real searches") }
    }
}

// ---- searches ------------------------------------------------------------------------------------------------------------

@Composable
private fun QueryStep(ui: StudyUiState, vm: StudyViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<StudyKind?>(null) }
    var style by rememberSaveable { mutableStateOf<StudyStyle?>(null) }
    val done = ui.completedQueries
    Page {
        Heading(if (done == 0) "Step 3: your searches" else "Search ${done + 1}")
        Text("Searches done: $done. Please do 10 to 15 (at least ${StudyRules.MIN_QUERIES}).", style = MaterialTheme.typography.bodyLarge)
        if (StudyRules.canAddQuery(done)) {
            Text(
                "Think of something you know is on this phone: a file, an app or a contact. Type the search the way you normally would.",
                style = MaterialTheme.typography.bodyLarge
            )
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Your search") }, singleLine = true,
                keyboardOptions = KeyboardOptions.Default)
            Text("What are you looking for?", style = MaterialTheme.typography.titleMedium)
            ChipRow(StudyKind.values().toList(), kind, { kind = it }, { it.label })
            Text("How did you write it?", style = MaterialTheme.typography.titleMedium)
            ChipRow(StudyStyle.values().toList(), style, { style = it }, { it.label })
            LsButton(
                onClick = { vm.startQuery(text, kind!!, style!!); text = ""; kind = null; style = null },
                enabled = text.isNotBlank() && kind != null && style != null && !ui.searching,
                modifier = BigButton
            ) { Text(if (ui.searching) "Searching…" else "Search") }
        } else {
            Text("You have reached the maximum of ${StudyRules.MAX_QUERIES} searches.", style = MaterialTheme.typography.bodyLarge)
        }
        LsOutlinedButton(onClick = vm::finishQueries, enabled = StudyRules.canExport(done), modifier = BigButton) {
            Text(if (StudyRules.canExport(done)) "I am done with the searches" else "Finish (needs ${StudyRules.MIN_QUERIES} searches)")
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T?, onSelect: (T) -> Unit, label: (T) -> String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = selected == o, onClick = { onSelect(o) }, label = { Text(label(o), style = MaterialTheme.typography.bodyLarge) },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            )
        }
    }
}

// ---- judging -------------------------------------------------------------------------------------------------------------

@Composable
private fun JudgingStep(ui: StudyUiState, vm: StudyViewModel, onOpen: (FileResult) -> Unit) {
    val j = ui.judging ?: return
    val practice = j.queryId == null
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Heading(if (practice) "Practice: judge each result" else "Judge each result")
            Text(
                "For each item say whether it is what you were looking for. Tap an item to open it and check. " +
                    "Both buttons are needed for every item before you can go on.",
                style = MaterialTheme.typography.bodyLarge
            )
        }
        items(j.items, key = { it.key }) { item ->
            val r = item.result
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { onOpen(r) }, verticalAlignment = Alignment.CenterVertically) {
                        ResultLeading(r)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(r.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                            Text(
                                when (r.entityType.name) { "APP" -> "App"; "CONTACT" -> "Contact"; else -> "File" },
                                style = MaterialTheme.typography.labelMedium
                            )
                            r.snippets.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            }
                        }
                    }
                    val v = j.judged[item.key]
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        JudgeButton("Relevant", v == 1, Modifier.weight(1f)) { vm.judge(item, true) }
                        JudgeButton("Not relevant", v == 0, Modifier.weight(1f)) { vm.judge(item, false) }
                    }
                }
            }
        }
        item {
            Text("Was what you were looking for in this list?", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JudgeButton("Yes", j.found == 1, Modifier.weight(1f)) { vm.setFound(true) }
                JudgeButton("No", j.found == 0, Modifier.weight(1f)) { vm.setFound(false) }
            }
            Spacer(Modifier.size(8.dp))
            LsButton(onClick = vm::finishJudging, enabled = j.readyForNext, modifier = BigButton) {
                Text(if (practice) "Done with practice" else "Next")
            }
            if (practice) LsOutlinedButton(onClick = vm::endPractice, modifier = BigButton) { Text("Start the real searches") }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun JudgeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) LsButton(onClick = onClick, modifier = modifier.heightIn(min = 52.dp)) { Text(label) }
    else LsOutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 52.dp)) { Text(label) }
}

// ---- free use + SUS -----------------------------------------------------------------------------------------------------

@Composable
private fun FreeUseStep(ui: StudyUiState, vm: StudyViewModel, onOpenSearch: () -> Unit) {
    LaunchedEffect(Unit) { while (true) { vm.tickFreeUse(); delay(1000) } }
    val left = ui.freeUseSecondsLeft
    Page {
        Heading("Step 4: try LocalSeek freely")
        Text(
            "Before the last questions, please use LocalSeek like you normally would for about 2 minutes. Search for anything you like. " +
                "Then come back here with the \"Back to study\" button.",
            style = MaterialTheme.typography.bodyLarge
        )
        LsTonalButton(onClick = onOpenSearch, modifier = BigButton) { Text("Open LocalSeek search") }
        Text(if (left > 0) "About ${(left + 59) / 60} minute(s) to go." else "Two minutes are over. You can continue.", style = MaterialTheme.typography.bodyLarge)
        LsButton(onClick = vm::finishFreeUse, enabled = left == 0, modifier = BigButton) { Text("Continue to the questions") }
    }
}

@Composable
private fun SusStep(vm: StudyViewModel) {
    val answers = remember { mutableStateOf(List(10) { 0 }) }
    Page {
        Heading("Step 5: ten short questions")
        Text("About using LocalSeek in general. 1 = strongly disagree, 5 = strongly agree.", style = MaterialTheme.typography.bodyLarge)
        Sus.ITEMS.forEachIndexed { i, q ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${i + 1}. $q", style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        (1..5).forEach { v ->
                            Column(
                                Modifier.heightIn(min = 56.dp).selectable(selected = answers.value[i] == v, role = Role.RadioButton) {
                                    answers.value = answers.value.toMutableList().also { it[i] = v }
                                },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                RadioButton(selected = answers.value[i] == v, onClick = null, modifier = Modifier.padding(top = 8.dp))
                                Text("$v", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("disagree", style = MaterialTheme.typography.labelSmall); Text("agree", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        LsButton(onClick = { vm.submitSus(answers.value) }, enabled = answers.value.all { it in 1..5 }, modifier = BigButton) { Text("Continue") }
    }
}

// ---- export + delete ------------------------------------------------------------------------------------------------------

@Composable
private fun ExportStep(ui: StudyUiState, vm: StudyViewModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.buildExport() }
    var confirm by remember { mutableStateOf(false) }
    val json = ui.exportJson
    val code = ui.session?.participantCode.orEmpty()
    Page {
        Heading("Last step: send your result")
        Text(
            "This is the exact file that will be shared. It contains only numbers and your code. No search text, no names. " +
                "Please read it if you like, then tap Share and choose how to send it (for example WhatsApp or email to the study organiser).",
            style = MaterialTheme.typography.bodyLarge
        )
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            Text(json ?: "Preparing…", Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        LsButton(onClick = { json?.let { context.startActivity(vm.shareIntent(it, code)) } }, enabled = json != null, modifier = BigButton) { Text("Share") }
        Text(
            "After you have sent it, you can delete everything the study stored on this phone.",
            style = MaterialTheme.typography.bodyLarge
        )
        LsOutlinedButton(onClick = { confirm = true }, modifier = BigButton) { Text("Delete study data") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete study data?") },
            text = { Text("This removes your searches, your judgements and your answers from this phone. Do you also want to clear the search index LocalSeek built?") },
            confirmButton = {
                Column {
                    LsTextButton(onClick = { confirm = false; vm.deleteStudyData(alsoClearIndex = false) }) { Text("Delete study data, keep the index") }
                    LsTextButton(onClick = { confirm = false; vm.deleteStudyData(alsoClearIndex = true) }) { Text("Delete study data and clear the index") }
                }
            },
            dismissButton = { LsTextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

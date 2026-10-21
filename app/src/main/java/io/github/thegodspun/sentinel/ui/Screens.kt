package io.github.thegodspun.sentinel.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.thegodspun.sentinel.MainActivity
import io.github.thegodspun.sentinel.app
import io.github.thegodspun.sentinel.core.TrackerItem
import io.github.thegodspun.sentinel.habits.UsageCollector
import io.github.thegodspun.sentinel.service.AgentService
import io.github.thegodspun.sentinel.service.NotificationWatcher
import io.github.thegodspun.sentinel.service.PhoneControlService
import io.github.thegodspun.sentinel.work.SentinelWorker
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentinelScreen(chat: ChatViewModel, refreshKey: Int, actions: MainActivity.Actions) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(if (context.app.settings.apiKey.isBlank()) 3 else 0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listOf("Sentinel", "Habits", "Tracker", "Setup")[tab], fontWeight = FontWeight.Bold) },
                actions = {
                    if (tab == 0) TextButton(onClick = chat::newChat) { Text("New chat", color = SentinelColors.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = HeaderBlue,
                    titleContentColor = SentinelColors.White,
                    actionIconContentColor = SentinelColors.White,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                val itemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                )
                listOf(
                    "Chat" to Icons.Filled.Home,
                    "Habits" to Icons.Filled.Favorite,
                    "Tracker" to Icons.Filled.List,
                    "Setup" to Icons.Filled.Settings,
                ).forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, null) },
                        label = { Text(label) },
                        colors = itemColors,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> ChatTab(chat, actions)
                1 -> HabitsTab(refreshKey, actions, onAsk = { tab = 0; chat.send(it) })
                2 -> TrackerTab(refreshKey)
                else -> SetupTab(refreshKey, actions)
            }
        }
    }

    val confirmation by chat.confirmation.collectAsState()
    confirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { chat.answerConfirmation(false) },
            title = { Text("Allow this?") },
            text = { Text(pending.question, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { Button(onClick = { chat.answerConfirmation(true) }) { Text("Yes, do it") } },
            dismissButton = { OutlinedButton(onClick = { chat.answerConfirmation(false) }) { Text("No") } },
        )
    }
}

@Composable
private fun ChatTab(chat: ChatViewModel, actions: MainActivity.Actions) {
    val lines by chat.lines.collectAsState()
    val status by chat.status.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val settings = LocalContext.current.app.settings
    val briefing = settings.lastBriefing
    val briefingAt = settings.lastBriefingAt

    LaunchedEffect(lines.size, status) {
        val count = lines.size + if (status != null) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (lines.isEmpty()) {
                item { Welcome(briefing, briefingAt, onSuggestion = chat::send) }
            }
            items(lines) { line -> Bubble(line) }
            if (status != null) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(status.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask or tell me anything") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { chat.send(draft); draft = "" }),
                maxLines = 4,
            )
            if (draft.isBlank()) {
                Button(
                    onClick = actions::listen,
                    modifier = Modifier.height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                ) { Text("Talk", fontWeight = FontWeight.Bold) }
            } else {
                Button(onClick = { chat.send(draft); draft = "" }, modifier = Modifier.height(56.dp)) { Text("Send") }
            }
        }
    }
}

private val SUGGESTIONS = listOf(
    "What benefits and services should I look into first? Ask me what you need to know.",
    "What's due this week?",
    "What bad habits have you noticed, and what should I change first?",
    "Help me keep track of a letter I got from Social Security.",
    "Set a timer for 15 minutes for a pressure relief shift.",
)

@Composable
private fun Welcome(briefing: String, briefingAt: Long, onSuggestion: (String) -> Unit) {
    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (briefing.isNotBlank()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    val time = if (briefingAt > 0) {
                        " · " + java.time.Instant.ofEpochMilli(briefingAt).atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("EEE h:mm a", java.util.Locale.US))
                    } else ""
                    Text("Your briefing$time", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(briefing, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Text("Try asking:", style = MaterialTheme.typography.titleSmall)
        SUGGESTIONS.forEach { suggestion ->
            FilledTonalButton(onClick = { onSuggestion(suggestion) }, modifier = Modifier.fillMaxWidth()) {
                Text(suggestion)
            }
        }
    }
}

@Composable
private fun Bubble(line: ChatLine) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
        Text(
            line.text,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (line.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(14.dp),
                )
                .padding(12.dp),
            color = if (line.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun TrackerTab(refreshKey: Int) {
    val tracker = LocalContext.current.app.tracker
    var version by remember { mutableIntStateOf(0) }
    val items = remember(refreshKey, version) {
        tracker.all().sortedWith(compareBy<TrackerItem> { it.done }.thenBy(nullsLast()) { it.dueDateTime() })
    }

    if (items.isEmpty()) {
        Text(
            "Nothing tracked yet. Tell Sentinel about any appointment, deadline, claim or letter " +
                "and it will save it here and remind you ahead of time.",
            modifier = Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(items, key = { it.id }) { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (item.done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(dueLabel(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { tracker.update(item.id) { it.copy(done = !it.done) }; version++ }) {
                    Icon(
                        Icons.Filled.Check,
                        if (item.done) "Reopen" else "Mark done",
                        tint = if (item.done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                    )
                }
                IconButton(onClick = { tracker.delete(item.id); version++ }) {
                    Icon(Icons.Filled.Delete, "Delete")
                }
            }
            HorizontalDivider()
        }
    }
}

private fun dueLabel(item: TrackerItem): String {
    val category = item.category.replaceFirstChar { it.uppercase() }
    if (item.done) return "$category · done"
    val date = item.dueDate() ?: return category
    val days = ChronoUnit.DAYS.between(LocalDate.now(), date)
    val relative = when {
        days < 0 -> "OVERDUE by ${-days} day(s)"
        days == 0L -> "due TODAY"
        days == 1L -> "due tomorrow"
        else -> "due in $days days"
    }
    return "$category · ${item.due} · $relative"
}

@Composable
private fun SetupTab(refreshKey: Int, actions: MainActivity.Actions) {
    val context = LocalContext.current
    val settings = context.app.settings
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var aboutMe by remember { mutableStateOf(settings.aboutMe) }
    var speak by remember { mutableStateOf(settings.speakReplies) }
    var alwaysOn by remember { mutableStateOf(settings.alwaysOn) }
    var reviewAll by remember { mutableStateOf(settings.reviewAllNotifications) }
    var briefingHour by remember { mutableStateOf(settings.briefingHour.takeIf { it >= 0 }?.toString().orEmpty()) }
    var wakeBriefing by remember { mutableStateOf(settings.wakeBriefing) }
    var habitLearning by remember { mutableStateOf(settings.habitLearning) }
    var learnFromTexts by remember { mutableStateOf(settings.learnFromTexts) }
    var saved by remember { mutableStateOf(false) }

    val status = remember(refreshKey) { PermissionStatus.read(context) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("1. Claude API key")
        Text(
            "Create a key at console.anthropic.com (Settings → API keys) and paste it here. " +
                "Usage is billed to that account; set a monthly spend limit there.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { actions.openUrl("https://console.anthropic.com/settings/keys") }) {
            Text("Open the Claude Console")
        }
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it; saved = false },
            label = { Text("API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it; saved = false },
            label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("claude-opus-5-5 is the default. claude-haiku-5-5 costs far less but is less capable.") },
        )

        Section("2. About you")
        OutlinedTextField(
            value = aboutMe, onValueChange = { aboutMe = it; saved = false },
            label = { Text("Anything Sentinel should always know") },
            placeholder = { Text("State, injury level and date, insurance, veteran status, who helps you, what's hardest right now…") },
            modifier = Modifier.fillMaxWidth(), minLines = 4,
        )

        Section("3. Behavior")
        ToggleRow("Read replies out loud", speak) { speak = it; saved = false }
        ToggleRow("Always-on mode (persistent Talk notification)", alwaysOn) { alwaysOn = it; saved = false }
        ToggleRow("Review all notifications, not only benefit/health/money ones", reviewAll) { reviewAll = it; saved = false }
        ToggleRow("Brief me when I wake up and pick up my phone", wakeBriefing) { wakeBriefing = it; saved = false }
        ToggleRow("Learn my habits and coach me (needs Usage access)", habitLearning) { habitLearning = it; saved = false }
        ToggleRow("Learn about me from my texts (needs Text messages permission)", learnFromTexts) {
            learnFromTexts = it; saved = false
        }
        OutlinedTextField(
            value = briefingHour, onValueChange = { briefingHour = it.filter(Char::isDigit).take(2); saved = false },
            label = {
                Text(
                    if (wakeBriefing) "If no wake-up is noticed, brief me by this hour (0-23)"
                    else "Daily briefing hour (0-23, blank = off)"
                )
            },
            supportingText = {
                if (wakeBriefing) Text("Once Sentinel learns when you usually get up, it adjusts this on its own.")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                settings.apiKey = apiKey
                settings.model = model
                settings.aboutMe = aboutMe
                settings.speakReplies = speak
                settings.alwaysOn = alwaysOn
                settings.reviewAllNotifications = reviewAll
                settings.briefingHour = briefingHour.toIntOrNull()?.coerceIn(0, 23) ?: -1
                settings.wakeBriefing = wakeBriefing
                settings.habitLearning = habitLearning
                settings.learnFromTexts = learnFromTexts
                if (alwaysOn) AgentService.start(context) else AgentService.stop(context)
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
            colors = if (saved) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                )
            } else ButtonDefaults.buttonColors(),
        ) { Text(if (saved) "Saved ✓" else "Save settings") }

        Section("4. Permissions")
        Text(
            "Sentinel was installed outside the Play Store, so for the two \"special access\" items " +
                "Android may first say the setting is restricted. If so: tap \"App info\", then the ⋮ menu " +
                "at the top right, then \"Allow restricted settings\", and try again.",
            style = MaterialTheme.typography.bodyMedium,
        )
        PermissionRow("Notifications, microphone, contacts, calls, sending and reading texts, calendar", status.runtime) {
            actions.requestPermissions()
        }
        PermissionRow("Notification access (lets the sentinel spot deadlines)", status.notificationAccess) {
            actions.openNotificationAccess()
        }
        PermissionRow("Screen control (Accessibility → Sentinel)", status.accessibility) {
            actions.openAccessibility()
        }
        PermissionRow("Usage access (sleep, wake-up time and habit learning)", status.usageAccess) {
            actions.openUsageAccess()
        }
        PermissionRow("Run in background without battery limits", status.batteryExempt) {
            actions.requestBatteryExemption()
        }
        OutlinedButton(onClick = actions::openAppInfo, modifier = Modifier.fillMaxWidth()) { Text("App info") }

        Section("5. Quick access")
        Text(
            "Swipe down twice from the top of the screen, tap the pencil (edit), and drag the " +
                "\"Ask Sentinel\" tile into your Quick Settings to start talking from anywhere.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = { SentinelWorker.runNow(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("Run a sentinel check now")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (granted) "✓" else "✗",
            color = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(8.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (!granted) Button(onClick = onFix) { Text("Turn on") }
    }
}

private data class PermissionStatus(
    val runtime: Boolean,
    val notificationAccess: Boolean,
    val accessibility: Boolean,
    val batteryExempt: Boolean,
    val usageAccess: Boolean,
) {
    companion object {
        fun read(context: Context): PermissionStatus {
            val runtime = MainActivity.RUNTIME_PERMISSIONS
                .filter { it != Manifest.permission.POST_NOTIFICATIONS || android.os.Build.VERSION.SDK_INT >= 33 }
                .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            val accessibilityOn = PhoneControlService.instance != null ||
                AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    .orEmpty().contains("${context.packageName}/")
            return PermissionStatus(
                runtime = runtime,
                notificationAccess = NotificationWatcher.isEnabled(context),
                accessibility = accessibilityOn,
                batteryExempt = context.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName),
                usageAccess = UsageCollector.hasAccess(context),
            )
        }
    }
}

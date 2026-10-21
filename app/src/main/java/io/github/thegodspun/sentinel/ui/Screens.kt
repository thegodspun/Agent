package io.github.thegodspun.sentinel.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import io.github.thegodspun.sentinel.MainActivity
import io.github.thegodspun.sentinel.app
import io.github.thegodspun.sentinel.core.TrackerItem
import io.github.thegodspun.sentinel.habits.UsageCollector
import io.github.thegodspun.sentinel.service.AgentService
import io.github.thegodspun.sentinel.service.NotificationWatcher
import io.github.thegodspun.sentinel.service.PhoneControlService
import io.github.thegodspun.sentinel.work.SentinelWorker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val TABS = listOf(
    NavTab("Chat", SentinelIcons.Chat),
    NavTab("Habits", SentinelIcons.Insights),
    NavTab("Tracker", SentinelIcons.Tasks),
    NavTab("Setup", SentinelIcons.Tune),
)

@Composable
fun SentinelScreen(chat: ChatViewModel, refreshKey: Int, actions: MainActivity.Actions, initialTab: Int? = null) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(initialTab ?: if (context.app.settings.apiKey.isBlank()) 3 else 0) }
    // Hide the tab bar while typing so the keyboard has the room.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val barSpace = if (imeVisible) 0.dp else TAB_BAR_HEIGHT + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val hazeState = remember { HazeState() }
    SentinelBackground {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().haze(hazeState)) {
                CompositionLocalProvider(LocalBottomBarSpace provides barSpace) {
                    Crossfade(targetState = tab, animationSpec = tween(220), label = "tab") { current ->
                        when (current) {
                            0 -> ChatTab(chat, actions)
                            1 -> HabitsTab(refreshKey, actions, onAsk = { tab = 0; chat.send(it) })
                            2 -> TrackerTab(refreshKey)
                            else -> SetupTab(refreshKey, actions)
                        }
                    }
                }
            }
            // The tab bar floats over the content, which scrolls underneath the frosted glass.
            if (!imeVisible) {
                Box(Modifier.align(Alignment.BottomCenter)) { PillNavBar(TABS, tab, hazeState) { tab = it } }
            }
        }
    }

    val confirmation by chat.confirmation.collectAsState()
    confirmation?.let { pending ->
        ApprovalDialog(
            question = pending.question,
            onAllow = { chat.answerConfirmation(true) },
            onDecline = { chat.answerConfirmation(false) },
        )
    }
}

/** "Allow this?" over a blurred view of the screen behind it (Android 12+ window blur). */
@Composable
private fun ApprovalDialog(question: String, onAllow: () -> Unit, onDecline: () -> Unit) {
    Dialog(onDismissRequest = onDecline) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setDimAmount(0.25f)
            if (Build.VERSION.SDK_INT >= 31) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.also { it.blurBehindRadius = 64 }
            }
        }
        val dark = isSystemInDarkTheme()
        val shape = RoundedCornerShape(32.dp)
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(24.dp, shape, ambientColor = SentinelColors.Navy, spotColor = SentinelColors.Navy)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (dark) 0.9f else 0.94f))
                .gloss(if (dark) 0.06f else 0.6f)
                .border(1.dp, glassEdge(dark), shape)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBadge(SentinelIcons.Shield, size = 56.dp)
            Spacer(Modifier.height(16.dp))
            Text("Allow this?", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                question,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SoftButton("Not now", onDecline, modifier = Modifier.weight(1f))
                GradientButton("Allow", onAllow, modifier = Modifier.weight(1f))
            }
        }
        }
    }
}

// ---------------------------------------------------------------- Chat

private data class Suggestion(val icon: ImageVector, val text: String)

private val SUGGESTIONS = listOf(
    Suggestion(SentinelIcons.Sparkle, "What benefits and services should I look into first? Ask me what you need to know."),
    Suggestion(SentinelIcons.Tasks, "What's due this week?"),
    Suggestion(SentinelIcons.Insights, "What bad habits have you noticed, and what should I change first?"),
    Suggestion(SentinelIcons.Bell, "Help me keep track of a letter I got from Social Security."),
    Suggestion(SentinelIcons.Bolt, "Set a timer for 15 minutes for a pressure relief shift."),
)

@Composable
private fun ChatTab(chat: ChatViewModel, actions: MainActivity.Actions) {
    val lines by chat.lines.collectAsState()
    val status by chat.status.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val settings = LocalContext.current.app.settings
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 60 } }

    LaunchedEffect(lines.size, status) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size + if (status != null) 1 else 0)
    }

    fun submit() {
        if (draft.isNotBlank()) {
            chat.send(draft.trim())
            draft = ""
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "header") {
                    ChatHeader(
                        briefing = settings.lastBriefing,
                        briefingAt = settings.lastBriefingAt,
                        compact = lines.isNotEmpty(),
                        onNewChat = chat::newChat,
                    )
                }
                if (lines.isEmpty()) {
                    item(key = "suggestions") { Suggestions(onPick = chat::send) }
                }
                items(lines, key = { it.id }) { line -> Bubble(line, Modifier.animateItem()) }
                if (status != null) {
                    item(key = "thinking") { Thinking(status.orEmpty()) }
                }
            }
            Composer(
                draft = draft,
                onDraft = { draft = it },
                busy = status != null,
                onSend = ::submit,
                onTalk = actions::listen,
                onStop = chat::cancel,
            )
            Spacer(Modifier.height(LocalBottomBarSpace.current))
        }
        // Keeps scrolled messages from running under the status bar icons.
        if (scrolled) {
            Box(
                Modifier.fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
            )
        }
    }
}

@Composable
private fun ChatHeader(briefing: String, briefingAt: Long, compact: Boolean, onNewChat: () -> Unit) {
    val today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US))
    GradientHeader(
        eyebrow = today,
        title = greeting(),
        subtitle = if (compact) null else "Sentinel is keeping watch.",
        action = if (compact) {
            { HeaderIconButton(SentinelIcons.NewChat, "New chat", onNewChat) }
        } else null,
        content = if (compact) null else {
            {
                GlassPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(SentinelIcons.Sun, null, tint = SentinelColors.GreenLight, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        val time = if (briefingAt > 0) {
                            " · " + Instant.ofEpochMilli(briefingAt).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US))
                        } else ""
                        Text("Latest briefing$time", style = MaterialTheme.typography.labelLarge, color = SentinelColors.White)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        briefing.ifBlank {
                            "Your first briefing arrives when you wake up and pick up your phone: what's due, what came in overnight, and a note on your habits."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = SentinelColors.White.copy(alpha = if (briefing.isBlank()) 0.75f else 0.95f),
                    )
                }
            }
        },
    )
}

@Composable
private fun Suggestions(onPick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Try asking")
        SUGGESTIONS.forEach { s ->
            SentinelCard(padding = 14.dp, onClick = { onPick(s.text) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(s.icon, size = 36.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(s.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Avatar() {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(SentinelBrushes.Header),
        contentAlignment = Alignment.Center,
    ) { Icon(SentinelIcons.Shield, null, tint = SentinelColors.White, modifier = Modifier.size(16.dp)) }
}

@Composable
private fun Bubble(line: ChatLine, modifier: Modifier = Modifier) {
    if (line.fromUser) {
        Row(modifier.fillMaxWidth().padding(start = 64.dp, end = 14.dp), horizontalArrangement = Arrangement.End) {
            Text(
                line.text,
                modifier = Modifier
                    .shadow(6.dp, RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp), ambientColor = SentinelColors.Blue, spotColor = SentinelColors.Blue)
                    .clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                    .background(SentinelBrushes.Outgoing)
                    .gloss(0.22f)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                color = SentinelColors.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    } else {
        val dark = isSystemInDarkTheme()
        Row(modifier.fillMaxWidth().padding(start = 12.dp, end = 36.dp), verticalAlignment = Alignment.Bottom) {
            Avatar()
            Spacer(Modifier.width(8.dp))
            SelectionContainer {
                Text(
                    line.text,
                    modifier = Modifier
                        .shadow(4.dp, RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp), ambientColor = SentinelColors.Blue.copy(alpha = 0.2f), spotColor = SentinelColors.Blue.copy(alpha = 0.2f))
                        .clip(RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (dark) 0.85f else 0.92f))
                        .border(1.dp, glassEdge(dark), RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun Thinking(status: String) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 36.dp), verticalAlignment = Alignment.Bottom) {
        Avatar()
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypingDots()
            Spacer(Modifier.width(12.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onTalk: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            placeholder = { Text("Message Sentinel") },
            shape = RoundedCornerShape(28.dp),
            maxLines = 5,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        Spacer(Modifier.width(10.dp))
        if (draft.isBlank() || busy) MicButton(busy = busy, onClick = onTalk, onStop = onStop) else SendButton(onSend)
    }
}

// ---------------------------------------------------------------- Tracker

@Composable
private fun TrackerTab(refreshKey: Int) {
    val tracker = LocalContext.current.app.tracker
    var version by remember { mutableIntStateOf(0) }
    val all = remember(refreshKey, version) {
        tracker.all().sortedWith(compareBy(nullsLast()) { it.dueDateTime() })
    }
    val open = all.filter { !it.done }
    val done = all.filter { it.done }
    val today = LocalDate.now()
    val overdue = open.count { d -> d.dueDate()?.isBefore(today) == true }
    val thisWeek = open.count { d -> d.dueDate()?.let { !it.isBefore(today) && !it.isAfter(today.plusDays(7)) } == true }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp + LocalBottomBarSpace.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            GradientHeader(
                eyebrow = "Tracker",
                title = if (open.isEmpty()) "All clear" else "${open.size} on your list",
                subtitle = buildList {
                    if (overdue > 0) add("$overdue overdue")
                    if (thisWeek > 0) add("$thisWeek due this week")
                    if (isEmpty()) add("Deadlines, appointments, claims and benefits")
                }.joinToString(" · "),
            )
        }
        if (all.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    SentinelIcons.Tasks,
                    "Nothing tracked yet",
                    "Tell Sentinel about any appointment, deadline, claim or letter. It saves it here and reminds you ahead of time.",
                )
            }
        }
        if (open.isNotEmpty()) item(key = "open") { SectionTitle("Coming up", Modifier.padding(horizontal = 16.dp)) }
        items(open, key = { it.id }) { item ->
            TrackerCard(item, onToggle = { tracker.update(item.id) { it.copy(done = true) }; version++ }, onDelete = { tracker.delete(item.id); version++ })
        }
        if (done.isNotEmpty()) item(key = "done") { SectionTitle("Done", Modifier.padding(horizontal = 16.dp)) }
        items(done, key = { it.id }) { item ->
            TrackerCard(item, onToggle = { tracker.update(item.id) { it.copy(done = false) }; version++ }, onDelete = { tracker.delete(item.id); version++ })
        }
    }
}

@Composable
private fun TrackerCard(item: TrackerItem, onToggle: () -> Unit, onDelete: () -> Unit) {
    SentinelCard(Modifier.padding(horizontal = 16.dp), padding = 14.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .then(
                        if (item.done) Modifier.background(SentinelBrushes.Go)
                        else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    .clickable(role = Role.Checkbox, onClickLabel = if (item.done) "Reopen" else "Mark done", onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                if (item.done) Icon(Icons.Filled.Check, "Done", tint = SentinelColors.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(
                        item.category.replaceFirstChar { it.uppercase() },
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DuePill(item)
                }
                if (item.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(item.notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun DuePill(item: TrackerItem) {
    val due = item.dueDateTime() ?: return
    val days = ChronoUnit.DAYS.between(LocalDate.now(), due.toLocalDate())
    val time = if (item.due?.contains('T') == true) " " + due.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)) else ""
    val scheme = MaterialTheme.colorScheme
    val (text, container, content) = when {
        item.done -> Triple(due.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)), scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
        days < 0 -> Triple("Overdue ${-days}d", scheme.errorContainer, scheme.onErrorContainer)
        days == 0L -> Triple("Today$time", scheme.secondaryContainer, scheme.onSecondaryContainer)
        days == 1L -> Triple("Tomorrow$time", scheme.secondaryContainer, scheme.onSecondaryContainer)
        else -> Triple("In $days days · " + due.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)), scheme.primaryContainer, scheme.onPrimaryContainer)
    }
    Pill(text, container, content)
}

// ---------------------------------------------------------------- Setup

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
    var readChatsOnScreen by remember { mutableStateOf(settings.readChatsOnScreen) }

    val status = remember(refreshKey) { PermissionStatus.read(context) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GradientHeader(
            eyebrow = "Setup",
            title = "Make Sentinel yours",
            subtitle = if (apiKey.isBlank()) "Start by adding your Claude API key" else "${status.grantedCount} of 5 permissions on · changes save automatically",
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SentinelCard {
                CardTitle(SentinelIcons.Key, "Claude connection")
                SoftTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; settings.apiKey = it },
                    label = "API key",
                    visualTransformation = PasswordVisualTransformation(),
                    supporting = "From console.anthropic.com → Settings → API keys. Usage is billed there; set a monthly limit.",
                )
                Spacer(Modifier.height(12.dp))
                SoftTextField(
                    value = model,
                    onValueChange = { model = it; settings.model = it },
                    label = "Model",
                    supporting = "claude-opus-5-5 is the default. claude-haiku-5-5 costs far less but is less capable.",
                )
                Spacer(Modifier.height(12.dp))
                SoftButton("Get an API key", { actions.openUrl("https://console.anthropic.com/settings/keys") }, icon = SentinelIcons.Key)
            }

            SentinelCard {
                CardTitle(SentinelIcons.Person, "About you")
                SoftTextField(
                    value = aboutMe,
                    onValueChange = { aboutMe = it; settings.aboutMe = it },
                    label = "Anything Sentinel should always know",
                    placeholder = "State, injury level and date, insurance, veteran status, who helps you, what's hardest right now…",
                    singleLine = false,
                    minLines = 4,
                )
            }

            SentinelCard {
                CardTitle(SentinelIcons.Sun, "Briefings")
                SettingSwitch("Brief me when I wake up", "Sent the moment you start using your phone after sleeping", wakeBriefing) {
                    wakeBriefing = it; settings.wakeBriefing = it
                }
                Spacer(Modifier.height(8.dp))
                SoftTextField(
                    value = briefingHour,
                    onValueChange = { v ->
                        briefingHour = v.filter(Char::isDigit).take(2)
                        settings.briefingHour = briefingHour.toIntOrNull()?.coerceIn(0, 23) ?: -1
                    },
                    label = if (wakeBriefing) "Brief me by this hour if I haven't woken (0–23)" else "Daily briefing hour (0–23, blank = off)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supporting = if (wakeBriefing) "Once Sentinel learns when you usually get up, it adjusts this itself." else null,
                )
            }

            SentinelCard {
                CardTitle(SentinelIcons.Insights, "Learning")
                SettingSwitch("Learn my habits and coach me", "Sleep, wake-up time and phone use. Needs Usage access.", habitLearning) {
                    habitLearning = it; settings.habitLearning = it
                }
                RowDivider()
                SettingSwitch("Learn about me from my texts", "SMS history and incoming messages, once a day", learnFromTexts) {
                    learnFromTexts = it; settings.learnFromTexts = it
                }
                if (learnFromTexts) {
                    RowDivider()
                    SettingSwitch("Read Google Messages chats I open", "Both sides, including RCS. Needs Screen control.", readChatsOnScreen) {
                        readChatsOnScreen = it; settings.readChatsOnScreen = it
                    }
                }
                RowDivider()
                SettingSwitch("Review every notification", "Otherwise only benefit, health and money ones", reviewAll) {
                    reviewAll = it; settings.reviewAllNotifications = it
                }
            }

            SentinelCard {
                CardTitle(SentinelIcons.Mic, "Voice & always-on")
                SettingSwitch("Read replies out loud", null, speak) { speak = it; settings.speakReplies = it }
                RowDivider()
                SettingSwitch("Always-on", "Keeps a Talk button in your notifications and catches the moment you wake", alwaysOn) {
                    alwaysOn = it
                    settings.alwaysOn = it
                    if (it) AgentService.start(context) else AgentService.stop(context)
                }
            }

            SentinelCard {
                CardTitle(SentinelIcons.Lock, "Permissions")
                Text(
                    "If Android says a setting is restricted, tap App info → ⋮ (top right) → Allow restricted settings, then try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                PermissionItem("Phone basics", "Notifications, mic, contacts, calls, texts, calendar", status.runtime, actions::requestPermissions)
                RowDivider()
                PermissionItem("Notification access", "Spots deadlines and learns from messages", status.notificationAccess, actions::openNotificationAccess)
                RowDivider()
                PermissionItem("Screen control", "Operates apps for you; reads RCS chats you open", status.accessibility, actions::openAccessibility)
                RowDivider()
                PermissionItem("Usage access", "Sleep, wake-up time and habit learning", status.usageAccess, actions::openUsageAccess)
                RowDivider()
                PermissionItem("No battery limits", "Keeps the watch running in the background", status.batteryExempt, actions::requestBatteryExemption)
                Spacer(Modifier.height(12.dp))
                SoftButton("App info", actions::openAppInfo, icon = Icons.Filled.Settings)
            }

            SentinelCard {
                CardTitle(SentinelIcons.Bolt, "Quick access")
                Text(
                    "Swipe down twice, tap the pencil, and drag the \"Ask Sentinel\" tile into Quick Settings to talk from anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                SoftButton("Run a check now", { SentinelWorker.runNow(context) }, icon = SentinelIcons.Shield)
            }
            Spacer(Modifier.height(8.dp + LocalBottomBarSpace.current))
        }
    }
}

@Composable
private fun CardTitle(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        IconBadge(icon, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun PermissionItem(title: String, detail: String, granted: Boolean, onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .then(if (granted) Modifier.background(SentinelBrushes.Go) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (granted) Icons.Filled.Check else SentinelIcons.Lock,
                null,
                tint = if (granted) SentinelColors.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Pill("On", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        } else {
            SoftButton("Turn on", onFix, modifier = Modifier.widthIn(min = 96.dp))
        }
    }
}

private data class PermissionStatus(
    val runtime: Boolean,
    val notificationAccess: Boolean,
    val accessibility: Boolean,
    val batteryExempt: Boolean,
    val usageAccess: Boolean,
) {
    val grantedCount: Int get() = listOf(runtime, notificationAccess, accessibility, batteryExempt, usageAccess).count { it }

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

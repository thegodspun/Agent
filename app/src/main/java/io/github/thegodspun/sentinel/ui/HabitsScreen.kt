package io.github.thegodspun.sentinel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.thegodspun.sentinel.MainActivity
import io.github.thegodspun.sentinel.app
import io.github.thegodspun.sentinel.core.DaySummary
import io.github.thegodspun.sentinel.core.HabitInsight
import io.github.thegodspun.sentinel.core.HabitProfile
import io.github.thegodspun.sentinel.habits.UsageCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val COACH_REQUEST =
    "Review my habits from the last two weeks. Be honest about my bad habits, tell me what's " +
        "going well, and give me one concrete thing to change this week."

@Composable
fun HabitsTab(refreshKey: Int, actions: MainActivity.Actions, onAsk: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    var version by remember { mutableIntStateOf(0) }
    val hasAccess = remember(refreshKey) { UsageCollector.hasAccess(context) }

    LaunchedEffect(refreshKey, hasAccess) {
        if (hasAccess) {
            withContext(Dispatchers.IO) { runCatching { UsageCollector.refresh(context) } }
            version++
        }
    }
    val data = remember(refreshKey, version) { app.habits.data() }
    val facts = remember(refreshKey, version) { app.notes.all() }
    val today = LocalDate.now()
    val week = data.days.filter { it.date < today.toString() && (it.screenMinutes > 0 || it.wakeTime != null) }.takeLast(7)
    val wake = HabitProfile.typicalWakeMinutes(data, today)
    val bed = HabitProfile.typicalBedtimeMinutes(data, today)
    val sleeps = data.days.takeLast(14).mapNotNull { it.sleepMinutes }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GradientHeader(
            eyebrow = "Habits",
            title = if (wake == null) "Learning your rhythm" else "Your rhythm",
            subtitle = when {
                !app.settings.habitLearning -> "Habit learning is off in Setup"
                !hasAccess -> "Turn on Usage access so Sentinel can learn"
                week.isEmpty() -> "It takes a few nights to learn your routine"
                else -> "Based on the last ${week.size} days"
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RhythmStat(SentinelIcons.Sun, "Usually up", wake?.let(HabitProfile::formatMinutes) ?: "—", Modifier.weight(1f))
                RhythmStat(SentinelIcons.Moon, "Screen off", bed?.let(HabitProfile::formatMinutes) ?: "—", Modifier.weight(1f))
                RhythmStat(SentinelIcons.Bolt, "Per night", if (sleeps.isEmpty()) "—" else HabitProfile.hours(sleeps.average()), Modifier.weight(1f))
            }
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (app.settings.habitLearning && !hasAccess) {
                SentinelCard {
                    Text("Usage access needed", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Sentinel learns your sleep, wake-up time and phone habits from Android's usage history. " +
                            "Only daily totals are sent to Claude, never what you looked at.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    SoftButton("Turn on Usage access", actions::openUsageAccess)
                }
            }

            GradientButton("Coach me on my habits", onClick = { onAsk(COACH_REQUEST) }, icon = SentinelIcons.Sparkle)

            if (week.isNotEmpty()) {
                SectionTitle("Phone use")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Screen time", HabitProfile.hours(week.map { it.screenMinutes }.average()), "a day", Modifier.weight(1f))
                    StatTile("Unlocks", week.map { it.unlocks }.average().roundToInt().toString(), "a day", Modifier.weight(1f))
                    StatTile("Late night", "${week.map { it.lateNightMinutes }.average().roundToInt()}m", "12–5 a.m.", Modifier.weight(1f))
                }
                TopApps(week)
                SectionTitle("Day by day")
                SentinelCard(padding = 8.dp) {
                    week.reversed().forEachIndexed { i, d ->
                        if (i > 0) RowDivider()
                        DayRow(d)
                    }
                }
            }

            SectionTitle("Your goals")
            SentinelCard {
                GoalEditor(
                    data.goals,
                    onAdd = { app.habits.addGoal(it); version++ },
                    onRemove = { app.habits.removeGoal(it); version++ },
                )
            }

            SectionTitle("What Sentinel has noticed")
            if (data.insights.isEmpty()) {
                SentinelCard {
                    Text(
                        "Observations show up here after your first briefings, or when you ask to be coached.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            data.insights.reversed().forEach { InsightCard(it) }

            SectionTitle("What Sentinel knows about you")
            SentinelCard(padding = 8.dp) {
                if (facts.isEmpty()) {
                    Text(
                        "Nothing saved yet. Sentinel saves facts here from your chats and texts. Remove anything that's wrong or that you'd rather it didn't keep.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                facts.reversed().forEachIndexed { i, fact ->
                    if (i > 0) RowDivider()
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                        Text(fact, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { app.notes.removeExact(fact); version++ }) {
                            Icon(Icons.Filled.Close, "Forget this", tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp + LocalBottomBarSpace.current))
        }
    }
}

@Composable
private fun RhythmStat(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(SentinelColors.White.copy(alpha = 0.13f))
            .gloss(0.14f)
            .border(
                1.dp,
                Brush.verticalGradient(listOf(SentinelColors.White.copy(alpha = 0.4f), SentinelColors.White.copy(alpha = 0.06f))),
                RoundedCornerShape(20.dp),
            )
            .padding(14.dp),
    ) {
        Icon(icon, null, tint = SentinelColors.GreenLight, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(10.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = SentinelColors.White, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = SentinelColors.White.copy(alpha = 0.75f))
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    SentinelCard(modifier, padding = 14.dp) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TopApps(week: List<DaySummary>) {
    val apps = week.flatMap { it.topApps }.groupBy { it.app }
        .mapValues { (_, v) -> v.sumOf { it.minutes } / week.size }
        .entries.sortedByDescending { it.value }.take(5)
    if (apps.isEmpty()) return
    val max = apps.first().value.coerceAtLeast(1)
    SentinelCard {
        Text("Most-used apps", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        apps.forEachIndexed { i, (name, minutes) ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(HabitProfile.hours(minutes.toDouble()) + " / day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Box(
                    Modifier.fillMaxWidth((minutes.toFloat() / max).coerceIn(0.03f, 1f)).fillMaxHeight()
                        .clip(CircleShape).background(SentinelBrushes.Progress)
                )
            }
        }
    }
}

@Composable
private fun DayRow(d: DaySummary) {
    val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
    val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    val sleep = runCatching {
        if (d.bedtime != null && d.wakeTime != null) {
            "${LocalDateTime.parse(d.bedtime).format(timeFmt)} → ${LocalDateTime.parse(d.wakeTime).format(timeFmt)}"
        } else null
    }.getOrNull()
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(runCatching { LocalDate.parse(d.date).format(dayFmt) }.getOrDefault(d.date), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SentinelIcons.Moon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(sleep ?: "Sleep unknown", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(HabitProfile.hours(d.screenMinutes.toDouble()), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (d.lateNightMinutes > 0) {
                Text("${d.lateNightMinutes}m after midnight", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                Text("${d.unlocks} unlocks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GoalEditor(goals: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    goals.forEach { goal ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(SentinelBrushes.Go))
            Spacer(Modifier.width(12.dp))
            Text(goal, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = { onRemove(goal) }) { Icon(Icons.Filled.Close, "Remove goal", tint = MaterialTheme.colorScheme.outline) }
        }
    }
    if (goals.isNotEmpty()) Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SoftTextField(
            value = draft,
            onValueChange = { draft = it },
            label = "Add a goal",
            placeholder = "e.g. Phone down by midnight",
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(SentinelBrushes.Go),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = { if (draft.isNotBlank()) { onAdd(draft.trim()); draft = "" } }) {
                Icon(Icons.Filled.Add, "Add goal", tint = SentinelColors.White)
            }
        }
    }
}

@Composable
private fun InsightCard(insight: HabitInsight) {
    val scheme = MaterialTheme.colorScheme
    val (label, accent) = when (insight.kind) {
        "good_habit" -> "Going well" to scheme.secondary
        "bad_habit" -> "Work on this" to scheme.error
        "suggestion" -> "Try this" to scheme.primary
        else -> "Pattern" to scheme.onSurfaceVariant
    }
    SentinelCard(padding = 0.dp) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(16.dp)) {
                Text("$label · ${insight.date}", style = MaterialTheme.typography.labelMedium, color = accent)
                Spacer(Modifier.height(4.dp))
                Text(insight.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

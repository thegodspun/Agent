package io.github.thegodspun.sentinel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.thegodspun.sentinel.MainActivity
import io.github.thegodspun.sentinel.app
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
    val today = LocalDate.now()
    val week = data.days.filter { it.date < today.toString() }.takeLast(7)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!app.settings.habitLearning) {
            InfoCard("Habit learning is off. Turn it on in Setup to let Sentinel learn your routine.")
        } else if (!hasAccess) {
            InfoCard(
                "To learn your sleep, wake-up time and phone habits, Sentinel needs Usage access. " +
                    "Only daily totals are sent to Claude, never what you looked at."
            )
            Button(onClick = actions::openUsageAccess, modifier = Modifier.fillMaxWidth()) { Text("Turn on Usage access") }
        }

        Button(
            onClick = { onAsk(COACH_REQUEST) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ),
        ) { Text("Coach me on my habits") }

        Heading("Your rhythm")
        val wake = HabitProfile.typicalWakeMinutes(data, today)
        val bed = HabitProfile.typicalBedtimeMinutes(data, today)
        val sleeps = week.mapNotNull { it.sleepMinutes }
        if (wake == null && bed == null) {
            Text("Still learning. It takes a few nights.", style = MaterialTheme.typography.bodyLarge)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Stat("Usually up", wake?.let(HabitProfile::formatMinutes) ?: "—", Modifier.weight(1f))
                Stat("Screen off", bed?.let(HabitProfile::formatMinutes) ?: "—", Modifier.weight(1f))
                Stat("Night", if (sleeps.isEmpty()) "—" else HabitProfile.hours(sleeps.average()), Modifier.weight(1f))
            }
        }

        if (week.isNotEmpty()) {
            Heading("Phone use, daily average (last ${week.size} days)")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Stat("Screen time", HabitProfile.hours(week.map { it.screenMinutes }.average()), Modifier.weight(1f))
                Stat("Unlocks", week.map { it.unlocks }.average().roundToInt().toString(), Modifier.weight(1f))
                Stat("12–5 a.m.", "${week.map { it.lateNightMinutes }.average().roundToInt()} min", Modifier.weight(1f))
            }
            val apps = week.flatMap { it.topApps }.groupBy { it.app }
                .mapValues { (_, v) -> v.sumOf { it.minutes } / week.size }
                .entries.sortedByDescending { it.value }.take(5)
            if (apps.isNotEmpty()) {
                Text("Top apps", fontWeight = FontWeight.SemiBold)
                val max = apps.first().value.coerceAtLeast(1)
                apps.forEach { (name, minutes) -> UsageBar(name, minutes, minutes.toFloat() / max) }
            }

            Heading("Day by day")
            val dayFmt = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
            val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
            week.reversed().forEach { d ->
                val sleep = if (d.bedtime != null && d.wakeTime != null) {
                    "${LocalDateTime.parse(d.bedtime).format(timeFmt)} → ${LocalDateTime.parse(d.wakeTime).format(timeFmt)}"
                } else "sleep unknown"
                Column {
                    Text(LocalDate.parse(d.date).format(dayFmt), fontWeight = FontWeight.SemiBold)
                    Text(
                        "$sleep · ${HabitProfile.hours(d.screenMinutes.toDouble())} screen · ${d.unlocks} unlocks" +
                            if (d.lateNightMinutes > 0) " · ${d.lateNightMinutes} min after midnight" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Heading("Your goals")
        GoalEditor(data.goals, onAdd = { app.habits.addGoal(it); version++ }, onRemove = { app.habits.removeGoal(it); version++ })

        Heading("What Sentinel has noticed")
        if (data.insights.isEmpty()) {
            Text(
                "Nothing yet. Observations appear here after your first briefings or when you ask to be coached.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        data.insights.reversed().forEach { InsightRow(it) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun InfoCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(text, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UsageBar(app: String, minutes: Int, fraction: Float) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(app, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(HabitProfile.hours(minutes.toDouble()), style = MaterialTheme.typography.bodyMedium)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

@Composable
private fun GoalEditor(goals: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    goals.forEach { goal ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("• $goal", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = { onRemove(goal) }) { Icon(Icons.Filled.Delete, "Remove goal") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
            placeholder = { Text("e.g. Phone down by midnight") }, singleLine = true,
        )
        Button(onClick = { if (draft.isNotBlank()) { onAdd(draft); draft = "" } }) { Text("Add") }
    }
}

@Composable
private fun InsightRow(insight: HabitInsight) {
    val (label, color) = when (insight.kind) {
        "good_habit" -> "Going well" to MaterialTheme.colorScheme.secondary
        "bad_habit" -> "Work on this" to MaterialTheme.colorScheme.error
        "suggestion" -> "Try this" to MaterialTheme.colorScheme.primary
        else -> "Pattern" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(10.dp).background(color, CircleShape))
        Spacer(Modifier.size(10.dp))
        Column {
            Text("$label · ${insight.date}", style = MaterialTheme.typography.labelMedium, color = color)
            Text(insight.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

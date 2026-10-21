package io.github.thegodspun.sentinel.tools

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import io.github.thegodspun.sentinel.core.AgentTool
import io.github.thegodspun.sentinel.core.ToolInput
import io.github.thegodspun.sentinel.core.boolProp
import io.github.thegodspun.sentinel.core.intProp
import io.github.thegodspun.sentinel.core.stringProp
import io.github.thegodspun.sentinel.service.PhoneControlService
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Tools that control the phone. Only given to conversations the user is watching. */
fun phoneTools(context: Context): List<AgentTool> {
    val ctx = context.applicationContext

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    fun launch(intent: Intent) = ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun control(): PhoneControlService = PhoneControlService.instance
        ?: throw IllegalStateException(
            "Screen control is off. Ask the user to turn on Sentinel in Settings > Accessibility " +
                "(the Setup tab has a button for it)."
        )

    /** Looks up a phone number for a contact name; passes numbers through unchanged. */
    fun resolveNumber(who: String): Pair<String, String> {
        if (who.any { it.isDigit() } && who.none { it.isLetter() }) return who to who
        check(granted(Manifest.permission.READ_CONTACTS)) { "Contacts permission is not granted (Setup tab)." }
        val matches = findContacts(ctx, who)
        check(matches.isNotEmpty()) { "No contact matches \"$who\"." }
        return matches.first()
    }

    return listOf(
        object : AgentTool("open_app", "Open an installed app by its name, e.g. \"Gmail\" or \"MyChart\".") {
            override val properties = mapOf("name" to stringProp("App name as shown on the home screen"))
            override val required = listOf("name")
            override suspend fun run(input: ToolInput): String {
                val wanted = input.string("name")
                val pm = ctx.packageManager
                val apps = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                val match = apps.firstOrNull { it.first.equals(wanted, ignoreCase = true) }
                    ?: apps.firstOrNull { it.first.contains(wanted, ignoreCase = true) }
                    ?: return "No installed app called \"$wanted\". Some installed apps: " +
                        apps.map { it.first }.sorted().take(60).joinToString()
                launch(pm.getLaunchIntentForPackage(match.second) ?: return "Can't open ${match.first}.")
                delay(1500) // let it draw before the agent reads the screen
                return "Opened ${match.first}."
            }
        },

        object : AgentTool("find_contact", "Look up phone numbers in the user's contacts by name.") {
            override val properties = mapOf("name" to stringProp("Full or partial name"))
            override val required = listOf("name")
            override suspend fun run(input: ToolInput): String {
                check(granted(Manifest.permission.READ_CONTACTS)) { "Contacts permission is not granted (Setup tab)." }
                val found = findContacts(ctx, input.string("name"))
                return if (found.isEmpty()) "No matches." else found.joinToString("\n") { "${it.second}: ${it.first}" }
            }
        },

        object : AgentTool("call", "Place a phone call to a contact name or number.") {
            override val properties = mapOf("to" to stringProp("Contact name or phone number"))
            override val required = listOf("to")
            override val requiresConfirmation = true
            override fun confirmationText(input: ToolInput) = "Call ${input.stringOrNull("to")}?"
            override suspend fun run(input: ToolInput): String {
                val (number, name) = resolveNumber(input.string("to"))
                val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
                launch(Intent(action, Uri.parse("tel:" + Uri.encode(number))))
                return if (action == Intent.ACTION_CALL) "Calling $name ($number)." else "Opened the dialer for $name ($number)."
            }
        },

        object : AgentTool("send_text", "Send an SMS text message to a contact name or number.") {
            override val properties = mapOf(
                "to" to stringProp("Contact name or phone number"),
                "message" to stringProp("The message text"),
            )
            override val required = listOf("to", "message")
            override val requiresConfirmation = true
            override fun confirmationText(input: ToolInput) =
                "Text ${input.stringOrNull("to")}:\n\n\"${input.stringOrNull("message")}\""
            override suspend fun run(input: ToolInput): String {
                val (number, name) = resolveNumber(input.string("to"))
                val message = input.string("message")
                if (!granted(Manifest.permission.SEND_SMS)) {
                    launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", message))
                    return "Opened Messages with the text ready for $name; the user must press send."
                }
                val sms = ctx.getSystemService(SmsManager::class.java)
                sms.sendMultipartTextMessage(number, null, sms.divideMessage(message), null, null)
                return "Text sent to $name ($number)."
            }
        },

        object : AgentTool("set_alarm", "Set an alarm in the Clock app.") {
            override val properties = mapOf(
                "hour" to intProp("Hour, 0-23"),
                "minute" to intProp("Minute, 0-59"),
                "label" to stringProp("What the alarm is for"),
            )
            override val required = listOf("hour", "minute")
            override suspend fun run(input: ToolInput): String {
                launch(
                    Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, input.intOrNull("hour") ?: error("hour required"))
                        .putExtra(AlarmClock.EXTRA_MINUTES, input.intOrNull("minute") ?: 0)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, input.stringOrNull("label") ?: "Sentinel")
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                )
                return "Alarm set."
            }
        },

        object : AgentTool("set_timer", "Start a countdown timer, e.g. for pressure relief or medication.") {
            override val properties = mapOf(
                "seconds" to intProp("Length in seconds"),
                "label" to stringProp("What the timer is for"),
            )
            override val required = listOf("seconds")
            override suspend fun run(input: ToolInput): String {
                launch(
                    Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, input.intOrNull("seconds") ?: error("seconds required"))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, input.stringOrNull("label") ?: "Sentinel")
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                )
                return "Timer started."
            }
        },

        object : AgentTool("calendar_upcoming", "List upcoming events from the phone's calendars.") {
            override val properties = mapOf("days" to intProp("How many days ahead to look. Default 7."))
            override suspend fun run(input: ToolInput): String {
                check(granted(Manifest.permission.READ_CALENDAR)) { "Calendar permission is not granted (Setup tab)." }
                val days = (input.intOrNull("days") ?: 7).coerceIn(1, 90)
                val start = System.currentTimeMillis()
                val events = readCalendar(ctx, start, start + days * 86_400_000L)
                return if (events.isEmpty()) "No events in the next $days days." else events.joinToString("\n")
            }
        },

        object : AgentTool(
            "calendar_add_event",
            "Open the calendar with a new event filled in; the user reviews and saves it.",
        ) {
            override val properties = mapOf(
                "title" to stringProp("Event title"),
                "start" to stringProp("Start as 2026-11-03T14:30"),
                "end" to stringProp("End as 2026-11-03T15:30. Default one hour after start."),
                "location" to stringProp("Address or place"),
                "description" to stringProp("Notes: what to bring, phone numbers"),
            )
            override val required = listOf("title", "start")
            override suspend fun run(input: ToolInput): String {
                val start = LocalDateTime.parse(input.string("start"))
                val end = input.stringOrNull("end")?.let(LocalDateTime::parse) ?: start.plusHours(1)
                val zone = ZoneId.systemDefault()
                launch(
                    Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, input.string("title"))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.atZone(zone).toInstant().toEpochMilli())
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.atZone(zone).toInstant().toEpochMilli())
                        .putExtra(CalendarContract.Events.EVENT_LOCATION, input.stringOrNull("location"))
                        .putExtra(CalendarContract.Events.DESCRIPTION, input.stringOrNull("description"))
                )
                return "Opened the calendar with the event filled in; the user needs to tap Save."
            }
        },

        object : AgentTool("open_maps", "Show a place or get directions in Google Maps.") {
            override val properties = mapOf(
                "query" to stringProp("Address or place name"),
                "navigate" to boolProp("Start turn-by-turn directions instead of just showing it"),
            )
            override val required = listOf("query")
            override suspend fun run(input: ToolInput): String {
                val q = Uri.encode(input.string("query"))
                val uri = if (input.boolOrNull("navigate") == true) "google.navigation:q=$q" else "geo:0,0?q=$q"
                launch(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                return "Opened Maps."
            }
        },

        object : AgentTool("open_url", "Open a web page in the browser.") {
            override val properties = mapOf("url" to stringProp("https:// address"))
            override val required = listOf("url")
            override suspend fun run(input: ToolInput): String {
                val url = input.string("url")
                require(url.startsWith("https://") || url.startsWith("http://")) { "Only web addresses can be opened." }
                launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return "Opened $url."
            }
        },

        object : AgentTool("flashlight", "Turn the flashlight on or off.") {
            override val properties = mapOf("on" to boolProp("true for on, false for off"))
            override val required = listOf("on")
            override suspend fun run(input: ToolInput): String {
                val cameras = ctx.getSystemService(CameraManager::class.java)
                val id = cameras.cameraIdList.firstOrNull() ?: return "No flashlight found."
                val on = input.boolOrNull("on") ?: true
                cameras.setTorchMode(id, on)
                return if (on) "Flashlight on." else "Flashlight off."
            }
        },

        object : AgentTool(
            "phone_button",
            "Press a system button: back, home, recents, notifications, quick_settings, power_dialog, " +
                "lock_screen or screenshot. Needs screen control turned on.",
        ) {
            override val properties = mapOf(
                "button" to stringProp(
                    "Which button",
                    listOf("back", "home", "recents", "notifications", "quick_settings", "power_dialog", "lock_screen", "screenshot"),
                )
            )
            override val required = listOf("button")
            override suspend fun run(input: ToolInput): String {
                val ok = control().globalAction(input.string("button"))
                delay(800)
                return if (ok) "Pressed ${input.string("button")}." else "That didn't work."
            }
        },

        object : AgentTool(
            "read_screen",
            "Read the text and buttons currently on screen. Use it before tapping and after, to check results.",
        ) {
            override val properties = emptyMap<String, Map<String, Any>>()
            override suspend fun run(input: ToolInput) = control().readScreen()
        },

        object : AgentTool("tap", "Tap the on-screen button or item with this label (from read_screen).") {
            override val properties = mapOf("label" to stringProp("Exact text or description of the element"))
            override val required = listOf("label")
            override val requiresConfirmation = true
            override fun confirmationText(input: ToolInput) = "Tap \"${input.stringOrNull("label")}\" on screen?"
            override suspend fun run(input: ToolInput): String {
                val ok = control().tap(input.string("label"))
                delay(1200)
                return if (ok) "Tapped. Call read_screen to see the result." else "Couldn't find anything tappable labeled that."
            }
        },

        object : AgentTool("type_text", "Type text into a text field on screen.") {
            override val properties = mapOf(
                "text" to stringProp("Text to enter (replaces what is in the field)"),
                "field" to stringProp("Label or hint of the field; omit for the focused field"),
            )
            override val required = listOf("text")
            override suspend fun run(input: ToolInput): String =
                if (control().type(input.string("text"), input.stringOrNull("field"))) "Typed." else "No text field found."
        },

        object : AgentTool("scroll", "Scroll the current screen.") {
            override val properties = mapOf("direction" to stringProp("Direction", listOf("down", "up")))
            override val required = listOf("direction")
            override suspend fun run(input: ToolInput): String {
                val ok = control().scroll(forward = input.string("direction") == "down")
                delay(700)
                return if (ok) "Scrolled." else "Nothing to scroll."
            }
        },
    )
}

/**
 * Calendar events overlapping [from]..[to] as "Tue Nov 3, 2:30 PM: Title @ Place".
 * Returns an empty list without calendar permission.
 */
fun readCalendar(context: Context, from: Long, to: Long, max: Int = 50): List<String> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
        return emptyList()
    }
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, from)
        ContentUris.appendId(it, to)
    }.build()
    val fmt = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)
    val events = mutableListOf<String>()
    context.contentResolver.query(
        uri,
        arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY,
        ),
        null, null, "${CalendarContract.Instances.BEGIN} ASC",
    )?.use { c ->
        while (c.moveToNext() && events.size < max) {
            val begin = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(1)), ZoneId.systemDefault())
            val whenText = if (c.getInt(3) == 1) begin.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)) + " (all day)" else begin.format(fmt)
            val location = c.getString(2)?.takeIf { it.isNotBlank() }?.let { " @ $it" }.orEmpty()
            events += "$whenText: ${c.getString(0)}$location"
        }
    }
    return events
}

/** Returns (number, display name) pairs for contacts whose name contains [name]. */
private fun findContacts(context: Context, name: String): List<Pair<String, String>> {
    val results = mutableListOf<Pair<String, String>>()
    context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
        arrayOf("%$name%"),
        null,
    )?.use { c ->
        while (c.moveToNext() && results.size < 10) results += c.getString(0) to c.getString(1)
    }
    return results.distinct()
}

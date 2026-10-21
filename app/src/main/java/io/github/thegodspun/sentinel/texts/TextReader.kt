package io.github.thegodspun.sentinel.texts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import io.github.thegodspun.sentinel.core.TextFilter
import io.github.thegodspun.sentinel.core.TextMessage
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reads the phone's SMS/MMS history. RCS chats in Google Messages are not stored where other
 * apps can read them, so those are only seen as they arrive, through notifications.
 */
object TextReader {
    fun hasAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** SMS sent or received after [sinceMillis], oldest first, with verification codes removed. */
    fun since(context: Context, sinceMillis: Long, limit: Int = 500): List<TextMessage> =
        query(context, "${Telephony.Sms.DATE} > ?", arrayOf(sinceMillis.toString()), limit).asReversed()

    /** Newest-first search by words in the message and/or the person's name or number. */
    fun search(context: Context, text: String?, person: String?, days: Int, limit: Int = 40): List<TextMessage> {
        val since = System.currentTimeMillis() - days * 86_400_000L
        val clauses = mutableListOf("${Telephony.Sms.DATE} > ?")
        val args = mutableListOf(since.toString())
        if (!text.isNullOrBlank()) {
            clauses += "${Telephony.Sms.BODY} LIKE ?"
            args += "%$text%"
        }
        // Names aren't stored with messages, so match the person afterwards.
        val rows = query(context, clauses.joinToString(" AND "), args.toTypedArray(), if (person.isNullOrBlank()) limit else 2_000)
        return if (person.isNullOrBlank()) rows
        else rows.filter { it.person.contains(person, ignoreCase = true) }.take(limit)
    }

    private fun query(context: Context, selection: String, args: Array<String>, limit: Int): List<TextMessage> {
        if (!hasAccess(context)) return emptyList()
        val names = mutableMapOf<String, String>()
        val out = mutableListOf<TextMessage>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.DATE, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.TYPE),
            selection, args, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val body = c.getString(2).orEmpty()
                if (body.isBlank() || TextFilter.isSecretCode(body)) continue
                val address = c.getString(1).orEmpty()
                out += TextMessage(
                    time = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(0)), ZoneId.systemDefault())
                        .withSecond(0).withNano(0).toString(),
                    person = names.getOrPut(address) { contactName(context, address) ?: address },
                    outgoing = c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT,
                    body = body,
                    source = "SMS",
                )
            }
        }
        return out
    }

    private fun contactName(context: Context, number: String): String? {
        if (number.isBlank() ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }
}

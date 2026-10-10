package com.vibethroughcode.ftree.phonebook

import com.vibethroughcode.ftree.data.RecordedDate

/** One person from the phone's contacts, reduced to what Kutumb keeps: a name and, if there is one, a birthday. */
data class PhoneContact(
    val id: Long,
    val name: String,
    /** Already in the form the tree stores (`1990-04-17`, or `--04-17` with no year), or null. */
    val birthday: String?,
)

/** One row of the contacts provider's data table, reduced to what is read from it. */
data class DataRow(
    val contactId: Long,
    val displayName: String?,
    val mimeType: String,
    /** The event's date, for an event row. */
    val value: String?,
    /** The event's kind, for an event row. */
    val eventType: Int?,
)

/**
 * Turning what the contacts provider hands back into people, with no Android in it so it can be tested.
 *
 * Only a name and a birthday are read. Phone numbers, addresses, emails, photographs and notes are never
 * asked for, so there is nothing of them to copy into a family tree.
 */
object Phonebook {
    const val MIME_EVENT = "vnd.android.cursor.item/contact_event"
    const val MIME_NAME = "vnd.android.cursor.item/name"

    /** `ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY`. */
    const val TYPE_BIRTHDAY = 3

    private const val MAX_NAME = 100

    /** Contacts that are people with a name, one per contact, in name order. */
    fun contactsFrom(rows: List<DataRow>): List<PhoneContact> =
        rows.groupBy { it.contactId }
            .mapNotNull { (id, own) ->
                val name = own.firstNotNullOfOrNull { it.displayName?.trim()?.takeIf { n -> n.isNotEmpty() } }
                    ?: return@mapNotNull null
                if (!looksLikeAPerson(name)) return@mapNotNull null
                val birthday = own.firstNotNullOfOrNull { row ->
                    if (row.mimeType == MIME_EVENT && row.eventType == TYPE_BIRTHDAY) normalizeBirthday(row.value) else null
                }
                PhoneContact(id, name, birthday)
            }
            .sortedWith(compareBy<PhoneContact> { it.name.lowercase() }.thenBy { it.id })

    /**
     * A name worth offering. A contact saved as a bare number, or one with no letter in it, is a phone
     * number rather than a person, and an enormous one is not a name.
     */
    fun looksLikeAPerson(name: String): Boolean =
        name.length <= MAX_NAME && name.any { it.isLetter() }

    /**
     * A birthday as the tree stores it, or null if it is not a real one.
     *
     * Contacts hold these as `1990-04-17`, `--04-17` (no year), sometimes with a time after the date, and some
     * apps write a year of `0000` for "no year", which is the same thing and is read that way.
     */
    fun normalizeBirthday(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val dated = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ].*)?$""").matchEntire(text)
        val candidate = when {
            dated != null -> {
                val (year, month, day) = dated.destructured
                if (year == "0000") "--$month-$day" else "$year-$month-$day"
            }
            else -> text
        }
        return RecordedDate.parse(candidate)?.serialize()
    }

    /** What two spellings of one name have in common, for telling that somebody is already in the tree. */
    fun nameKey(name: String?): String =
        name.orEmpty().trim().lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
}

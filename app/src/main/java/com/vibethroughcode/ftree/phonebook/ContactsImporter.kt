package com.vibethroughcode.ftree.phonebook

import android.content.ContentResolver
import android.provider.ContactsContract.Data
import com.vibethroughcode.ftree.data.FamilyRepository
import com.vibethroughcode.ftree.data.Person
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where contacts come from. The phone's own address book in the app; a list in tests. */
interface PhonebookSource {
    suspend fun contacts(): List<PhoneContact>
}

/**
 * Reads names and birthdays, and nothing else, from the phone's contacts. Needs the contacts permission, which
 * the screen asks for only when the reader chooses to import; without it the provider refuses and this fails.
 */
class AndroidPhonebook(private val resolver: ContentResolver) : PhonebookSource {
    override suspend fun contacts(): List<PhoneContact> = withContext(Dispatchers.IO) {
        val rows = ArrayList<DataRow>()
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID, Data.DISPLAY_NAME_PRIMARY, Data.MIMETYPE, Data.DATA1, Data.DATA2),
            "${Data.MIMETYPE} IN (?, ?)",
            arrayOf(Phonebook.MIME_NAME, Phonebook.MIME_EVENT),
            null,
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(Data.CONTACT_ID)
            val name = cursor.getColumnIndexOrThrow(Data.DISPLAY_NAME_PRIMARY)
            val mime = cursor.getColumnIndexOrThrow(Data.MIMETYPE)
            val value = cursor.getColumnIndexOrThrow(Data.DATA1)
            val type = cursor.getColumnIndexOrThrow(Data.DATA2)
            while (cursor.moveToNext()) {
                val mimeType = cursor.getString(mime) ?: continue
                val isEvent = mimeType == Phonebook.MIME_EVENT
                rows += DataRow(
                    contactId = cursor.getLong(id),
                    displayName = cursor.getString(name),
                    mimeType = mimeType,
                    value = if (isEvent) cursor.getString(value) else null,
                    eventType = if (isEvent && !cursor.isNull(type)) cursor.getInt(type) else null,
                )
            }
        }
        Phonebook.contactsFrom(rows)
    }
}

/** What an import did. */
data class ContactsImportResult(val added: Int, val connected: Int, val notConnected: Int, val alreadyThere: Int)

/** Writes a [ContactsImportPlan] into the tree. */
class ContactsImporter(private val family: FamilyRepository) {
    /**
     * Adds the reader first (if the plan has one) and then everyone else. Somebody the tree's relationship rules
     * refuse to connect, which can happen when somebody is added twice as a mother, is still added, without the
     * connection, rather than lost; they are counted in [ContactsImportResult.notConnected].
     */
    suspend fun apply(plan: ContactsImportPlan): ContactsImportResult {
        val me = plan.me?.let { family.addPerson(Person(name = it)) }
        var connected = 0
        var notConnected = 0
        for (planned in plan.people) {
            val person = Person(name = planned.name, gender = planned.relation.gender, birthDate = planned.birthDate)
            val kind = planned.relation.kind
            if (me != null && kind != null) {
                if (family.addNewRelative(me.id, person, kind).isSuccess) {
                    connected++
                } else {
                    family.addPerson(person)
                    notConnected++
                }
            } else {
                family.addPerson(person)
                notConnected++
            }
        }
        return ContactsImportResult(
            added = plan.people.size + if (me != null) 1 else 0,
            connected = connected,
            notConnected = notConnected,
            alreadyThere = plan.alreadyThere.size,
        )
    }
}

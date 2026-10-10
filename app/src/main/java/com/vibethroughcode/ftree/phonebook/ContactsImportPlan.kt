package com.vibethroughcode.ftree.phonebook

import com.vibethroughcode.ftree.data.Gender
import com.vibethroughcode.ftree.data.RelativeKind

/**
 * How a chosen contact is related to the reader, in the words a person uses. [kind] is how the tree records it
 * and [gender] what the word says about them; "added without a connection" has neither.
 */
enum class ContactRelation(val kind: RelativeKind?, val gender: Gender) {
    MOTHER(RelativeKind.PARENT, Gender.FEMALE),
    FATHER(RelativeKind.PARENT, Gender.MALE),
    SISTER(RelativeKind.SIBLING, Gender.FEMALE),
    BROTHER(RelativeKind.SIBLING, Gender.MALE),
    SPOUSE(RelativeKind.SPOUSE, Gender.UNSPECIFIED),
    DAUGHTER(RelativeKind.CHILD, Gender.FEMALE),
    SON(RelativeKind.CHILD, Gender.MALE),
    NOT_CONNECTED(null, Gender.UNSPECIFIED),
}

/** One person to add, and how. */
data class PlannedPerson(
    val contactId: Long,
    val name: String,
    val birthDate: String?,
    val relation: ContactRelation,
)

/**
 * What an import will do: who the reader is (if the tree is empty and so needs them), who is added, and who was
 * left out because the tree already has somebody of that name.
 */
data class ContactsImportPlan(
    val me: String?,
    val people: List<PlannedPerson>,
    val alreadyThere: List<PhoneContact>,
)

/** Turns what was ticked into a plan, with no Android in it. */
object ContactsImport {
    /**
     * [me] is the reader's own name when the tree is empty, which makes the reader the person everyone else is
     * related to; without it nobody is anyone's mother or son, so everybody is added without a connection.
     * Somebody whose name matches [existingNames] (see [Phonebook.nameKey]), or the reader's own, is left out
     * rather than added twice. Parents are put first, so that a sister added in the same go is attached to them.
     */
    fun plan(
        me: String?,
        picks: Map<Long, ContactRelation>,
        contacts: List<PhoneContact>,
        existingNames: Set<String>,
    ): ContactsImportPlan {
        val myName = me?.trim()?.takeIf { it.isNotEmpty() }
        val taken = existingNames + listOfNotNull(myName?.let(Phonebook::nameKey))
        val people = ArrayList<PlannedPerson>()
        val already = ArrayList<PhoneContact>()
        val seen = HashSet<String>()
        for (contact in contacts) {
            val relation = picks[contact.id] ?: continue
            val key = Phonebook.nameKey(contact.name)
            if (key in taken) { already += contact; continue }
            // The same name picked twice from two entries is one person.
            if (!seen.add(key)) continue
            people += PlannedPerson(
                contactId = contact.id,
                name = contact.name,
                birthDate = contact.birthday,
                relation = if (myName == null) ContactRelation.NOT_CONNECTED else relation,
            )
        }
        val order = listOf(RelativeKind.PARENT, RelativeKind.SPOUSE, RelativeKind.SIBLING, RelativeKind.CHILD)
        val sorted = people.sortedBy { p -> p.relation.kind?.let { order.indexOf(it) } ?: order.size }
        return ContactsImportPlan(myName, sorted, already)
    }
}

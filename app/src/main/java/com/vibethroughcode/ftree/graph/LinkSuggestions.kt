package com.vibethroughcode.ftree.graph

import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.RecordedDate
import com.vibethroughcode.ftree.data.RelativeKind
import com.vibethroughcode.ftree.transfer.nameKey

/**
 * Who the person being added is probably somebody already in the tree.
 *
 * Adding a relative from one page makes a new person, so adding "my mother" from my own page and then "my
 * father's wife" from his leaves two entries for one woman. The tree itself can see it coming: the woman who is
 * already the mother of my father's child is the wife he is about to be given. This reads the shape of the tree,
 * and the name typed, and offers those people first. Pure, so every rule is tested on the JVM.
 *
 * Only ever a suggestion. Nothing here links anybody; the reader taps one, or goes on and adds a new person.
 */
object LinkSuggestions {
    enum class Reason {
        /** Already a parent of one of this person's children. */
        SHARES_CHILD,

        /** Already the partner of one of this person's parents. */
        PARTNER_OF_PARENT,

        /** Already a child of one of this person's partners. */
        CHILD_OF_PARTNER,

        /** Has the same name as the one being typed. */
        SAME_NAME,
    }

    data class Suggestion(val person: Person, val reason: Reason, val via: Person? = null)

    /**
     * @param excluded people the new link could never reach (the person themselves, anyone who would close a loop)
     */
    fun forNewRelative(
        kind: RelativeKind,
        anchorId: String,
        typedName: String?,
        tree: FamilySnapshot,
        excluded: Set<String> = setOf(anchorId),
    ): List<Suggestion> {
        val people = tree.people
        fun parents(id: String) = tree.parentsOf[id].orEmpty()
        fun children(id: String) = tree.childrenOf[id].orEmpty()
        fun spouses(id: String) = tree.spousesOf[id].orEmpty()

        val alreadyThatKind: Set<String> = when (kind) {
            RelativeKind.PARENT -> parents(anchorId).toSet()
            RelativeKind.CHILD -> children(anchorId).toSet()
            RelativeKind.SPOUSE -> spouses(anchorId).toSet()
            RelativeKind.SIBLING -> tree.siblingsOf[anchorId].orEmpty().toSet()
        }
        val skip = excluded + anchorId + alreadyThatKind

        val found = LinkedHashMap<String, Suggestion>()
        fun offer(id: String, reason: Reason, via: String?) {
            if (id in skip || id in found) return
            val person = people[id] ?: return
            found[id] = Suggestion(person, reason, via?.let { people[it] })
        }

        when (kind) {
            RelativeKind.SPOUSE ->
                children(anchorId).forEach { child -> parents(child).forEach { offer(it, Reason.SHARES_CHILD, child) } }

            RelativeKind.PARENT ->
                // A second parent is the partner of the first; a third is never the answer.
                if (parents(anchorId).size < 2) {
                    parents(anchorId).forEach { parent -> spouses(parent).forEach { offer(it, Reason.PARTNER_OF_PARENT, parent) } }
                }

            RelativeKind.CHILD ->
                spouses(anchorId).forEach { spouse -> children(spouse).forEach { offer(it, Reason.CHILD_OF_PARTNER, spouse) } }

            RelativeKind.SIBLING -> Unit
        }

        val key = nameKey(typedName)
        if (key != null) {
            people.values.filter { nameKey(it.name) == key }.forEach { offer(it.id, Reason.SAME_NAME, null) }
        }
        return found.values.toList()
    }

    /**
     * Other people in the tree who are very likely the same person as [person]: the same name once spelling is
     * set aside, and no birth or death date that says otherwise. For offering to merge two entries that were made
     * by accident; the reader decides.
     */
    fun likelySamePerson(person: Person, everyone: Collection<Person>): List<Person> {
        val key = nameKey(person.name) ?: return emptyList()
        return everyone.filter { other ->
            other.id != person.id &&
                nameKey(other.name) == key &&
                datesAgree(person.birthDate, other.birthDate) &&
                datesAgree(person.deathDate, other.deathDate)
        }
    }

    private fun datesAgree(a: String?, b: String?): Boolean {
        val x = RecordedDate.parse(a) ?: return true
        val y = RecordedDate.parse(b) ?: return true
        return x.isCompatibleWith(y)
    }
}

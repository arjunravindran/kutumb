package com.vibethroughcode.ftree.phonebook

import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.RecordedDate
import com.vibethroughcode.ftree.transfer.nameKey

/**
 * Who in the tree a phone contact might be, when the names are close but not the same.
 *
 * An exact name is settled elsewhere (the contact is left out). This finds the quieter cases: "Asha K" for
 * "Asha Kumar", "Priyanka Sharmaa" for "Priyanka Sharma", an accent or a hyphen. It never decides anything; the
 * picker shows "might be Asha Kumar" and the reader ticks the contact or not.
 *
 * What it cannot do: a contact saved as "Mom" shares no letters with "Asha", so no name rule can connect them.
 * Only the reader knows that, and the "same person as another entry" action on a person's page is for it.
 */
class ContactMatcher(people: Collection<Person>) {
    private class Entry(val person: Person, val exactKey: String, val tokens: List<String>)

    private val entries: List<Entry> = people.mapNotNull { person ->
        val key = nameKey(person.name) ?: return@mapNotNull null
        Entry(person, Phonebook.nameKey(person.name), key.split(' '))
    }

    /** The closest person already in the tree whose name is nearly the contact's, or null. */
    fun possibleMatch(contact: PhoneContact): Person? {
        val tokens = nameKey(contact.name)?.split(' ') ?: return null
        val exact = Phonebook.nameKey(contact.name)
        var best: Person? = null
        var bestScore = Int.MAX_VALUE
        for (entry in entries) {
            if (entry.exactKey == exact) continue
            val score = score(tokens, entry.tokens) ?: continue
            if (score >= bestScore) continue
            if (!datesAgree(contact.birthday, entry.person.birthDate)) continue
            best = entry.person
            bestScore = score
        }
        return best
    }

    /** Lower is closer; null when the names are not alike. Every word of the shorter name must find a partner. */
    private fun score(a: List<String>, b: List<String>): Int? {
        val (short, long) = if (a.size <= b.size) a to b else b to a
        val used = BooleanArray(long.size)
        var distance = 0
        var solid = false
        for (word in short) {
            var pick = -1
            var pickDistance = Int.MAX_VALUE
            for (i in long.indices) {
                if (used[i]) continue
                val d = wordDistance(word, long[i]) ?: continue
                if (d < pickDistance) { pick = i; pickDistance = d }
            }
            if (pick < 0) return null
            used[pick] = true
            distance += pickDistance
            if (word.length >= 3) solid = true
        }
        // "A" or "K" alone is not a name to go on.
        if (!solid) return null
        return (long.size - short.size) * 2 + distance
    }

    /** 0 same, 1 an initial or one slip of the pen in a longer word, null not alike. */
    private fun wordDistance(x: String, y: String): Int? = when {
        x == y -> 0
        x.length == 1 && y.startsWith(x) -> 1
        y.length == 1 && x.startsWith(y) -> 1
        minOf(x.length, y.length) >= 5 && withinOneEdit(x, y) -> 1
        else -> null
    }

    private fun withinOneEdit(x: String, y: String): Boolean {
        if (kotlin.math.abs(x.length - y.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < x.length && j < y.length) {
            if (x[i] == y[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                x.length > y.length -> i++
                x.length < y.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (x.length - i) + (y.length - j) <= 1
    }

    private fun datesAgree(a: String?, b: String?): Boolean {
        val x = RecordedDate.parse(a) ?: return true
        val y = RecordedDate.parse(b) ?: return true
        return x.isCompatibleWith(y)
    }
}

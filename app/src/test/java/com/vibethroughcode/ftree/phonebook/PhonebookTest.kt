package com.vibethroughcode.ftree.phonebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonebookTest {
    private fun name(id: Long, display: String?) = DataRow(id, display, Phonebook.MIME_NAME, null, null)
    private fun birthday(id: Long, display: String?, value: String?, type: Int = Phonebook.TYPE_BIRTHDAY) =
        DataRow(id, display, Phonebook.MIME_EVENT, value, type)

    // --- birthdays -------------------------------------------------------------------------------------

    @Test fun aFullDateAndAYearlessOneAreKeptAsTheTreeStoresThem() {
        assertEquals("1990-04-17", Phonebook.normalizeBirthday("1990-04-17"))
        assertEquals("--04-17", Phonebook.normalizeBirthday("--04-17"))
    }

    @Test fun aYearOfZeroMeansNoYear() {
        assertEquals("--04-17", Phonebook.normalizeBirthday("0000-04-17"))
    }

    @Test fun aTimeAfterTheDateIsDropped() {
        assertEquals("1990-04-17", Phonebook.normalizeBirthday("1990-04-17T00:00:00Z"))
        assertEquals("1990-04-17", Phonebook.normalizeBirthday("1990-04-17 00:00:00"))
    }

    @Test fun anythingThatIsNotARealDayIsRefusedRatherThanGuessed() {
        listOf(null, "", "  ", "April 17", "17/04/1990", "1990-13-01", "1990-02-30", "--02-30", "--13-01")
            .forEach { assertNull("'$it'", Phonebook.normalizeBirthday(it)) }
    }

    @Test fun aLoneYearIsAcceptedBecauseTheTreeKnowsWhatToDoWithOne() {
        assertEquals("1990", Phonebook.normalizeBirthday("1990"))
    }

    // --- who counts as a person ------------------------------------------------------------------------

    @Test fun aNameWithALetterIsAPersonAndANumberIsNot() {
        assertTrue(Phonebook.looksLikeAPerson("Asha Devi"))
        assertTrue(Phonebook.looksLikeAPerson("आशा"))
        assertFalse(Phonebook.looksLikeAPerson("+91 98765 43210"))
        assertFalse(Phonebook.looksLikeAPerson("12345"))
        assertFalse(Phonebook.looksLikeAPerson("x".repeat(101)))
    }

    // --- rows to contacts ------------------------------------------------------------------------------

    @Test fun eachContactIsOnePersonWithTheirBirthdayIfTheyHaveOne() {
        val rows = listOf(
            name(1, "Asha Devi"), birthday(1, "Asha Devi", "1960-02-11"),
            name(2, "Kiran"),
            birthday(3, "Meera", "--09-30"), name(3, "Meera"),
        )
        assertEquals(
            listOf(
                PhoneContact(1, "Asha Devi", "1960-02-11"),
                PhoneContact(2, "Kiran", null),
                PhoneContact(3, "Meera", "--09-30"),
            ),
            Phonebook.contactsFrom(rows),
        )
    }

    @Test fun onlyABirthdayCountsNotAnAnniversaryOrAnyOtherEvent() {
        val anniversary = birthday(1, "Asha", "1980-05-05", type = 1)
        val other = birthday(1, "Asha", "1999-01-01", type = 2)
        assertNull(Phonebook.contactsFrom(listOf(name(1, "Asha"), anniversary, other)).single().birthday)
    }

    @Test fun anUnreadableBirthdayLeavesTheContactInWithoutOne() {
        val contact = Phonebook.contactsFrom(listOf(name(1, "Asha"), birthday(1, "Asha", "not a date"))).single()
        assertEquals("Asha", contact.name)
        assertNull(contact.birthday)
    }

    @Test fun contactsThatAreOnlyNumbersOrHaveNoNameAreLeftOut() {
        val rows = listOf(name(1, "+44 20 7946 0958"), name(2, null), name(3, "  "), name(4, "Dev"))
        assertEquals(listOf("Dev"), Phonebook.contactsFrom(rows).map { it.name })
    }

    @Test fun theListIsInNameOrderWhateverTheCase() {
        val rows = listOf(name(1, "zoya"), name(2, "Asha"), name(3, "bela"))
        assertEquals(listOf("Asha", "bela", "zoya"), Phonebook.contactsFrom(rows).map { it.name })
    }

    // --- matching names --------------------------------------------------------------------------------

    @Test fun twoSpellingsOfOneNameShareAKey() {
        assertEquals(Phonebook.nameKey("Asha  Devi"), Phonebook.nameKey("  asha devi "))
        assertEquals("", Phonebook.nameKey(null))
        assertFalse(Phonebook.nameKey("Asha Devi") == Phonebook.nameKey("Asha Rao"))
    }
}

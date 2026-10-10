package com.vibethroughcode.ftree.phonebook

import com.vibethroughcode.ftree.data.Gender
import com.vibethroughcode.ftree.data.RelativeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactsImportPlanTest {
    private val asha = PhoneContact(1, "Asha Devi", "1960-02-11")
    private val ravi = PhoneContact(2, "Ravi", null)
    private val meera = PhoneContact(3, "Meera", "--09-30")
    private val kiran = PhoneContact(4, "Kiran", null)
    private val all = listOf(asha, ravi, meera, kiran)

    @Test fun everyRelationWordMeansWhatItSays() {
        assertEquals(RelativeKind.PARENT to Gender.FEMALE, ContactRelation.MOTHER.let { it.kind to it.gender })
        assertEquals(RelativeKind.PARENT to Gender.MALE, ContactRelation.FATHER.let { it.kind to it.gender })
        assertEquals(RelativeKind.SIBLING to Gender.FEMALE, ContactRelation.SISTER.let { it.kind to it.gender })
        assertEquals(RelativeKind.SIBLING to Gender.MALE, ContactRelation.BROTHER.let { it.kind to it.gender })
        assertEquals(RelativeKind.CHILD to Gender.FEMALE, ContactRelation.DAUGHTER.let { it.kind to it.gender })
        assertEquals(RelativeKind.CHILD to Gender.MALE, ContactRelation.SON.let { it.kind to it.gender })
        assertEquals(RelativeKind.SPOUSE to Gender.UNSPECIFIED, ContactRelation.SPOUSE.let { it.kind to it.gender })
        assertNull(ContactRelation.NOT_CONNECTED.kind)
    }

    @Test fun onlyWhatWasTickedIsPlanned_withTheNameAndBirthdayFromTheContact() {
        val plan = ContactsImport.plan("Me", mapOf(1L to ContactRelation.MOTHER, 3L to ContactRelation.SISTER), all, emptySet())
        assertEquals("Me", plan.me)
        assertEquals(
            listOf(
                PlannedPerson(1, "Asha Devi", "1960-02-11", ContactRelation.MOTHER),
                PlannedPerson(3, "Meera", "--09-30", ContactRelation.SISTER),
            ),
            plan.people,
        )
    }

    @Test fun parentsComeFirstSoALaterSisterIsAttachedToThem() {
        val plan = ContactsImport.plan(
            "Me",
            mapOf(2L to ContactRelation.BROTHER, 4L to ContactRelation.SON, 1L to ContactRelation.MOTHER, 3L to ContactRelation.SPOUSE),
            all,
            emptySet(),
        )
        assertEquals(
            listOf(ContactRelation.MOTHER, ContactRelation.SPOUSE, ContactRelation.BROTHER, ContactRelation.SON),
            plan.people.map { it.relation },
        )
    }

    @Test fun withoutAReaderEveryoneIsAddedWithoutAConnection() {
        val plan = ContactsImport.plan(null, mapOf(1L to ContactRelation.MOTHER, 2L to ContactRelation.SON), all, emptySet())
        assertNull(plan.me)
        assertEquals(listOf(ContactRelation.NOT_CONNECTED, ContactRelation.NOT_CONNECTED), plan.people.map { it.relation })
        assertEquals("a blank name is no name", null, ContactsImport.plan("   ", emptyMap(), all, emptySet()).me)
    }

    @Test fun somebodyAlreadyInTheTreeIsLeftOutAndCounted() {
        val plan = ContactsImport.plan(
            "Me",
            mapOf(1L to ContactRelation.MOTHER, 2L to ContactRelation.FATHER),
            all,
            existingNames = setOf(Phonebook.nameKey("  asha  DEVI")),
        )
        assertEquals(listOf("Ravi"), plan.people.map { it.name })
        assertEquals(listOf(asha), plan.alreadyThere)
    }

    @Test fun theReaderIsNotAddedAgainAsOneOfTheirOwnContacts() {
        val plan = ContactsImport.plan("ravi", mapOf(2L to ContactRelation.BROTHER), all, emptySet())
        assertEquals(emptyList<PlannedPerson>(), plan.people)
        assertEquals(listOf(ravi), plan.alreadyThere)
    }

    @Test fun twoEntriesWithOneNameAreOnePerson() {
        val twin = PhoneContact(9, "ravi", null)
        val plan = ContactsImport.plan("Me", mapOf(2L to ContactRelation.BROTHER, 9L to ContactRelation.BROTHER), all + twin, emptySet())
        assertEquals(1, plan.people.size)
    }

    @Test fun anIdThatIsNotInTheListIsIgnored() {
        assertEquals(emptyList<PlannedPerson>(), ContactsImport.plan("Me", mapOf(99L to ContactRelation.MOTHER), all, emptySet()).people)
    }
}

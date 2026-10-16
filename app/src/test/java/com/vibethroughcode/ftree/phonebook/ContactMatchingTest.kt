package com.vibethroughcode.ftree.phonebook

import com.vibethroughcode.ftree.data.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactMatchingTest {
    private fun person(name: String?, born: String? = null) = Person(name = name, birthDate = born)
    private fun contact(name: String, born: String? = null) = PhoneContact(1, name, born)
    private fun match(contactName: String, vararg people: Person, born: String? = null) =
        ContactMatcher(people.toList()).possibleMatch(contact(contactName, born))?.name

    @Test fun anInitialMatchesTheFullSurname() {
        assertEquals("Asha Kumar", match("Asha K", person("Asha Kumar")))
        assertEquals("Asha K", match("Asha Kumar", person("Asha K")))
    }

    @Test fun aFirstNameAloneMatchesTheFullName() {
        assertEquals("Asha Kumar", match("Asha", person("Asha Kumar")))
    }

    @Test fun oneSlipOfThePenInALongWordStillMatches() {
        assertEquals("Priyanka Sharma", match("Priyanka Sharmaa", person("Priyanka Sharma")))
        assertEquals("Priyanka Sharma", match("Priyanka Sharna", person("Priyanka Sharma")))
    }

    @Test fun shortWordsMustBeExactSoRaviIsNotRavi() {
        assertNull(match("Ravi", person("Rani")))
        assertNull(match("Anu", person("Anil")))
    }

    @Test fun accentsAndPunctuationAreSetAside() {
        assertEquals("José Núñez", match("Jose Nunez", person("José Núñez")))
    }

    @Test fun aNicknameForARelativeCannotBeMatchedByName() {
        assertNull(match("Mom", person("Asha Devi")))
    }

    @Test fun anExactNameIsNotAPossibleMatchBecauseItIsAlreadySettled() {
        assertNull(match("asha  DEVI", person("Asha Devi")))
    }

    @Test fun aSingleLetterIsNothingToGoOn() {
        assertNull(match("A", person("Asha")))
    }

    @Test fun differentSurnamesAreDifferentPeople() {
        assertNull(match("Asha Rao", person("Asha Kumar")))
    }

    @Test fun datesThatContradictRuleItOut() {
        assertNull(match("Asha K", person("Asha Kumar", born = "1960-02-11"), born = "1985"))
        assertEquals("Asha Kumar", match("Asha K", person("Asha Kumar", born = "1960-02-11"), born = "1960"))
    }

    @Test fun theClosestOfSeveralIsChosen() {
        assertEquals("Asha Kumari", match("Asha Kumar", person("Asha Kumar Singh"), person("Asha Kumari")))
    }

    @Test fun unnamedPeopleAreIgnored() {
        assertNull(match("Asha", person(null), person("  ")))
    }
}

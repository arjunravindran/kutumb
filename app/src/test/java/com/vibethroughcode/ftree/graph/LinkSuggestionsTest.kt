package com.vibethroughcode.ftree.graph

import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.RelativeKind
import com.vibethroughcode.ftree.graph.LinkSuggestions.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSuggestionsTest {
    private fun person(id: String, name: String?, birth: String? = null) = Person(id = id, name = name, birthDate = birth)

    private fun tree(
        people: List<Person>,
        parents: List<Pair<String, String>> = emptyList(),
        spouses: List<Pair<String, String>> = emptyList(),
    ) = FamilySnapshot(people.associateBy { it.id }, parents, spouses, emptyList())

    // Me, with my mother added from my page; my father is mine too, but nobody has joined him to her.
    private val me = person("me", "Priya")
    private val mother = person("mother", "Asha")
    private val father = person("father", "Ravi")
    private val family = tree(listOf(me, mother, father), parents = listOf("mother" to "me", "father" to "me"))

    @Test fun addingAWifeToMyFatherOffersTheWomanWhoIsAlreadyMyMother() {
        val offered = LinkSuggestions.forNewRelative(RelativeKind.SPOUSE, "father", null, family)
        assertEquals(listOf("mother"), offered.map { it.person.id })
        assertEquals(Reason.SHARES_CHILD, offered.single().reason)
        assertEquals("me", offered.single().via?.id)
    }

    @Test fun anAlreadyMarriedCoupleOffersNoOneToMarry() {
        val married = family.copy(spouseEdges = listOf("father" to "mother"))
        assertTrue(LinkSuggestions.forNewRelative(RelativeKind.SPOUSE, "father", null, married).isEmpty())
    }

    @Test fun addingASecondParentOffersThePartnerOfTheFirst() {
        val oneParent = tree(listOf(me, father, mother), parents = listOf("father" to "me"), spouses = listOf("father" to "mother"))
        val offered = LinkSuggestions.forNewRelative(RelativeKind.PARENT, "me", null, oneParent)
        assertEquals(listOf("mother"), offered.map { it.person.id })
        assertEquals(Reason.PARTNER_OF_PARENT, offered.single().reason)
    }

    @Test fun someoneWithTwoParentsIsOfferedNoThird() {
        val two = family.copy(spouseEdges = listOf("father" to "mother"))
        assertTrue(LinkSuggestions.forNewRelative(RelativeKind.PARENT, "me", null, two).isEmpty())
    }

    @Test fun addingAChildToMeOffersMyPartnersChildren() {
        val kid = person("kid", "Dev")
        val t = tree(listOf(me, kid, person("partner", "Sam")), parents = listOf("partner" to "kid"), spouses = listOf("me" to "partner"))
        val offered = LinkSuggestions.forNewRelative(RelativeKind.CHILD, "me", null, t)
        assertEquals(listOf("kid"), offered.map { it.person.id })
        assertEquals(Reason.CHILD_OF_PARTNER, offered.single().reason)
    }

    @Test fun aNameTypedThatIsAlreadyInTheTreeIsOffered() {
        val offered = LinkSuggestions.forNewRelative(RelativeKind.SPOUSE, "father", "  ASHA ", person("x", null).let { family })
        assertEquals(Reason.SHARES_CHILD, offered.single { it.person.id == "mother" }.reason)
        assertEquals("a person is offered once, for the best reason", 1, offered.size)

        val byNameOnly = LinkSuggestions.forNewRelative(RelativeKind.SIBLING, "me", "asha", family)
        assertEquals(Reason.SAME_NAME, byNameOnly.single().reason)
    }

    @Test fun nobodyIsOfferedWhoCouldNeverBeLinked() {
        val offered = LinkSuggestions.forNewRelative(RelativeKind.SIBLING, "me", "Asha", family, excluded = setOf("me", "mother"))
        assertTrue(offered.isEmpty())
    }

    @Test fun someoneAlreadyThatKindOfRelativeIsNotOfferedAgain() {
        // "Asha" is already my parent; typing her name while adding a parent is not a suggestion to add her again.
        assertTrue(LinkSuggestions.forNewRelative(RelativeKind.PARENT, "me", "Asha", family).isEmpty())
    }

    @Test fun twoEntriesForOneWomanAreSpottedButTwoDifferentOnesAreNot() {
        val twin = person("twin", "asha ")
        val other = person("other", "Asha", birth = "1990")
        val same = person("same", "Asha", birth = "1960")
        val older = person("older", "Asha", birth = "1962")
        val everyone = listOf(mother, twin, other, person("nameless", null))
        assertEquals(listOf("twin", "other"), LinkSuggestions.likelySamePerson(mother, everyone).map { it.id })

        val dated = mother.copy(birthDate = "1960")
        assertEquals("a contradicting birth year rules it out", listOf("twin"), LinkSuggestions.likelySamePerson(dated, listOf(twin, other)).map { it.id })
        assertEquals(listOf("same"), LinkSuggestions.likelySamePerson(dated, listOf(same, older)).map { it.id }.take(1))
        assertTrue(LinkSuggestions.likelySamePerson(person("u", null), listOf(person("v", null))).isEmpty())
    }
}

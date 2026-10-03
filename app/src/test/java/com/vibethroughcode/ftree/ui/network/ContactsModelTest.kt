package com.vibethroughcode.ftree.ui.network

import com.vibethroughcode.ftree.data.Gender
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.graph.FamilySnapshot
import com.vibethroughcode.ftree.graph.KinshipTerm
import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.game.RedemptionStatus
import com.vibethroughcode.ftree.kutumb.game.RewardRedemption
import com.vibethroughcode.ftree.kutumb.game.RewardType
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsModelTest {

    private fun person(id: String, name: String?, gender: Gender = Gender.UNSPECIFIED) = Person(id = id, name = name, gender = gender)

    private fun key() = Bip340Scheme.generateKeyPair().publicKey

    private fun direct(id: String) = TrustedContact(id, key(), pairedAt = 1, pairingMethod = PairingMethod.DIRECT)

    private fun vouched(id: String, by: String) =
        TrustedContact(id, key(), pairedAt = 2, pairingMethod = PairingMethod.VOUCHED, vouchedByPersonId = by)

    // me and Kiran are siblings under mum; Asha is mum's sister (my aunt).
    private val snapshot = FamilySnapshot(
        people = listOf(
            person("me", "Me"), person("kiran", "Kiran"), person("mum", "Mum", Gender.FEMALE),
            person("asha", "Asha", Gender.FEMALE), person("nan", "Nani", Gender.FEMALE), person("ghost", null),
        ).associateBy { it.id },
        parentEdges = listOf("mum" to "me", "mum" to "kiran", "nan" to "mum", "nan" to "asha"),
        spouseEdges = emptyList(),
        siblingEdges = emptyList(),
    )

    @Test
    fun peopleMetInPersonComeFirstThenByName() {
        val rows = ContactsModel.rows("me", listOf(vouched("asha", "kiran"), direct("kiran"), direct("mum")), snapshot)
        assertEquals(listOf("kiran", "mum", "asha"), rows.map { it.contact.personId })
    }

    @Test
    fun aRowCarriesWhoTheyAreAndHowTheyAreRelated() {
        val rows = ContactsModel.rows("me", listOf(vouched("asha", "kiran")), snapshot)
        val asha = rows.single()
        assertEquals("Asha", asha.person?.name)
        assertEquals("Kiran", asha.voucher?.name)
        assertTrue(asha.relation?.term is KinshipTerm.ParentsSibling)
    }

    @Test
    fun theReaderIsNeverTheirOwnContact() {
        assertTrue(ContactsModel.rows("me", listOf(direct("me")), snapshot).isEmpty())
    }

    @Test
    fun aContactWhoLeftTheTreeIsStillListed() {
        val row = ContactsModel.rows("me", listOf(direct("deleted")), snapshot).single()
        assertNull(row.person)
        assertNull(row.relation)
    }

    @Test
    fun unnamedContactsSortLast() {
        val rows = ContactsModel.rows("me", listOf(direct("ghost"), direct("kiran")), snapshot)
        assertEquals(listOf("kiran", "ghost"), rows.map { it.contact.personId })
        assertNotNull(rows.last().person)
    }

    private fun owes(id: String, from: String, to: String, done: Boolean = false) = RewardRedemption(
        id, from, to, RewardType.COFFEE, 5,
        status = if (done) RedemptionStatus.REDEEMED else RedemptionStatus.OWED,
        redeemedAt = if (done) 9L else null,
    )

    @Test
    fun owedListsOnlyWhatIsOutstandingAndInvolvesTheReader() {
        val rows = ContactsModel.owed(
            "me",
            listOf(owes("a", "me", "kiran"), owes("b", "kiran", "me", done = true), owes("c", "kiran", "mum")),
            snapshot,
        )
        assertEquals(listOf("a"), rows.map { it.redemption.id })
    }

    @Test
    fun whatTheReaderOwesComesFirstThenByTheOtherPersonsName() {
        val rows = ContactsModel.owed(
            "me",
            listOf(owes("1", "mum", "me"), owes("2", "me", "mum"), owes("3", "kiran", "me"), owes("4", "me", "asha")),
            snapshot,
        )
        assertEquals(listOf("4", "2", "3", "1"), rows.map { it.redemption.id })
        assertEquals(listOf(true, true, false, false), rows.map { it.youOwe })
        assertEquals("Asha", rows.first().other?.name)
    }

    @Test
    fun anOwedRewardToSomeoneWhoLeftTheTreeIsStillListed() {
        val row = ContactsModel.owed("me", listOf(owes("x", "me", "deleted")), snapshot).single()
        assertNull(row.other)
        assertTrue(row.youOwe)
    }
}

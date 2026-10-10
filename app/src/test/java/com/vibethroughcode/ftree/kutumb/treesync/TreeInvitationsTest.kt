package com.vibethroughcode.ftree.kutumb.treesync

import com.vibethroughcode.ftree.data.CatalogStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeInvitationsTest {
    private class Memory(var text: String? = null) : CatalogStore {
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private fun invite(tree: String = "t1", from: String = "asha", at: Long = 1) =
        PendingInvite(tree, "Ravindran family", 42, from, "ravi", "k".repeat(64), "Asha", "main", at)

    @Test fun anInvitationWaitsUntilSomebodyAnswersIt() {
        val invitations = TreeInvitations(Memory())
        assertTrue(invitations.receive(invite()))
        assertEquals(listOf("t1"), invitations.pending.value.map { it.tree })
    }

    @Test fun theSameOfferTwiceIsOneQuestion() {
        val invitations = TreeInvitations(Memory())
        assertTrue(invitations.receive(invite(at = 1)))
        assertFalse(invitations.receive(invite(at = 2)))
        assertEquals(1, invitations.pending.value.size)
        assertEquals("the first stays", 1L, invitations.pending.value.single().receivedAt)
    }

    @Test fun newestFirst() {
        val invitations = TreeInvitations(Memory())
        invitations.receive(invite(tree = "t1"))
        invitations.receive(invite(tree = "t2"))
        assertEquals(listOf("t2", "t1"), invitations.pending.value.map { it.tree })
    }

    @Test fun anInvitationSurvivesARestart() {
        val store = Memory()
        TreeInvitations(store).receive(invite())
        assertEquals(listOf("t1"), TreeInvitations(store).pending.value.map { it.tree })
    }

    @Test fun notNowForgetsItAndTheyMayOfferAgain() {
        val invitations = TreeInvitations(Memory())
        invitations.receive(invite())
        invitations.dismiss("t1")
        assertTrue(invitations.pending.value.isEmpty())
        assertTrue(invitations.receive(invite()))
    }

    @Test fun onceJoinedTheSameTreeIsNeverOfferedAgain() {
        val store = Memory()
        val invitations = TreeInvitations(store)
        invitations.receive(invite())
        invitations.joined("t1", "local-1")
        assertTrue(invitations.pending.value.isEmpty())
        assertTrue(invitations.hasJoined("t1"))
        assertFalse(invitations.receive(invite()))
        assertTrue("and that outlives a restart", TreeInvitations(store).hasJoined("t1"))
        assertFalse(TreeInvitations(store).receive(invite()))
    }

    @Test fun anOfferForADifferentTreeIsUnaffectedByAnotherBeingJoined() {
        val invitations = TreeInvitations(Memory())
        invitations.joined("t1", "local-1")
        assertTrue(invitations.receive(invite(tree = "t2")))
    }

    @Test fun whoHasAnsweredIsRememberedPerTreeAndPerson() {
        val store = Memory()
        val invitations = TreeInvitations(store)
        assertFalse(invitations.hasAnswered("t1", "ravi"))
        invitations.answered("t1", "ravi")
        assertTrue(invitations.hasAnswered("t1", "ravi"))
        assertFalse(invitations.hasAnswered("t1", "kiran"))
        assertFalse(invitations.hasAnswered("t2", "ravi"))
        assertTrue(TreeInvitations(store).hasAnswered("t1", "ravi"))
    }

    @Test fun unreadableTextStartsEmptyRatherThanCrashing() {
        val invitations = TreeInvitations(Memory("{not json"))
        assertTrue(invitations.pending.value.isEmpty())
        assertTrue(invitations.receive(invite()))
    }
}

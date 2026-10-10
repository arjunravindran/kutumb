package com.vibethroughcode.ftree.kutumb.treesync

import com.vibethroughcode.ftree.data.CatalogStore
import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncPublisher
import com.vibethroughcode.ftree.kutumb.sync.SyncType
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingInviterTest {
    private val ravi = TrustedContact("ravi", "1".repeat(64), pairedAt = 1, pairingMethod = PairingMethod.DIRECT)

    private class Memory : CatalogStore {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private class Transport(var accept: Boolean = true) : SyncPublisher {
        val sent = ArrayList<Pair<SyncEnvelope, List<String>>>()
        override suspend fun publish(envelope: SyncEnvelope, personIds: Collection<String>): Boolean {
            if (accept) sent += envelope to personIds.toList()
            return accept
        }
    }

    private fun inviter(
        transport: Transport = Transport(),
        invitations: TreeInvitations = TreeInvitations(Memory()),
        shared: String? = "t1",
        me: String? = "asha",
        name: String = "  Ravindran family  ",
    ) = SharingInviter({ shared }, { name }, { 42 }, { me }, transport, invitations)

    @Test fun theInvitationNamesTheTreeAndBothPeopleAsTheSharedTreeKnowsThem() = runBlocking {
        val transport = Transport()
        assertTrue(inviter(transport).invite(ravi))
        val (envelope, to) = transport.sent.single()
        assertEquals(listOf("ravi"), to)
        assertEquals(SyncType.TREE_INVITE, envelope.type)
        assertEquals(TreeInvite("t1", "Ravindran family", 42, from = "asha", you = "ravi"), TreeInviteCodec.parseInvite(envelope))
    }

    @Test fun nothingIsSentForATreeThatIsNotSharedOrBeforeThePhoneKnowsWhoItIs() = runBlocking {
        val transport = Transport()
        assertFalse(inviter(transport, shared = null).invite(ravi))
        assertFalse(inviter(transport, me = null).invite(ravi))
        assertFalse(inviter(transport, name = "   ").invite(ravi))
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun nobodyIsInvitedAsThemselves() = runBlocking {
        val transport = Transport()
        assertFalse(inviter(transport, me = "ravi").invite(ravi))
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun aTransportThatRefusesIsReported() = runBlocking {
        assertFalse(inviter(Transport(accept = false)).invite(ravi))
    }

    @Test fun anAnswerIsRememberedPerTreeAndPerson() = runBlocking {
        val invitations = TreeInvitations(Memory())
        val inviter = inviter(invitations = invitations)
        assertFalse(inviter.hasAnswered("ravi"))
        invitations.answered("t1", "ravi")
        assertTrue(inviter.hasAnswered("ravi"))
        assertFalse(inviter.hasAnswered("kiran"))
        assertFalse(inviter(invitations = invitations, shared = null).hasAnswered("ravi"))
    }

    // --- the Sharing rules with an inviter ------------------------------------------------------------------

    private class FakeTree(var shared: String? = "t1") : TreeSyncSource, TreeSyncMembers, TreeSyncSwitch {
        val memberIds = mutableListOf<String>()
        val history = mutableListOf<TreeOp>(TreeOp.PersonAdd("op1", Hlc(1_000, 0, "a"), "p1"))
        override suspend fun sharedId() = shared
        override suspend fun members() = memberIds.toList()
        override suspend fun pendingCount() = 0
        override suspend fun unpublished() = emptyList<TreeOp>()
        override suspend fun allOps() = history.toList()
        override suspend fun markPublished(ids: List<String>) = Unit
        override suspend fun addMember(personId: String) = shared != null && personId !in memberIds && memberIds.add(personId)
        override suspend fun removeMember(personId: String) { memberIds.remove(personId) }
        override suspend fun startSharing() = false
        override suspend fun stopSharing() = false
    }

    private class FakeInviter(var answered: Boolean = false) : TreeInviter {
        val invited = ArrayList<String>()
        override suspend fun invite(contact: TrustedContact): Boolean { invited += contact.personId; return true }
        override suspend fun hasAnswered(personId: String) = answered
    }

    private fun sharing(tree: FakeTree, inviter: FakeInviter, transport: Transport, contacts: List<TrustedContact> = listOf(ravi)) =
        TreeSharing(tree, tree, tree, { contacts }, TreeSyncPublisher(tree, transport), inviter)

    @Test fun addingSomeoneInvitesThemAndHoldsTheTreeBackUntilTheyAsk() = runBlocking {
        val tree = FakeTree()
        val inviter = FakeInviter()
        val transport = Transport()
        val s = sharing(tree, inviter, transport)
        assertTrue(s.setMember(ravi, true))
        assertEquals(listOf("ravi"), inviter.invited)
        assertEquals(listOf("ravi"), tree.memberIds)
        assertTrue("no tree is sent to somebody who has not said yes", transport.sent.none { it.first.type == SyncType.TREE_EDIT && it.second == listOf("ravi") })
        assertTrue(s.state().candidates.single().invitedWaiting)
    }

    @Test fun onceTheyHaveAskedTheRowStopsSayingWaiting() = runBlocking {
        val tree = FakeTree()
        val inviter = FakeInviter()
        val s = sharing(tree, inviter, Transport())
        s.setMember(ravi, true)
        inviter.answered = true
        assertFalse(s.state().candidates.single().invitedWaiting)
    }

    @Test fun somebodyAddedAgainWhoAskedBeforeIsSentWhatTheyMissedAtOnce() = runBlocking {
        val tree = FakeTree()
        val transport = Transport()
        val s = sharing(tree, FakeInviter(answered = true), transport)
        assertTrue(s.setMember(ravi, true))
        assertNotNull(transport.sent.firstOrNull { it.first.type == SyncType.TREE_EDIT && it.second == listOf("ravi") })
    }

    @Test fun nobodyNotPairedInPersonIsInvited() = runBlocking {
        val vouched = TrustedContact("meera", "2".repeat(64), pairedAt = 1, pairingMethod = PairingMethod.VOUCHED, vouchedByPersonId = "ravi")
        val tree = FakeTree()
        val inviter = FakeInviter()
        assertFalse(sharing(tree, inviter, Transport(), listOf(vouched)).setMember(vouched, true))
        assertTrue(inviter.invited.isEmpty())
        assertTrue(tree.memberIds.isEmpty())
    }

    @Test fun addingSomeoneTwiceInvitesThemOnce() = runBlocking {
        val inviter = FakeInviter()
        val s = sharing(FakeTree(), inviter, Transport())
        assertTrue(s.setMember(ravi, true))
        assertFalse(s.setMember(ravi, true))
        assertEquals(1, inviter.invited.size)
    }
}

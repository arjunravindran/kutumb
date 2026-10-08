package com.vibethroughcode.ftree.kutumb.treesync

import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncPublisher
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeSharingTest {
    private fun contact(id: String, method: PairingMethod, key: Char) = TrustedContact(
        personId = id,
        pubKeyCurrent = key.toString().repeat(64),
        pairedAt = 1,
        pairingMethod = method,
        vouchedByPersonId = if (method == PairingMethod.VOUCHED) "kiran" else null,
    )

    private val kiran = contact("kiran", PairingMethod.DIRECT, 'a')
    private val asha = contact("asha", PairingMethod.DIRECT, 'b')
    private val meera = contact("meera", PairingMethod.VOUCHED, 'c')

    /** A shared-tree stand-in that can also be switched on, like the real coordinator. */
    private class FakeTree(var shared: String? = null) : TreeSyncSource, TreeSyncMembers, TreeSyncSwitch {
        val memberIds = mutableListOf<String>()
        var pending = 0
        val history = mutableListOf<TreeOp>()
        override suspend fun sharedId() = shared
        override suspend fun members() = memberIds.toList()
        override suspend fun pendingCount() = pending
        override suspend fun unpublished() = emptyList<TreeOp>()
        override suspend fun allOps() = history.toList()
        override suspend fun markPublished(ids: List<String>) = Unit
        override suspend fun addMember(personId: String) = shared != null && personId !in memberIds && memberIds.add(personId)
        override suspend fun removeMember(personId: String) { memberIds.remove(personId) }
        override suspend fun startSharing(): Boolean { if (shared != null) return false; shared = "tree-1"; return true }
    }

    private class Transport(var accept: Boolean = true) : SyncPublisher {
        val sent = ArrayList<List<String>>()
        override suspend fun publish(envelope: SyncEnvelope, personIds: Collection<String>): Boolean {
            if (accept) sent += personIds.toList()
            return accept
        }
    }

    private fun sharing(tree: FakeTree, contacts: List<TrustedContact>, transport: Transport = Transport()) =
        TreeSharing(tree, tree, tree, { contacts }, TreeSyncPublisher(tree, transport))

    @Test fun anUnsharedTreeOffersNobodyAndNothingIsWaiting() = runBlocking {
        val state = sharing(FakeTree(), listOf(kiran, asha)).state()
        assertEquals(SharingState(shared = false, candidates = emptyList(), waiting = 0), state)
    }

    @Test fun onceSharedOnlyContactsPairedInPersonAreOffered_inAStableOrder() = runBlocking {
        val tree = FakeTree()
        val s = sharing(tree, listOf(meera, asha, kiran))
        assertTrue(s.turnOn())
        assertEquals(listOf("asha", "kiran"), s.state().candidates.map { it.personId })
        assertTrue("nobody is a member until chosen", s.state().candidates.none { it.isMember })
    }

    @Test fun turningItOnTwiceDoesNothingTheSecondTime() = runBlocking {
        val s = sharing(FakeTree(), listOf(kiran))
        assertTrue(s.turnOn())
        assertFalse(s.turnOn())
    }

    @Test fun addingAMemberMarksThemAndSendsThemTheTree() = runBlocking {
        val tree = FakeTree(shared = "tree-1")
        tree.history += TreeOp.PersonAdd("1", Hlc(1, 0, "a"), "p")
        val transport = Transport()
        val s = sharing(tree, listOf(kiran, asha), transport)
        assertTrue(s.setMember(kiran, true))
        // Candidates are in id order (asha, kiran), so only the second is a member.
        assertEquals(listOf(false, true), s.state().candidates.map { it.isMember })
        assertEquals(listOf(listOf("kiran")), transport.sent)
    }

    @Test fun removingAMemberStopsThemBeingOne_andRemovingANonMemberChangesNothing() = runBlocking {
        val tree = FakeTree(shared = "tree-1")
        val s = sharing(tree, listOf(kiran))
        s.setMember(kiran, true)
        assertTrue(s.setMember(kiran, false))
        assertFalse(s.state().candidates.single().isMember)
        assertFalse(s.setMember(kiran, false))
    }

    @Test fun someoneOnlyVouchedForCannotBeAdded() = runBlocking {
        val tree = FakeTree(shared = "tree-1")
        val transport = Transport()
        assertFalse(sharing(tree, listOf(meera), transport).setMember(meera, true))
        assertTrue(tree.memberIds.isEmpty() && transport.sent.isEmpty())
    }

    @Test fun nobodyCanBeAddedToATreeThatIsNotShared() = runBlocking {
        val tree = FakeTree()
        assertFalse(sharing(tree, listOf(kiran)).setMember(kiran, true))
        assertTrue(tree.memberIds.isEmpty())
    }

    @Test fun theWaitingCountComesFromTheTree() = runBlocking {
        val tree = FakeTree(shared = "tree-1").also { it.pending = 3 }
        assertEquals(3, sharing(tree, emptyList()).state().waiting)
    }
}

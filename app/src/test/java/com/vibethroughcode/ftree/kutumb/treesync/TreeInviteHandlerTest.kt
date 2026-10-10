package com.vibethroughcode.ftree.kutumb.treesync

import com.vibethroughcode.ftree.data.CatalogStore
import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncPublisher
import com.vibethroughcode.ftree.kutumb.sync.SyncType
import com.vibethroughcode.ftree.kutumb.sync.UnsignedEvent
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeInviteHandlerTest {
    private val rumor = UnsignedEvent("c".repeat(64), 1, 30078, emptyList(), "")
    private val asha = TrustedContact("asha-here", "a".repeat(64), pairedAt = 1, pairingMethod = PairingMethod.DIRECT)
    private val vouched = TrustedContact("meera", "b".repeat(64), pairedAt = 1, pairingMethod = PairingMethod.VOUCHED, vouchedByPersonId = "asha-here")
    private val invite = TreeInvite("t1", "Ravindran family", 42, from = "asha", you = "ravi")

    private class Tree(var shared: String? = null, val members: MutableList<String> = mutableListOf()) : TreeSyncSink, TreeSyncSource {
        val history = mutableListOf<TreeOp>(TreeOp.PersonAdd("op1", Hlc(1_000, 0, "a"), "p1"))
        override suspend fun sharedId() = shared
        override suspend fun isMember(personId: String) = personId in members
        override suspend fun receive(ops: List<TreeOp>) = Received(ops.size, 0, 0)
        override suspend fun members() = members.toList()
        override suspend fun pendingCount() = 0
        override suspend fun unpublished() = emptyList<TreeOp>()
        override suspend fun allOps() = history.toList()
        override suspend fun markPublished(ids: List<String>) = Unit
    }

    private class Transport : SyncPublisher {
        val sent = ArrayList<Pair<SyncEnvelope, List<String>>>()
        override suspend fun publish(envelope: SyncEnvelope, personIds: Collection<String>): Boolean {
            sent += envelope to personIds.toList()
            return true
        }
    }

    private class Memory : CatalogStore {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }

    private class Rig(val tree: Tree = Tree()) {
        val invitations = TreeInvitations(Memory())
        val transport = Transport()
        val handler = TreeInviteHandler(
            tree, invitations, TreeSyncPublisher(tree, transport), "main",
            { if (it == "asha-here") "Asha" else null }, { 77L },
        )
    }

    private fun joinEnvelope(tree: String = "t1") = TreeInviteCodec.join(TreeJoin(tree))

    @Test fun anInvitationFromAContactMetInPersonIsKeptWithWhoSentItAndWhere() = runBlocking {
        val rig = Rig()
        rig.handler.onEnvelope(TreeInviteCodec.invite(invite), asha, rumor)
        val kept = rig.invitations.pending.value.single()
        assertEquals(PendingInvite("t1", "Ravindran family", 42, "asha", "ravi", "a".repeat(64), "Asha", "main", 77L), kept)
    }

    @Test fun anInvitationFromSomeoneOnlyVouchedForIsIgnored() = runBlocking {
        val rig = Rig()
        rig.handler.onEnvelope(TreeInviteCodec.invite(invite), vouched, rumor)
        assertTrue(rig.invitations.pending.value.isEmpty())
    }

    @Test fun anInvitationToATreeThisOneAlreadyIsIsIgnored() = runBlocking {
        val rig = Rig(Tree(shared = "t1"))
        rig.handler.onEnvelope(TreeInviteCodec.invite(invite), asha, rumor)
        assertTrue(rig.invitations.pending.value.isEmpty())
    }

    @Test fun aMalformedInvitationIsIgnored() = runBlocking {
        val rig = Rig()
        rig.handler.onEnvelope(SyncEnvelope(SyncType.TREE_INVITE, mapOf("tree" to "t1")), asha, rumor)
        assertTrue(rig.invitations.pending.value.isEmpty())
    }

    @Test fun theSameInvitationTwiceIsOneQuestion() = runBlocking {
        val rig = Rig()
        rig.handler.onEnvelope(TreeInviteCodec.invite(invite), asha, rumor)
        rig.handler.onEnvelope(TreeInviteCodec.invite(invite), asha, rumor)
        assertEquals(1, rig.invitations.pending.value.size)
    }

    @Test fun aJoinFromAMemberSendsThemTheWholeTreeAndRecordsTheyAnswered() = runBlocking {
        val rig = Rig(Tree(shared = "t1", members = mutableListOf("asha-here")))
        rig.handler.onEnvelope(joinEnvelope(), asha, rumor)
        assertEquals(listOf(listOf("asha-here")), rig.transport.sent.map { it.second })
        assertEquals(SyncType.TREE_EDIT, rig.transport.sent.single().first.type)
        assertTrue(rig.invitations.hasAnswered("t1", "asha-here"))
    }

    @Test fun aJoinFromSomeoneWhoWasNeverAddedIsIgnored() = runBlocking {
        val rig = Rig(Tree(shared = "t1"))
        rig.handler.onEnvelope(joinEnvelope(), asha, rumor)
        assertTrue(rig.transport.sent.isEmpty())
        assertFalse(rig.invitations.hasAnswered("t1", "asha-here"))
    }

    @Test fun aJoinForAnotherTreeOrWhenNotSharedIsIgnored() = runBlocking {
        Rig(Tree(shared = "other", members = mutableListOf("asha-here"))).let { rig ->
            rig.handler.onEnvelope(joinEnvelope(), asha, rumor)
            assertTrue(rig.transport.sent.isEmpty())
        }
        Rig(Tree(shared = null)).let { rig ->
            rig.handler.onEnvelope(joinEnvelope(), asha, rumor)
            assertTrue(rig.transport.sent.isEmpty())
        }
    }

    @Test fun aJoinFromSomeoneOnlyVouchedForIsIgnoredEvenIfAMember() = runBlocking {
        val rig = Rig(Tree(shared = "t1", members = mutableListOf("meera")))
        rig.handler.onEnvelope(joinEnvelope(), vouched, rumor)
        assertTrue(rig.transport.sent.isEmpty())
    }

    @Test fun otherMessageTypesAreNotThisHandlersBusiness() = runBlocking {
        val rig = Rig(Tree(shared = "t1", members = mutableListOf("asha-here")))
        rig.handler.onEnvelope(SyncEnvelope(SyncType.TREE_EDIT, emptyMap()), asha, rumor)
        assertTrue(rig.transport.sent.isEmpty())
        assertTrue(rig.invitations.pending.value.isEmpty())
    }
}

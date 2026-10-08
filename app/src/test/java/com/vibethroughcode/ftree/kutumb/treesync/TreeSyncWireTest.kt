package com.vibethroughcode.ftree.kutumb.treesync

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

class TreeSyncWireTest {
    private val at = Hlc(1_000, 0, "a")
    private fun op(n: Int) = TreeOp.PersonAdd("op$n", Hlc(1_000L + n, 0, "a"), "p$n")

    private val direct = TrustedContact("kiran", "a".repeat(64), pairedAt = 1, pairingMethod = PairingMethod.DIRECT)
    private val vouched = TrustedContact("asha", "b".repeat(64), pairedAt = 1, pairingMethod = PairingMethod.VOUCHED, vouchedByPersonId = "kiran")
    private val rumor = UnsignedEvent("c".repeat(64), 1, 30078, emptyList(), "")

    private class FakeTree(
        var shared: String? = "tree-1",
        val members: MutableList<String> = mutableListOf("kiran"),
        var pending: MutableList<TreeOp> = mutableListOf(),
        val history: MutableList<TreeOp> = mutableListOf(),
    ) : TreeSyncSink, TreeSyncSource, TreeSyncMembers {
        val received = ArrayList<TreeOp>()
        val published = ArrayList<String>()
        override suspend fun sharedId() = shared
        override suspend fun isMember(personId: String) = personId in members
        override suspend fun receive(ops: List<TreeOp>): Received { received += ops; return Received(ops.size, 0, 0) }
        override suspend fun members() = members.toList()
        override suspend fun unpublished() = pending.toList()
        override suspend fun allOps() = history.toList()
        override suspend fun markPublished(ids: List<String>) { published += ids; pending.removeAll { it.id in ids } }
        override suspend fun addMember(personId: String): Boolean = shared != null && personId !in members && members.add(personId)
        override suspend fun removeMember(personId: String) { members.remove(personId) }
    }

    private class FakeTransport(var accept: Boolean = true) : SyncPublisher {
        val sent = ArrayList<Pair<SyncEnvelope, List<String>>>()
        override suspend fun publish(envelope: SyncEnvelope, personIds: Collection<String>): Boolean {
            if (!accept) return false
            sent += envelope to personIds.toList()
            return true
        }
    }

    private fun envelope(tree: String = "tree-1", ops: List<TreeOp> = listOf(op(1))) =
        SyncEnvelope(SyncType.TREE_EDIT, TreeOpCodec.payload(tree, ops))

    // --- receiving ------------------------------------------------------------------------------------

    @Test fun aMemberPairedInPersonIsHeard() = runBlocking {
        val tree = FakeTree()
        TreeSyncHandler(tree).onEnvelope(envelope(ops = listOf(op(1), op(2))), direct, rumor)
        assertEquals(listOf("op1", "op2"), tree.received.map { it.id })
    }

    @Test fun aContactOnlyVouchedForIsNeverHeard_evenIfSomehowAMember() = runBlocking {
        val tree = FakeTree(members = mutableListOf("asha"))
        TreeSyncHandler(tree).onEnvelope(envelope(), vouched, rumor)
        assertTrue(tree.received.isEmpty())
    }

    @Test fun aPairedContactWhoIsNotAMemberIsNotHeard() = runBlocking {
        val tree = FakeTree(members = mutableListOf())
        TreeSyncHandler(tree).onEnvelope(envelope(), direct, rumor)
        assertTrue(tree.received.isEmpty())
    }

    @Test fun aMessageForAnotherTreeOrWhenNothingIsSharedIsDropped() = runBlocking {
        val handler = { tree: FakeTree -> TreeSyncHandler(tree) }
        val other = FakeTree(); handler(other).onEnvelope(envelope(tree = "someone-elses"), direct, rumor)
        val unshared = FakeTree(shared = null); handler(unshared).onEnvelope(envelope(), direct, rumor)
        assertTrue(other.received.isEmpty() && unshared.received.isEmpty())
    }

    @Test fun otherKindsOfMessageAndMalformedOnesAreIgnored() = runBlocking {
        val tree = FakeTree()
        val handler = TreeSyncHandler(tree)
        handler.onEnvelope(SyncEnvelope(SyncType.MESSAGE, mapOf("body" to "hi")), direct, rumor)
        handler.onEnvelope(SyncEnvelope(SyncType.TREE_EDIT, mapOf("ops" to "nope")), direct, rumor)
        handler.onEnvelope(SyncEnvelope(SyncType.TREE_EDIT, emptyMap()), direct, rumor)
        handler.onEnvelope(envelope(ops = emptyList()), direct, rumor)
        assertTrue(tree.received.isEmpty())
    }

    @Test fun anEnvelopeSurvivesTheTripOverTheWire() = runBlocking {
        val tree = FakeTree()
        val wire = SyncEnvelope.parse(envelope(ops = listOf(op(7))).toJson())!!
        TreeSyncHandler(tree).onEnvelope(wire, direct, rumor)
        assertEquals(listOf(op(7)), tree.received)
    }

    // --- sending --------------------------------------------------------------------------------------

    @Test fun flushSendsWhatIsPendingToEveryMemberInBatchesAndMarksItSent() = runBlocking {
        val tree = FakeTree(members = mutableListOf("kiran", "asha"), pending = MutableList(7) { op(it) })
        val transport = FakeTransport()
        assertEquals(7, TreeSyncPublisher(tree, transport, batchSize = 3).flush())
        assertEquals(listOf(3, 3, 1), transport.sent.map { TreeOpCodec.parsePayload(it.first.payload)!!.ops.size })
        assertTrue(transport.sent.all { it.second == listOf("kiran", "asha") })
        assertEquals(7, tree.published.size)
        assertTrue(tree.pending.isEmpty())
    }

    @Test fun whenSyncCannotSendNothingIsMarkedSentAndTheNextFlushTriesAgain() = runBlocking {
        val tree = FakeTree(pending = MutableList(4) { op(it) })
        val transport = FakeTransport(accept = false)
        val publisher = TreeSyncPublisher(tree, transport, batchSize = 2)
        assertEquals(0, publisher.flush())
        assertEquals(4, tree.pending.size)
        transport.accept = true
        assertEquals(4, publisher.flush())
        assertTrue(tree.pending.isEmpty())
    }

    @Test fun nothingIsSentWithoutMembersOrAnUnsharedTree() = runBlocking {
        val noMembers = FakeTree(members = mutableListOf(), pending = mutableListOf(op(1)))
        val unshared = FakeTree(shared = null, pending = mutableListOf(op(1)))
        val transport = FakeTransport()
        assertEquals(0, TreeSyncPublisher(noMembers, transport).flush())
        assertEquals(0, TreeSyncPublisher(unshared, transport).flush())
        assertTrue(transport.sent.isEmpty())
        assertEquals("still waiting for someone to send to", 1, noMembers.pending.size)
    }

    // --- adding a member ------------------------------------------------------------------------------

    @Test fun aNewMemberIsSentTheWholeHistoryAtOnce() = runBlocking {
        val tree = FakeTree(members = mutableListOf(), history = MutableList(5) { op(it) })
        val transport = FakeTransport()
        assertTrue(shareTreeWith(direct, tree, TreeSyncPublisher(tree, transport, batchSize = 2)))
        assertEquals(listOf("kiran"), tree.members)
        assertEquals(3, transport.sent.size)
        assertTrue(transport.sent.all { it.second == listOf("kiran") })
    }

    @Test fun onlyAContactPairedInPersonCanBeAdded() = runBlocking {
        val tree = FakeTree(members = mutableListOf(), history = mutableListOf(op(1)))
        val transport = FakeTransport()
        assertFalse(shareTreeWith(vouched, tree, TreeSyncPublisher(tree, transport)))
        assertTrue(tree.members.isEmpty() && transport.sent.isEmpty())
    }

    @Test fun addingSomeoneTwiceSendsNothingTheSecondTime() = runBlocking {
        val tree = FakeTree(members = mutableListOf(), history = mutableListOf(op(1)))
        val transport = FakeTransport()
        val publisher = TreeSyncPublisher(tree, transport)
        assertTrue(shareTreeWith(direct, tree, publisher))
        val sent = transport.sent.size
        assertFalse(shareTreeWith(direct, tree, publisher))
        assertEquals(sent, transport.sent.size)
    }

    @Test fun anUnsharedTreeCannotHaveMembers() = runBlocking {
        val tree = FakeTree(shared = null, members = mutableListOf())
        assertFalse(shareTreeWith(direct, tree, TreeSyncPublisher(tree, FakeTransport())))
    }
}

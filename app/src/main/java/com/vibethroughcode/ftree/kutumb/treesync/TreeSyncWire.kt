package com.vibethroughcode.ftree.kutumb.treesync

import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncHandler
import com.vibethroughcode.ftree.kutumb.sync.SyncPublisher
import com.vibethroughcode.ftree.kutumb.sync.SyncType
import com.vibethroughcode.ftree.kutumb.sync.UnsignedEvent
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact

/** What the inbound side needs from a shared tree. Implemented by [TreeSyncCoordinator]; faked in tests. */
interface TreeSyncSink {
    suspend fun sharedId(): String?
    suspend fun isMember(personId: String): Boolean
    suspend fun receive(ops: List<TreeOp>): Received
}

/** What the outbound side needs from a shared tree. Implemented by [TreeSyncCoordinator]; faked in tests. */
interface TreeSyncSource {
    suspend fun sharedId(): String?
    suspend fun members(): List<String>
    suspend fun pendingCount(): Int
    suspend fun unpublished(): List<TreeOp>
    suspend fun allOps(): List<TreeOp>
    suspend fun markPublished(ids: List<String>)
}

/**
 * Applies tree edits from the family. A message is taken in only if every one of these holds:
 * the sender was paired **in person** (a vouched-for contact is read-only), the message names **this**
 * shared tree, and the sender is one of its **members**. Anything else is dropped without a word, like
 * the other handlers: a refused message would be refused again on redelivery, so failing it helps nobody.
 *
 * What is accepted is then applied by [TreeSyncSink.receive], which does the merging and is idempotent.
 */
class TreeSyncHandler(private val tree: TreeSyncSink) : SyncHandler {
    override suspend fun onEnvelope(envelope: SyncEnvelope, sender: TrustedContact, rumor: UnsignedEvent) {
        if (envelope.type != SyncType.TREE_EDIT) return
        if (sender.pairingMethod != PairingMethod.DIRECT) return
        val decoded = TreeOpCodec.parsePayload(envelope.payload) ?: return
        val shared = tree.sharedId() ?: return
        if (decoded.tree != shared) return
        if (!tree.isMember(sender.personId)) return
        if (decoded.ops.isEmpty()) return
        tree.receive(decoded.ops)
    }
}

/**
 * Sends this phone's recorded changes to the tree's members, a batch at a time, and marks them sent
 * once the transport has queued them. The transport keeps its own retry queue, so "sent" here means
 * handed over, and a failure to hand over leaves the rest for the next [flush].
 */
class TreeSyncPublisher(
    private val tree: TreeSyncSource,
    private val transport: SyncPublisher,
    private val batchSize: Int = BATCH,
) {
    /** Sends everything not yet sent to all members. Returns how many operations went. */
    suspend fun flush(): Int {
        val shared = tree.sharedId() ?: return 0
        val members = tree.members()
        if (members.isEmpty()) return 0
        var sent = 0
        for (batch in tree.unpublished().chunked(batchSize)) {
            if (!transport.publish(envelope(shared, batch), members)) break
            tree.markPublished(batch.map { it.id })
            sent += batch.size
        }
        return sent
    }

    /**
     * Sends the whole history to one person, for a member who has just been added and holds none of it.
     * Operations they already have change nothing for them, so repeating this is safe. Returns whether every
     * batch was handed over.
     */
    suspend fun sendEverythingTo(personId: String): Boolean {
        val shared = tree.sharedId() ?: return false
        for (batch in tree.allOps().chunked(batchSize)) {
            if (!transport.publish(envelope(shared, batch), listOf(personId))) return false
        }
        return true
    }

    private fun envelope(shared: String, ops: List<TreeOp>) = SyncEnvelope(SyncType.TREE_EDIT, TreeOpCodec.payload(shared, ops))

    companion object {
        /** Kept well under a relay's event size limit: an operation is a couple of hundred bytes. */
        const val BATCH = 100
    }
}

/** Who a shared tree is shared with. Implemented by [TreeSyncCoordinator]. */
interface TreeSyncMembers {
    suspend fun addMember(personId: String): Boolean
    suspend fun removeMember(personId: String)
}

/**
 * Shares the tree with somebody. Only a contact paired **in person** can be a member: pairing in person is
 * what proves who they are, and the tree holds the details of living relatives. Returns false if the
 * contact is not eligible, the tree is not shared, or they were a member already (nothing is sent then).
 * A new member is sent the whole history straight away, since they hold none of it.
 */
suspend fun shareTreeWith(contact: TrustedContact, members: TreeSyncMembers, publisher: TreeSyncPublisher): Boolean {
    if (contact.pairingMethod != PairingMethod.DIRECT) return false
    if (!members.addMember(contact.personId)) return false
    publisher.sendEverythingTo(contact.personId)
    return true
}

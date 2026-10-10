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
class TreeSyncHandler(
    private val tree: TreeSyncSink,
    /** Told which member has just sent a valid edit for which shared tree: they plainly hold it. */
    private val heardFrom: (sharedTreeId: String, personId: String) -> Unit = { _, _ -> },
) : SyncHandler {
    override suspend fun onEnvelope(envelope: SyncEnvelope, sender: TrustedContact, rumor: UnsignedEvent) {
        if (envelope.type != SyncType.TREE_EDIT) return
        if (sender.pairingMethod != PairingMethod.DIRECT) return
        val decoded = TreeOpCodec.parsePayload(envelope.payload) ?: return
        val shared = tree.sharedId() ?: return
        if (decoded.tree != shared) return
        if (!tree.isMember(sender.personId)) return
        if (decoded.ops.isEmpty()) return
        tree.receive(decoded.ops)
        heardFrom(shared, sender.personId)
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

/**
 * Hears the two messages that bring somebody into a shared tree.
 *
 * `tree-invite` is kept as a waiting invitation, only from a contact paired **in person**, and never for a
 * tree this tree already is. Nothing is created until the reader says yes. `tree-join` is the answer: if it
 * comes from a paired contact who is a member of **this** shared tree, they are sent the whole history, which
 * is what they have just made room for. Anything else is dropped without a word, like the other handlers.
 */
class TreeInviteHandler(
    private val tree: TreeSyncSink,
    private val invitations: TreeInvitations,
    private val publisher: TreeSyncPublisher,
    /** The id of the tree on this phone that holds the pairing, which is where the invitation arrived. */
    private val viaTree: String,
    /** What this tree calls a person, for the line saying who shared the tree. */
    private val nameOf: suspend (String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncHandler {
    override suspend fun onEnvelope(envelope: SyncEnvelope, sender: TrustedContact, rumor: UnsignedEvent) {
        if (sender.pairingMethod != PairingMethod.DIRECT) return
        when (envelope.type) {
            SyncType.TREE_INVITE -> {
                val invite = TreeInviteCodec.parseInvite(envelope) ?: return
                if (tree.sharedId() == invite.tree) return
                invitations.receive(
                    PendingInvite(
                        tree = invite.tree, name = invite.name, people = invite.people,
                        from = invite.from, you = invite.you,
                        senderKey = sender.pubKeyCurrent, fromName = nameOf(sender.personId),
                        viaTree = viaTree, receivedAt = clock(),
                    ),
                )
            }
            SyncType.TREE_JOIN -> {
                val join = TreeInviteCodec.parseJoin(envelope) ?: return
                if (tree.sharedId() != join.tree) return
                if (!tree.isMember(sender.personId)) return
                invitations.answered(join.tree, sender.personId)
                publisher.sendEverythingTo(sender.personId)
            }
            else -> Unit
        }
    }
}

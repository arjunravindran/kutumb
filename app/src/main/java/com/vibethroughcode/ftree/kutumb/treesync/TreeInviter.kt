package com.vibethroughcode.ftree.kutumb.treesync

import com.vibethroughcode.ftree.kutumb.sync.SyncPublisher
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact

/** What the Sharing screen needs to invite somebody into the open shared tree. Faked in tests. */
interface TreeInviter {
    /** Sends [contact] the invitation. False if it could not be sent (no identity yet, or sync is off). */
    suspend fun invite(contact: TrustedContact): Boolean

    /** Whether [personId] has already asked for this tree, so they hold it and only need what they missed. */
    suspend fun hasAnswered(personId: String): Boolean
}

/**
 * Builds the invitation from what this phone knows about the open tree and hands it to the transport.
 * The ids it carries are inside the shared tree: [myPersonId] is who this phone is in it, and the
 * invited person is the one this tree has recorded them as.
 */
class SharingInviter(
    private val sharedId: suspend () -> String?,
    private val treeName: () -> String,
    private val peopleCount: suspend () -> Int,
    private val myPersonId: suspend () -> String?,
    private val transport: SyncPublisher,
    private val invitations: TreeInvitations,
) : TreeInviter {
    override suspend fun invite(contact: TrustedContact): Boolean {
        val shared = sharedId() ?: return false
        val me = myPersonId() ?: return false
        if (me == contact.personId) return false
        val name = treeName().trim().take(TreeInviteCodec.MAX_NAME).ifEmpty { return false }
        val invite = TreeInvite(shared, name, peopleCount(), from = me, you = contact.personId)
        return transport.publish(TreeInviteCodec.invite(invite), listOf(contact.personId))
    }

    override suspend fun hasAnswered(personId: String): Boolean {
        val shared = sharedId() ?: return false
        return invitations.hasAnswered(shared, personId)
    }
}

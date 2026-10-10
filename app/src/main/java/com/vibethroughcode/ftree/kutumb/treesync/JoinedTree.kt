package com.vibethroughcode.ftree.kutumb.treesync

import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact

/**
 * Makes a new, empty tree into the shared tree [invite] offered, so it can hear from the person who offered it.
 *
 * The tree is marked as shared under the inviter's id for it, this phone is recorded as the person the inviter
 * says it is (with [key], the key the inviter knows it by), and the inviter is recorded as somebody met in person
 * and made a member. Without those three the tree would drop everything the inviter sends. No people are added
 * here: they arrive from the inviter, with the ids they have in the shared tree.
 */
suspend fun prepareJoinedTree(
    treeSync: TreeSyncCoordinator,
    kutumb: KutumbRepository,
    invite: PendingInvite,
    key: KeyPair,
    now: Long,
) {
    treeSync.share(invite.tree)
    kutumb.setIdentity(LocalIdentity(invite.you, key))
    kutumb.saveContact(TrustedContact(invite.from, invite.senderKey, pairedAt = now, pairingMethod = PairingMethod.DIRECT))
    treeSync.addMember(invite.from)
}

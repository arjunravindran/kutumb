package com.vibethroughcode.ftree.kutumb.treesync

import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact

/** What the Sharing screen needs to switch sharing on. Implemented by [TreeSyncCoordinator]; faked in tests. */
interface TreeSyncSwitch {
    /** Starts sharing this tree. False if it was already shared. */
    suspend fun startSharing(): Boolean

    /** Stops sharing this tree and forgets who it was shared with. The tree itself is left alone. False if it was not shared. */
    suspend fun stopSharing(): Boolean
}

/** One person the tree could be shared with, and whether it is. */
data class SharingCandidate(val personId: String, val isMember: Boolean)

/**
 * What the Sharing screen shows.
 *
 * [candidates] are only the contacts paired **in person**: nobody else can be a member, so nobody else is
 * offered. [waiting] is the number of this phone's changes not yet handed to the transport, which is how
 * the screen can say "3 changes waiting" while sync is off or nobody is reachable.
 */
data class SharingState(
    val shared: Boolean,
    val candidates: List<SharingCandidate>,
    val waiting: Int,
)

/**
 * The rules behind the Sharing screen, with no Android in them so they can be tested directly.
 * The screen is a thin layer over this: it shows [state] and calls these three functions.
 */
class TreeSharing(
    private val tree: TreeSyncSource,
    private val switch: TreeSyncSwitch,
    private val members: TreeSyncMembers,
    private val contacts: suspend () -> List<TrustedContact>,
    private val publisher: TreeSyncPublisher,
) {
    suspend fun state(): SharingState {
        if (tree.sharedId() == null) return SharingState(shared = false, candidates = emptyList(), waiting = 0)
        val current = tree.members().toSet()
        val candidates = contacts()
            .filter { it.pairingMethod == PairingMethod.DIRECT }
            .sortedBy { it.personId }
            .map { SharingCandidate(it.personId, it.personId in current) }
        return SharingState(shared = true, candidates = candidates, waiting = tree.pendingCount())
    }

    /** Switches sharing on. Nobody receives anything until they are added as a member. */
    suspend fun turnOn(): Boolean = switch.startSharing()

    /** Stops sharing. Phones that already hold the tree keep their copy; nothing is taken back from them. */
    suspend fun turnOff(): Boolean = switch.stopSharing()

    /**
     * Adds or removes a member. Adding sends them the whole tree straight away. Returns whether anything changed:
     * false for somebody who is not eligible (not paired in person), who already was (or was not) a member, or
     * when the tree is not shared.
     */
    suspend fun setMember(contact: TrustedContact, member: Boolean): Boolean {
        if (tree.sharedId() == null) return false
        if (member) {
            if (!shareTreeWith(contact, members, publisher)) return false
            // Sending a new member the whole history does not mark operations as sent, so the "waiting"
            // count would stay up until the next pass. Flush now; a duplicate delivery is harmless.
            publisher.flush()
            return true
        }
        if (contact.personId !in tree.members()) return false
        members.removeMember(contact.personId)
        return true
    }
}

package com.vibethroughcode.ftree.kutumb.treesync

import com.vibethroughcode.ftree.data.CatalogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A tree somebody has offered to share, waiting for the reader to say yes or no.
 *
 * [from] and [you] are ids inside the shared tree (see [TreeInvite]). [senderKey] is the key the inviter
 * is known by in [viaTree], the tree on this phone that received the message and holds the pairing; the
 * new tree is given the same key and contact so the inviter recognises this phone there.
 */
@Serializable
data class PendingInvite(
    val tree: String,
    val name: String,
    val people: Int,
    val from: String,
    val you: String,
    val senderKey: String,
    val fromName: String?,
    val viaTree: String,
    val receivedAt: Long,
)

/**
 * The invitations this phone holds, and which shared trees it has already joined. It belongs to the
 * phone, not to a tree: an invitation can arrive through any tree that holds the pairing, and what
 * accepting it makes is a new tree. It is kept as text in preferences so a restart cannot lose one.
 *
 * Also remembers, for a tree this phone shares, which invited people have answered. That is only what
 * the Sharing screen needs to say "waiting"; whether somebody may take part is still decided by the
 * tree's own list of members.
 */
class TreeInvitations(private val store: CatalogStore) {
    @Serializable
    private data class Stored(
        val pending: List<PendingInvite> = emptyList(),
        /** Shared tree id to the id of the tree on this phone that holds it. */
        val joined: Map<String, String> = emptyMap(),
        /** "sharedTreeId|personId" for each invited person who has asked for the tree. */
        val answered: Set<String> = emptySet(),
    )

    private var state: Stored = load()
    private val flow = MutableStateFlow(state.pending)

    /** Waiting invitations, newest first. */
    val pending: StateFlow<List<PendingInvite>> = flow.asStateFlow()

    /**
     * Keeps an invitation. Ignored (false) if this phone already joined that shared tree, or the same
     * person has already offered it: a second message is the same offer, not a second question.
     */
    @Synchronized
    fun receive(invite: PendingInvite): Boolean {
        if (invite.tree in state.joined) return false
        if (state.pending.any { it.tree == invite.tree && it.from == invite.from }) return false
        save(state.copy(pending = listOf(invite) + state.pending.filter { it.tree != invite.tree }))
        return true
    }

    /** "Not now": the invitation goes away, and the inviter can offer it again. */
    @Synchronized
    fun dismiss(tree: String) {
        if (state.pending.none { it.tree == tree }) return
        save(state.copy(pending = state.pending.filter { it.tree != tree }))
    }

    /** The invitation was accepted: it is no longer waiting, and [localTreeId] now holds that shared tree. */
    @Synchronized
    fun joined(tree: String, localTreeId: String) {
        save(state.copy(pending = state.pending.filter { it.tree != tree }, joined = state.joined + (tree to localTreeId)))
    }

    fun hasJoined(tree: String): Boolean = tree in state.joined

    /** [personId] has asked for [tree], which this phone shares. */
    @Synchronized
    fun answered(tree: String, personId: String) {
        val key = "$tree|$personId"
        if (key !in state.answered) save(state.copy(answered = state.answered + key))
    }

    fun hasAnswered(tree: String, personId: String): Boolean = "$tree|$personId" in state.answered

    private fun save(next: Stored) {
        store.write(json.encodeToString(Stored.serializer(), next))
        state = next
        flow.value = next.pending
    }

    private fun load(): Stored =
        store.read()?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() } ?: Stored()

    private companion object {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    }
}

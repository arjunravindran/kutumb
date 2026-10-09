package com.vibethroughcode.ftree.kutumb.treesync

import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncType

/** An invitation to join the shared tree [tree]: its [name] and roughly how many [people] are in it. */
data class TreeInvite(val tree: String, val name: String, val people: Int)

/** The answer to an invitation: the sender has made a copy of [tree] and wants its history. */
data class TreeJoin(val tree: String)

/**
 * The two messages that take a person from "Asha shared a tree with me" to a copy of it on their phone.
 *
 * `tree-invite` carries `{"tree": "<shared tree id>", "name": "...", "people": 42}` and `tree-join`
 * carries `{"tree": "<shared tree id>"}`. Both are read strictly: a missing or oversized field makes the
 * whole message null, because an invitation is shown to a person and a half-read one would be worse than none.
 * Wire names are stable; never reuse or rename one.
 */
object TreeInviteCodec {
    const val MAX_NAME = 100

    fun invite(invite: TreeInvite) = SyncEnvelope(
        SyncType.TREE_INVITE,
        linkedMapOf("tree" to invite.tree, "name" to invite.name, "people" to invite.people.toLong()),
    )

    fun join(join: TreeJoin) = SyncEnvelope(SyncType.TREE_JOIN, linkedMapOf("tree" to join.tree))

    /** Null unless this is a well-formed `tree-invite`. The name is trimmed; a blank one is refused. */
    fun parseInvite(envelope: SyncEnvelope): TreeInvite? {
        if (envelope.type != SyncType.TREE_INVITE) return null
        val tree = treeId(envelope.payload["tree"]) ?: return null
        val name = (envelope.payload["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME } ?: return null
        val people = (envelope.payload["people"] as? Long)?.takeIf { it in 0..MAX_PEOPLE }?.toInt() ?: return null
        return TreeInvite(tree, name, people)
    }

    /** Null unless this is a well-formed `tree-join`. */
    fun parseJoin(envelope: SyncEnvelope): TreeJoin? {
        if (envelope.type != SyncType.TREE_JOIN) return null
        return TreeJoin(treeId(envelope.payload["tree"]) ?: return null)
    }

    private fun treeId(value: Any?): String? =
        (value as? String)?.takeIf { it.isNotBlank() && it.length <= TreeOpRules.MAX_ID }

    private const val MAX_PEOPLE = 10_000_000L
}

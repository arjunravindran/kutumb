package com.vibethroughcode.ftree.kutumb.treesync

import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TreeInviteCodecTest {
    private val invite = TreeInvite("tree-1", "Ravindran family", 42, from = "asha", you = "ravi")

    @Test fun anInviteSurvivesTheWire() {
        val sent = SyncEnvelope.parse(TreeInviteCodec.invite(invite).toJson())!!
        assertEquals(SyncType.TREE_INVITE, sent.type)
        assertEquals(invite, TreeInviteCodec.parseInvite(sent))
    }

    @Test fun aJoinSurvivesTheWire() {
        val sent = SyncEnvelope.parse(TreeInviteCodec.join(TreeJoin("tree-1")).toJson())!!
        assertEquals(SyncType.TREE_JOIN, sent.type)
        assertEquals(TreeJoin("tree-1"), TreeInviteCodec.parseJoin(sent))
    }

    @Test fun theNameIsTrimmedAndABlankOrTooLongOneRefusesTheWholeInvite() {
        fun with(name: Any?) = SyncEnvelope(SyncType.TREE_INVITE, mapOf("tree" to "t", "name" to name, "people" to 1L, "from" to "a", "you" to "b"))
        assertEquals("Ravindran", TreeInviteCodec.parseInvite(with("  Ravindran "))!!.name)
        assertNull(TreeInviteCodec.parseInvite(with("   ")))
        assertNull(TreeInviteCodec.parseInvite(with("x".repeat(TreeInviteCodec.MAX_NAME + 1))))
        assertNull(TreeInviteCodec.parseInvite(with(7L)))
        assertNull(TreeInviteCodec.parseInvite(with(null)))
    }

    @Test fun aMissingOrOversizedTreeIdOrPeopleCountRefusesTheMessage() {
        fun inv(tree: Any?, people: Any?) = SyncEnvelope(SyncType.TREE_INVITE, mapOf("tree" to tree, "name" to "n", "people" to people, "from" to "a", "you" to "b"))
        assertNull(TreeInviteCodec.parseInvite(inv(null, 1L)))
        assertNull(TreeInviteCodec.parseInvite(inv("", 1L)))
        assertNull(TreeInviteCodec.parseInvite(inv("x".repeat(TreeOpRules.MAX_ID + 1), 1L)))
        assertNull(TreeInviteCodec.parseInvite(inv("t", null)))
        assertNull(TreeInviteCodec.parseInvite(inv("t", -1L)))
        assertNull(TreeInviteCodec.parseInvite(inv("t", "many")))
        assertNull(TreeInviteCodec.parseJoin(SyncEnvelope(SyncType.TREE_JOIN, emptyMap())))
    }

    @Test fun bothPeopleMustBeNamedAndBeDifferent() {
        fun with(from: Any?, you: Any?) =
            SyncEnvelope(SyncType.TREE_INVITE, mapOf("tree" to "t", "name" to "n", "people" to 1L, "from" to from, "you" to you))
        assertEquals(TreeInvite("t", "n", 1, "a", "b"), TreeInviteCodec.parseInvite(with("a", "b")))
        assertNull(TreeInviteCodec.parseInvite(with(null, "b")))
        assertNull(TreeInviteCodec.parseInvite(with("a", null)))
        assertNull(TreeInviteCodec.parseInvite(with("", "b")))
        assertNull(TreeInviteCodec.parseInvite(with("a", 5L)))
        assertNull(TreeInviteCodec.parseInvite(with("x".repeat(TreeOpRules.MAX_ID + 1), "b")))
        assertNull("nobody invites themselves", TreeInviteCodec.parseInvite(with("a", "a")))
    }

    @Test fun eachParserOnlyReadsItsOwnType() {
        assertNull(TreeInviteCodec.parseInvite(TreeInviteCodec.join(TreeJoin("t"))))
        assertNull(TreeInviteCodec.parseJoin(TreeInviteCodec.invite(invite)))
        assertNull(TreeInviteCodec.parseInvite(SyncEnvelope(SyncType.TREE_EDIT, emptyMap())))
    }
}

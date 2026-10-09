package com.vibethroughcode.ftree.kutumb.treesync

import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TreeInviteCodecTest {
    private val invite = TreeInvite("tree-1", "Ravindran family", 42)

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
        fun with(name: Any?) = SyncEnvelope(SyncType.TREE_INVITE, mapOf("tree" to "t", "name" to name, "people" to 1L))
        assertEquals("Ravindran", TreeInviteCodec.parseInvite(with("  Ravindran "))!!.name)
        assertNull(TreeInviteCodec.parseInvite(with("   ")))
        assertNull(TreeInviteCodec.parseInvite(with("x".repeat(TreeInviteCodec.MAX_NAME + 1))))
        assertNull(TreeInviteCodec.parseInvite(with(7L)))
        assertNull(TreeInviteCodec.parseInvite(with(null)))
    }

    @Test fun aMissingOrOversizedTreeIdOrPeopleCountRefusesTheMessage() {
        fun inv(tree: Any?, people: Any?) = SyncEnvelope(SyncType.TREE_INVITE, mapOf("tree" to tree, "name" to "n", "people" to people))
        assertNull(TreeInviteCodec.parseInvite(inv(null, 1L)))
        assertNull(TreeInviteCodec.parseInvite(inv("", 1L)))
        assertNull(TreeInviteCodec.parseInvite(inv("x".repeat(TreeOpRules.MAX_ID + 1), 1L)))
        assertNull(TreeInviteCodec.parseInvite(inv("t", null)))
        assertNull(TreeInviteCodec.parseInvite(inv("t", -1L)))
        assertNull(TreeInviteCodec.parseInvite(inv("t", "many")))
        assertNull(TreeInviteCodec.parseJoin(SyncEnvelope(SyncType.TREE_JOIN, emptyMap())))
    }

    @Test fun eachParserOnlyReadsItsOwnType() {
        assertNull(TreeInviteCodec.parseInvite(TreeInviteCodec.join(TreeJoin("t"))))
        assertNull(TreeInviteCodec.parseJoin(TreeInviteCodec.invite(invite)))
        assertNull(TreeInviteCodec.parseInvite(SyncEnvelope(SyncType.TREE_EDIT, emptyMap())))
    }
}

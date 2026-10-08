package com.vibethroughcode.ftree.kutumb.treesync

import com.vibethroughcode.ftree.kutumb.sync.Json
import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeOpCodecTest {
    private val at = Hlc(1_700_000_000_000, 2, "phone-a")
    private val edge = EdgeKey.of(EdgeKey.PARENT, "p1", "p2")

    private val everyKind: List<TreeOp> = listOf(
        TreeOp.PersonAdd("1", at, "p1"),
        TreeOp.PersonDelete("2", at, "p1"),
        TreeOp.PersonRestore("3", at, "p1"),
        TreeOp.PersonSet("4", at, "p1", PersonField.NAME, "Asha K"),
        TreeOp.PersonSet("5", at, "p1", PersonField.NOTES, null),
        TreeOp.EdgeAdd("6", at, edge, "BIOLOGICAL"),
        TreeOp.EdgeAdd("7", at, EdgeKey.of(EdgeKey.SPOUSE, "p2", "p1"), null),
        TreeOp.EdgeRemove("8", at, edge),
        TreeOp.EdgeSubtype("9", at, edge, "ADOPTIVE"),
        TreeOp.EdgeSubtype("10", at, edge, null),
    )

    @Test fun everyKindOfOperationSurvivesTheWire() {
        for (op in everyKind) assertEquals(op, TreeOpCodec.decode(TreeOpCodec.encode(op)))
    }

    @Test fun aWholeBatchSurvivesBeingSentAsAnEnvelope() {
        val envelope = SyncEnvelope(SyncType.TREE_EDIT, TreeOpCodec.payload("tree-1", everyKind))
        val received = SyncEnvelope.parse(envelope.toJson())!!
        assertEquals(SyncType.TREE_EDIT, received.type)
        val decoded = TreeOpCodec.parsePayload(received.payload)!!
        assertEquals("tree-1", decoded.tree)
        assertEquals(everyKind, decoded.ops)
        assertEquals(0, decoded.skipped)
    }

    @Test fun aSymmetricEdgeWrittenTheWrongWayRoundIsReadBackCanonical() {
        val m = TreeOpCodec.encode(TreeOp.EdgeAdd("1", at, EdgeKey.of(EdgeKey.SPOUSE, "a", "b"), null)).toMutableMap()
        m["from"] = "b"; m["to"] = "a"
        assertEquals(EdgeKey.of(EdgeKey.SPOUSE, "a", "b"), (TreeOpCodec.decode(m) as TreeOp.EdgeAdd).edge)
    }

    @Test fun anOperationFromANewerReleaseIsSkippedWithoutSpoilingTheRest() {
        val good = TreeOpCodec.encode(TreeOp.PersonAdd("1", at, "p1"))
        val newer = mapOf("id" to "2", "hlc" to at.format(), "op" to "person.merge", "from" to "a", "into" to "b")
        val payload = mapOf("tree" to "t", "ops" to listOf(good, newer, "junk", mapOf("op" to "person.add")))
        val decoded = TreeOpCodec.parsePayload(payload)!!
        assertEquals(1, decoded.ops.size)
        assertEquals(3, decoded.skipped)
    }

    @Test fun malformedFieldsAreRejectedNotCoerced() {
        fun bad(change: (MutableMap<String, Any?>) -> Unit, op: TreeOp = TreeOp.PersonSet("1", at, "p", PersonField.NAME, "x")) {
            val m = TreeOpCodec.encode(op).toMutableMap(); change(m); assertNull(m.toString(), TreeOpCodec.decode(m))
        }
        bad({ it["hlc"] = "garbage" })
        bad({ it["hlc"] = 5L })
        bad({ it["id"] = 7L })
        bad({ it["field"] = "shoeSize" })
        bad({ it.remove("value") })
        bad({ it["value"] = 12L })
        bad({ it["person"] = null })
        bad({ it["subtype"] = 3L }, TreeOp.EdgeAdd("1", at, edge, null))
        bad({ it.remove("from") }, TreeOp.EdgeRemove("1", at, edge))
    }

    @Test fun aPayloadThatIsNotATreeEditIsRefused() {
        assertNull(TreeOpCodec.parsePayload(mapOf("ops" to emptyList<Any>())))
        assertNull(TreeOpCodec.parsePayload(mapOf("tree" to "", "ops" to emptyList<Any>())))
        assertNull(TreeOpCodec.parsePayload(mapOf("tree" to "t")))
        assertNull(TreeOpCodec.parsePayload(mapOf("tree" to "t", "ops" to "nope")))
        assertNotNull(TreeOpCodec.parsePayload(mapOf("tree" to "t", "ops" to emptyList<Any>())))
    }

    @Test fun anOversizedBatchIsRefusedOnBothEnds() {
        val many = List(TreeOpCodec.MAX_OPS_PER_MESSAGE + 1) { TreeOp.PersonAdd("op$it", at, "p") }
        assertTrue(runCatching { TreeOpCodec.payload("t", many) }.isFailure)
        val raw = mapOf("tree" to "t", "ops" to many.map(TreeOpCodec::encode))
        assertNull(TreeOpCodec.parsePayload(raw))
    }

    @Test fun theReadingTravelsAsATextStampNotANumber() {
        // JSON numbers are doubles to many readers; a millisecond clock plus counter must not lose digits.
        val text = Json.write(TreeOpCodec.encode(TreeOp.PersonAdd("1", at, "p")))
        assertTrue(text.contains("\"hlc\":\"1700000000000.2.phone-a\""))
    }

    @Test fun anOperationSurvivesBeingStoredAsText() {
        for (op in everyKind) assertEquals(op, TreeOpCodec.fromJson(TreeOpCodec.toJson(op)))
        assertNull(TreeOpCodec.fromJson("not json"))
        assertNull(TreeOpCodec.fromJson("[1,2]"))
        assertNull(TreeOpCodec.fromJson("{\"op\":\"person.merge\"}"))
    }
}

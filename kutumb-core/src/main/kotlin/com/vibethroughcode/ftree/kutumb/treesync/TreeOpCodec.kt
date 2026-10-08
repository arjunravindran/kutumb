package com.vibethroughcode.ftree.kutumb.treesync

/**
 * The `tree-edit` payload: `{"tree": "<shared tree id>", "ops": [ {...}, ... ]}`.
 *
 * Each operation is a flat object with its `id`, its `hlc` and an `op` name, plus the fields that op
 * needs. An operation this build does not recognise (a newer release's) is skipped and counted, never
 * allowed to spoil the rest of the batch. Wire names are stable; never reuse or rename one.
 */
object TreeOpCodec {
    /** The most operations one message may carry; a longer list is refused rather than half-read. */
    const val MAX_OPS_PER_MESSAGE = 500

    data class Decoded(val tree: String, val ops: List<TreeOp>, val skipped: Int)

    fun payload(tree: String, ops: List<TreeOp>): Map<String, Any?> {
        require(ops.size <= MAX_OPS_PER_MESSAGE) { "at most $MAX_OPS_PER_MESSAGE operations per message" }
        return linkedMapOf("tree" to tree, "ops" to ops.map(::encode))
    }

    fun encode(op: TreeOp): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("id" to op.id, "hlc" to op.hlc.format())
        when (op) {
            is TreeOp.PersonAdd -> { m["op"] = "person.add"; m["person"] = op.personId }
            is TreeOp.PersonDelete -> { m["op"] = "person.delete"; m["person"] = op.personId }
            is TreeOp.PersonRestore -> { m["op"] = "person.restore"; m["person"] = op.personId }
            is TreeOp.PersonSet -> {
                m["op"] = "person.set"; m["person"] = op.personId
                m["field"] = op.field.wire; m["value"] = op.value
            }
            is TreeOp.EdgeAdd -> { m["op"] = "edge.add"; putEdge(m, op.edge); m["subtype"] = op.subtype }
            is TreeOp.EdgeRemove -> { m["op"] = "edge.remove"; putEdge(m, op.edge) }
            is TreeOp.EdgeSubtype -> { m["op"] = "edge.subtype"; putEdge(m, op.edge); m["subtype"] = op.subtype }
        }
        return m
    }

    /** One operation as the text stored in the operation log. */
    fun toJson(op: TreeOp): String = com.vibethroughcode.ftree.kutumb.sync.Json.write(encode(op))

    /** Null for text that is not a well-formed operation of a kind this build knows. */
    fun fromJson(text: String): TreeOp? =
        (com.vibethroughcode.ftree.kutumb.sync.Json.parseOrNull(text) as? Map<*, *>)?.let(::decode)

    /** Null when the payload is not a tree-edit at all (no tree id, no list of operations, too many). */
    fun parsePayload(payload: Map<String, Any?>): Decoded? {
        val tree = (payload["tree"] as? String)?.takeIf { it.isNotBlank() && it.length <= TreeOpRules.MAX_ID } ?: return null
        val raw = payload["ops"] as? List<*> ?: return null
        if (raw.size > MAX_OPS_PER_MESSAGE) return null
        val ops = ArrayList<TreeOp>(raw.size)
        var skipped = 0
        for (item in raw) {
            val op = (item as? Map<*, *>)?.let(::decode)
            if (op == null) skipped++ else ops += op
        }
        return Decoded(tree, ops, skipped)
    }

    /** Null for an operation that is malformed or of a kind this build does not know. */
    fun decode(m: Map<*, *>): TreeOp? {
        val id = m["id"] as? String ?: return null
        val hlc = (m["hlc"] as? String)?.let(Hlc::parse) ?: return null
        return when (m["op"] as? String) {
            "person.add" -> TreeOp.PersonAdd(id, hlc, m["person"] as? String ?: return null)
            "person.delete" -> TreeOp.PersonDelete(id, hlc, m["person"] as? String ?: return null)
            "person.restore" -> TreeOp.PersonRestore(id, hlc, m["person"] as? String ?: return null)
            "person.set" -> {
                val field = (m["field"] as? String)?.let(PersonField::fromWire) ?: return null
                if ("value" !in m) return null
                val value = m["value"]
                if (value != null && value !is String) return null
                TreeOp.PersonSet(id, hlc, m["person"] as? String ?: return null, field, value)
            }
            "edge.add" -> TreeOp.EdgeAdd(id, hlc, edge(m) ?: return null, (subtype(m) ?: return null).value)
            "edge.remove" -> TreeOp.EdgeRemove(id, hlc, edge(m) ?: return null)
            "edge.subtype" -> TreeOp.EdgeSubtype(id, hlc, edge(m) ?: return null, (subtype(m) ?: return null).value)
            else -> null
        }
    }

    private fun putEdge(m: MutableMap<String, Any?>, e: EdgeKey) {
        m["type"] = e.type; m["from"] = e.from; m["to"] = e.to
    }

    private fun edge(m: Map<*, *>): EdgeKey? {
        val type = m["type"] as? String ?: return null
        val from = m["from"] as? String ?: return null
        val to = m["to"] as? String ?: return null
        return EdgeKey.of(type, from, to)
    }

    /** Null means the subtype is the wrong type; a [Sub] with a null value means absent or cleared. */
    private fun subtype(m: Map<*, *>): Sub? {
        val s = m["subtype"]
        return if (s == null || s is String) Sub(s) else null
    }

    private class Sub(val value: String?)
}

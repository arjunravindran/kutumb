package com.vibethroughcode.ftree.kutumb.treesync

/** A value and the reading that wrote it. The latest reading wins; see [wins]. */
data class Reg<T>(val hlc: Hlc, val value: T)

/** What [TreeReplica.apply] did with one operation. */
sealed interface Verdict {
    /** The operation changed the tree. */
    data object Applied : Verdict

    /** A replay, or older than what is already held: harmless, nothing changed. */
    data object NoChange : Verdict

    /**
     * Stamped further ahead of this phone's clock than it will accept. Keep it and offer it again
     * later; it is not lost, and applying it now would let one wrong clock win every field.
     */
    data object Deferred : Verdict

    /** Refused outright: a malformed or oversized operation. Never retry. */
    data class Invalid(val reason: InvalidReason) : Verdict
}

/** Why a relationship that someone recorded is not in the tree you see. */
enum class HeldReason {
    /** Drawing it would make somebody their own ancestor. The newest edge in the loop is held. */
    CYCLE,

    /** It names a person this phone has not heard of yet. It appears when that person arrives. */
    MISSING_PERSON,
}

/** Something the family should look at, kept visible rather than silently dropped. */
sealed interface InboxItem {
    /** The person was deleted, then someone edited them anyway. Restore them to keep the edit. */
    data class EditAfterDelete(val personId: String, val fields: Set<PersonField>, val deletedAt: Hlc) : InboxItem

    data class RelationshipHeld(val edge: EdgeKey, val reason: HeldReason) : InboxItem
}

data class PersonView(val id: String, val fields: Map<PersonField, String>)
data class EdgeView(val edge: EdgeKey, val subtype: String?)

/** The tree as people see it. A pure function of the replica's state, sorted so equal states compare equal. */
data class TreeView(
    val people: List<PersonView>,
    val edges: List<EdgeView>,
    val inbox: List<InboxItem>,
)

data class PersonSnap(val alive: Reg<Boolean>?, val fields: Map<PersonField, Reg<String?>>)
data class EdgeSnap(val presence: Reg<Boolean>?, val subtype: Reg<String?>?)

/** Everything a replica knows, immutable and comparable. Two replicas hold the same tree iff these are equal. */
data class Snapshot(val people: Map<String, PersonSnap>, val edges: Map<EdgeKey, EdgeSnap>)

/**
 * One phone's copy of a shared tree.
 *
 * The state is a set of last-writer-wins registers: whether each person exists, each of their fields,
 * whether each relationship exists and its subtype. Applying an operation can only move a register to
 * a later reading, which is commutative, associative and idempotent, so **phones that have seen the
 * same operations hold the same state whatever order they arrived in, and however often**. Everything
 * that needs the whole tree to decide (a deleted person, a loop of parents) is worked out afterwards
 * in [view], from the state alone, so it cannot differ between phones either.
 */
class TreeReplica(private val maxFutureMs: Long = HybridClock.DEFAULT_MAX_FUTURE_MS) {
    private class PersonRegs {
        var alive: Reg<Boolean>? = null
        val fields = HashMap<PersonField, Reg<String?>>()
    }

    private class EdgeRegs {
        var presence: Reg<Boolean>? = null
        var subtype: Reg<String?>? = null
    }

    private val people = HashMap<String, PersonRegs>()
    private val edges = HashMap<EdgeKey, EdgeRegs>()

    /** [nowMs] is this phone's wall clock, used only to refuse readings from the far future. */
    fun apply(op: TreeOp, nowMs: Long): Verdict {
        TreeOpRules.check(op)?.let { return Verdict.Invalid(it) }
        if (op.hlc.time > nowMs + maxFutureMs) return Verdict.Deferred
        val changed = when (op) {
            is TreeOp.PersonAdd -> setAlive(op.personId, op.hlc, true)
            is TreeOp.PersonRestore -> setAlive(op.personId, op.hlc, true)
            is TreeOp.PersonDelete -> setAlive(op.personId, op.hlc, false)
            is TreeOp.PersonSet -> setField(op.personId, op.field, Reg(op.hlc, op.value))
            is TreeOp.EdgeAdd -> {
                val a = setPresence(op.edge, op.hlc, true)
                val b = setSubtype(op.edge, Reg(op.hlc, op.subtype))
                a || b
            }
            is TreeOp.EdgeRemove -> setPresence(op.edge, op.hlc, false)
            is TreeOp.EdgeSubtype -> setSubtype(op.edge, Reg(op.hlc, op.subtype))
        }
        return if (changed) Verdict.Applied else Verdict.NoChange
    }

    fun applyAll(ops: Iterable<TreeOp>, nowMs: Long): List<Verdict> = ops.map { apply(it, nowMs) }

    /**
     * Folds another replica's whole state into this one, register by register. This is how a phone that
     * has been away catches up from a snapshot, and it gives the same result as replaying the
     * operations that built that snapshot.
     */
    fun join(other: Snapshot) {
        for ((id, p) in other.people) {
            p.alive?.let { setAlive(id, it.hlc, it.value) }
            for ((field, reg) in p.fields) setField(id, field, reg)
        }
        for ((key, e) in other.edges) {
            e.presence?.let { setPresence(key, it.hlc, it.value) }
            e.subtype?.let { setSubtype(key, it) }
        }
    }

    /** People this replica has a verdict on (shown or deleted), as opposed to ones only mentioned in passing. */
    fun knownPeople(): Set<String> = people.filter { it.value.alive != null }.keys.toSet()

    /** Edges this replica has a verdict on, present or removed. */
    fun knownEdges(): Set<EdgeKey> = edges.filter { it.value.presence != null }.keys.toSet()

    /** True when the registers say this edge exists, whether or not the view currently shows it. */
    fun edgeRecordedAsPresent(edge: EdgeKey): Boolean = edges[edge]?.presence?.value == true

    fun snapshot(): Snapshot = Snapshot(
        people = people.mapValues { (_, p) -> PersonSnap(p.alive, HashMap(p.fields)) },
        edges = edges.mapValues { (_, e) -> EdgeSnap(e.presence, e.subtype) },
    )

    // --- registers ---------------------------------------------------------------------------------

    private fun setAlive(id: String, hlc: Hlc, alive: Boolean): Boolean {
        val p = people.getOrPut(id) { PersonRegs() }
        val next = Reg(hlc, alive)
        if (!wins(next, p.alive)) return false
        p.alive = next
        return true
    }

    private fun setField(id: String, field: PersonField, next: Reg<String?>): Boolean {
        val p = people.getOrPut(id) { PersonRegs() }
        if (!wins(next, p.fields[field])) return false
        p.fields[field] = next
        return true
    }

    private fun setPresence(key: EdgeKey, hlc: Hlc, present: Boolean): Boolean {
        val e = edges.getOrPut(key) { EdgeRegs() }
        val next = Reg(hlc, present)
        if (!wins(next, e.presence)) return false
        e.presence = next
        return true
    }

    private fun setSubtype(key: EdgeKey, next: Reg<String?>): Boolean {
        val e = edges.getOrPut(key) { EdgeRegs() }
        if (!wins(next, e.subtype)) return false
        e.subtype = next
        return true
    }

    // --- the view ----------------------------------------------------------------------------------

    /** The tree as people see it, plus what needs a human's eye. Deterministic in the state alone. */
    fun view(): TreeView {
        val visible = people.filter { it.value.alive?.value == true }
        val peopleView = visible.entries.sortedBy { it.key }.map { (id, p) ->
            PersonView(id, p.fields.entries.filter { it.value.value != null }
                .sortedBy { it.key.ordinal }.associate { it.key to it.value.value!! })
        }

        val inbox = ArrayList<InboxItem>()
        for ((id, p) in people.entries.sortedBy { it.key }) {
            val deleted = p.alive?.takeIf { !it.value } ?: continue
            val later = p.fields.filter { it.value.hlc > deleted.hlc }.keys
            if (later.isNotEmpty()) inbox += InboxItem.EditAfterDelete(id, later.sortedBy { it.ordinal }.toSet(), deleted.hlc)
        }

        val candidates = ArrayList<Pair<EdgeKey, EdgeRegs>>()
        for ((key, e) in edges.entries.sortedWith(compareBy({ it.key.type }, { it.key.from }, { it.key.to }))) {
            if (e.presence?.value != true) continue
            val fromOk = key.from in visible
            val toOk = key.to in visible
            if (fromOk && toOk) { candidates += key to e; continue }
            // A deleted endpoint is the family's own doing. An endpoint nobody has told us about yet is not.
            if (unknown(key.from) || unknown(key.to)) inbox += InboxItem.RelationshipHeld(key, HeldReason.MISSING_PERSON)
        }

        val held = breakParentLoops(candidates)
        for (key in held) inbox += InboxItem.RelationshipHeld(key, HeldReason.CYCLE)

        val edgesView = candidates.filter { it.first !in held }
            .map { (key, e) -> EdgeView(key, e.subtype?.value) }
        return TreeView(peopleView, edgesView, inbox)
    }

    private fun unknown(id: String) = people[id]?.alive == null

    /**
     * While the parent edges contain a loop, holds the newest edge in it. The search runs over sorted
     * ids, so the same state always gives the same answer on every phone.
     */
    private fun breakParentLoops(candidates: List<Pair<EdgeKey, EdgeRegs>>): Set<EdgeKey> {
        val parents = candidates.filter { it.first.type == EdgeKey.PARENT }
        val hlcOf = parents.associate { it.first to it.second.presence!!.hlc }
        val live = parents.map { it.first }.toMutableSet()
        val held = LinkedHashSet<EdgeKey>()
        while (true) {
            val cycle = findCycle(live) ?: return held
            val newest = cycle.maxWith(compareBy<EdgeKey>({ hlcOf.getValue(it) }, { it.from }, { it.to }))
            live.remove(newest)
            held += newest
        }
    }

    private fun findCycle(edgeSet: Set<EdgeKey>): List<EdgeKey>? {
        val out = HashMap<String, MutableList<EdgeKey>>()
        for (e in edgeSet.sortedWith(compareBy({ it.from }, { it.to }))) out.getOrPut(e.from) { ArrayList() }.add(e)
        val done = HashSet<String>()
        for (start in out.keys.sorted()) {
            if (start in done) continue
            val path = arrayListOf(start)
            val next = arrayListOf(0)
            val pathEdges = ArrayList<EdgeKey>()
            val onPath = hashSetOf(start)
            while (path.isNotEmpty()) {
                val node = path[path.lastIndex]
                val i = next[next.lastIndex]
                val edgesOut = out[node].orEmpty()
                if (i >= edgesOut.size) {
                    onPath.remove(node); done += node
                    path.removeAt(path.lastIndex); next.removeAt(next.lastIndex)
                    if (pathEdges.isNotEmpty()) pathEdges.removeAt(pathEdges.lastIndex)
                    continue
                }
                next[next.lastIndex] = i + 1
                val edge = edgesOut[i]
                val child = edge.to
                if (child in onPath) {
                    return pathEdges.subList(path.indexOf(child), pathEdges.size) + edge
                }
                if (child in done) continue
                path.add(child); next.add(0); pathEdges.add(edge); onPath.add(child)
            }
        }
        return null
    }

    private companion object {
        /** True when [next] should replace [current]: a later reading, or on an exact tie the larger value, so ties are order-free too. */
        fun <T> wins(next: Reg<T>, current: Reg<T>?): Boolean {
            if (current == null) return true
            val c = next.hlc.compareTo(current.hlc)
            if (c != 0) return c > 0
            return next.value.toString() > current.value.toString()
        }
    }
}

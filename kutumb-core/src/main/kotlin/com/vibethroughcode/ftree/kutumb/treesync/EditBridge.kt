package com.vibethroughcode.ftree.kutumb.treesync

/**
 * The synced part of a person: everything except the photograph, which stays on the phone that added
 * it (photos are a later phase). The defaults are what an unknown placeholder looks like, which is
 * also what a person with no registers set yet looks like, so "nothing recorded" and "recorded as
 * empty" are the same thing to both ends.
 */
data class PersonData(
    val name: String? = null,
    val gender: String = "UNSPECIFIED",
    val birthDate: String? = null,
    val deathDate: String? = null,
    val deceased: Boolean = false,
    val notes: String? = null,
) {
    fun fields(): Map<PersonField, String?> = linkedMapOf(
        PersonField.NAME to name,
        PersonField.GENDER to gender,
        PersonField.BIRTH_DATE to birthDate,
        PersonField.DEATH_DATE to deathDate,
        PersonField.DECEASED to deceased.toString(),
        PersonField.NOTES to notes,
    )

    companion object {
        val EMPTY = PersonData()

        fun fromFields(fields: Map<PersonField, String>) = PersonData(
            name = fields[PersonField.NAME],
            gender = fields[PersonField.GENDER] ?: EMPTY.gender,
            birthDate = fields[PersonField.BIRTH_DATE],
            deathDate = fields[PersonField.DEATH_DATE],
            deceased = fields[PersonField.DECEASED] == "true",
            notes = fields[PersonField.NOTES],
        )
    }
}

/**
 * Turns what the user just did into operations. Each call stamps every operation with its own clock
 * reading, in order, so a batch from one edit sorts as it was made.
 *
 * Callers pass the person's data from *before* the edit, read inside the same transaction that writes,
 * so the diff is against what this phone actually held. Only the fields that changed are sent; that is
 * what lets two people edit different fields of one person without overwriting each other.
 */
class EditRecorder(private val clock: HybridClock, private val newOpId: () -> String) {
    fun personAdded(personId: String, data: PersonData): List<TreeOp> =
        listOf<TreeOp>(TreeOp.PersonAdd(newOpId(), clock.tick(), personId)) + changes(personId, PersonData.EMPTY, data)

    fun personChanged(personId: String, before: PersonData, after: PersonData): List<TreeOp> =
        changes(personId, before, after)

    /** "Keep as unknown": the person stays, everything known about them goes. */
    fun personCleared(personId: String, before: PersonData): List<TreeOp> =
        changes(personId, before, PersonData.EMPTY)

    fun personDeleted(personId: String): List<TreeOp> =
        listOf(TreeOp.PersonDelete(newOpId(), clock.tick(), personId))

    fun edgeAdded(edge: EdgeKey, subtype: String?): List<TreeOp> =
        listOf(TreeOp.EdgeAdd(newOpId(), clock.tick(), edge, subtype))

    fun edgeRemoved(edge: EdgeKey): List<TreeOp> =
        listOf(TreeOp.EdgeRemove(newOpId(), clock.tick(), edge))

    fun edgeSubtypeChanged(edge: EdgeKey, subtype: String?): List<TreeOp> =
        listOf(TreeOp.EdgeSubtype(newOpId(), clock.tick(), edge, subtype))

    private fun changes(personId: String, before: PersonData, after: PersonData): List<TreeOp> {
        val was = before.fields()
        return after.fields().entries
            .filter { (field, value) -> was[field] != value }
            .map { (field, value) -> TreeOp.PersonSet(newOpId(), clock.tick(), personId, field, value) }
    }
}

/** What this phone's database holds for the shared tree, reduced to the synced parts. */
data class DbTree(val people: Map<String, PersonData>, val edges: Map<EdgeKey, String?>)

/** One change to make to the database so that it shows the same tree as [TreeReplica.view]. */
sealed interface ProjectionStep {
    data class InsertPerson(val id: String, val data: PersonData) : ProjectionStep
    data class UpdatePerson(val id: String, val data: PersonData) : ProjectionStep
    data class DeletePerson(val id: String) : ProjectionStep
    data class InsertEdge(val edge: EdgeKey, val subtype: String?) : ProjectionStep
    data class SetEdgeSubtype(val edge: EdgeKey, val subtype: String?) : ProjectionStep
    data class DeleteEdge(val edge: EdgeKey) : ProjectionStep
}

/**
 * Works out how to make the database match the shared tree after operations arrive from other
 * phones. The steps come back in an order that is safe to run as written: people go in before the
 * edges that need them, and edges go before the people they hang from.
 *
 * Only things the replica **knows about** are ever removed. A person or edge in the database that the
 * replica has never heard of is left alone, because deleting a relative that simply was not shared yet
 * would be the worst bug this feature could have.
 */
object Projection {
    fun plan(replica: TreeReplica, db: DbTree): List<ProjectionStep> {
        val view = replica.view()
        val knownPeople = replica.knownPeople()
        val knownEdges = replica.knownEdges()
        val want = view.people.associate { it.id to PersonData.fromFields(it.fields) }
        val wantEdges = view.edges.associate { it.edge to it.subtype }

        val insertPeople = ArrayList<ProjectionStep>()
        val updatePeople = ArrayList<ProjectionStep>()
        val deletePeople = ArrayList<ProjectionStep>()
        for ((id, data) in want.entries.sortedBy { it.key }) {
            val have = db.people[id]
            when {
                have == null -> insertPeople += ProjectionStep.InsertPerson(id, data)
                have != data -> updatePeople += ProjectionStep.UpdatePerson(id, data)
            }
        }
        for (id in db.people.keys.sorted()) {
            if (id !in want && id in knownPeople) deletePeople += ProjectionStep.DeletePerson(id)
        }

        val insertEdges = ArrayList<ProjectionStep>()
        val subtypeEdges = ArrayList<ProjectionStep>()
        val deleteEdges = ArrayList<ProjectionStep>()
        val order = compareBy<EdgeKey>({ it.type }, { it.from }, { it.to })
        for ((edge, subtype) in wantEdges.entries.sortedWith(compareBy(order) { it.key })) {
            if (edge !in db.edges) insertEdges += ProjectionStep.InsertEdge(edge, subtype)
            else if (db.edges[edge] != subtype) subtypeEdges += ProjectionStep.SetEdgeSubtype(edge, subtype)
        }
        for (edge in db.edges.keys.sortedWith(order)) {
            if (edge !in wantEdges && edge in knownEdges) deleteEdges += ProjectionStep.DeleteEdge(edge)
        }

        return insertPeople + updatePeople + insertEdges + subtypeEdges + deleteEdges + deletePeople
    }
}

/**
 * Notices what the database holds that the shared tree does not, and says so as operations.
 *
 * This is the other direction of [Projection]: projection makes the database match the tree after other
 * phones' changes arrive; the reconciler makes the tree match the database after **this** phone's user
 * changed it. Because it compares states rather than watching calls, every way of writing the database
 * is covered with no hook to forget: an edit, a delete, a reset to unknown, an import, a seed, and the
 * first share of a tree that already exists (where every person is new to the registers).
 *
 * It is idempotent (run twice, the second finds nothing) and safe to run whenever, including at start-up
 * after a crash between a write and its recording.
 *
 * What it deliberately does not do:
 * - **Delete a person's edges when the person goes.** Removing a person leaves their relationships in
 *   the tree; they are not shown while the person is gone, and return if the person is restored.
 * - **Resurrect or overrule what the registers say about someone already deleted.** If the database still
 *   holds a person the tree has deleted, the next projection removes them; they are not re-added.
 */
object Reconciler {
    fun ops(replica: TreeReplica, db: DbTree, recorder: EditRecorder): List<TreeOp> {
        val view = replica.view()
        val shown = view.people.associate { it.id to PersonData.fromFields(it.fields) }
        val shownEdges = view.edges.associate { it.edge to it.subtype }
        val knownPeople = replica.knownPeople()
        val knownEdges = replica.knownEdges()
        val out = ArrayList<TreeOp>()

        for ((id, data) in db.people.entries.sortedBy { it.key }) {
            val was = shown[id]
            when {
                id !in knownPeople -> out += recorder.personAdded(id, data)
                was != null && was != data -> out += recorder.personChanged(id, was, data)
            }
        }
        for (id in shown.keys.sorted()) {
            if (id !in db.people) out += recorder.personDeleted(id)
        }

        val order = compareBy<EdgeKey>({ it.type }, { it.from }, { it.to })
        for ((edge, subtype) in db.edges.entries.sortedWith(compareBy(order) { it.key })) {
            when {
                edge !in knownEdges -> out += recorder.edgeAdded(edge, subtype)
                edge in shownEdges -> if (shownEdges[edge] != subtype) out += recorder.edgeSubtypeChanged(edge, subtype)
                // Known, not shown, and not recorded as present: it was removed and the user has put it back.
                // Recorded as present but not shown means held back (a loop, a missing person): leave it.
                !replica.edgeRecordedAsPresent(edge) -> out += recorder.edgeAdded(edge, subtype)
            }
        }
        for (edge in shownEdges.keys.sortedWith(order)) {
            // An edge that vanished because one of its people did is the person's doing, not a removal.
            if (edge !in db.edges && edge.from in db.people && edge.to in db.people) out += recorder.edgeRemoved(edge)
        }
        return out
    }
}

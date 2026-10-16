package com.vibethroughcode.ftree.data

import androidx.room.withTransaction
import com.vibethroughcode.ftree.graph.Circle
import com.vibethroughcode.ftree.graph.FamilyGraph
import com.vibethroughcode.ftree.graph.FamilySnapshot
import com.vibethroughcode.ftree.graph.RelationshipCheck
import com.vibethroughcode.ftree.graph.RelationshipRejection
import com.vibethroughcode.ftree.graph.RelationshipRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** What to do with a person's relationships when the person is removed. */
enum class DeletionMode {
    /**
     * Strip the person back to an unknown placeholder but keep the node and every edge, so the
     * shape of the family survives losing one name.
     */
    KEEP_AS_UNKNOWN,

    /** Remove the person and, by cascade, every edge that touched them. */
    DELETE_COMPLETELY,
}

/**
 * The single door to family data.
 *
 * Reads are `Flow`-based and deliberately narrow — one person, one person's relatives — rather
 * than "load the graph", because the graph is expected to reach thousands of people.
 */
class FamilyRepository(
    private val db: FTreeDatabase,
    private val photos: PhotoStore,
    private val people: PersonDao = db.personDao(),
    private val relationships: RelationshipDao = db.relationshipDao(),
    private val origins: PersonOriginDao = db.personOriginDao(),
) {

    fun observeAllPeople(): Flow<List<Person>> = people.observeAll()
    fun observePerson(id: String): Flow<Person?> = people.observeById(id)
    fun observePersonCount(): Flow<Int> = people.observeCount()
    fun searchPeople(query: String): Flow<List<Person>> = people.search(query)

    fun observeParents(id: String): Flow<List<Person>> = relationships.observeParents(id)
    fun observeChildren(id: String): Flow<List<Person>> = relationships.observeChildren(id)
    fun observeSpouses(id: String): Flow<List<Person>> = relationships.observeSpouses(id)
    fun observeSiblings(id: String): Flow<List<Person>> = relationships.observeSiblings(id)
    fun observeEdgesOf(id: String): Flow<List<Relationship>> = relationships.observeEdgesOf(id)

    /** Every edge. Fine for a Circle, which is small; a family chart reads a neighbourhood instead. */
    fun observeAllEdges(): Flow<List<Relationship>> = relationships.observeAllRelationships()

    suspend fun person(id: String): Person? = people.findById(id)

    /** Who the chart should open on when the user has not chosen. */
    suspend fun mostConnectedPersonId(): String? = people.mostConnectedPersonId()
    suspend fun relationshipCount(id: String): Int = relationships.edgeCount(id)

    suspend fun edgesBetween(a: String, b: String): List<Relationship> = relationships.edgesBetween(a, b)

    private val neighbourhood = NeighbourhoodLoader(people, relationships)

    /** The bounded slice of the tree the chart draws. */
    suspend fun loadNeighbourhood(
        focusId: String,
        generationsUp: Int,
        generationsDown: Int,
    ) = neighbourhood.load(focusId, generationsUp, generationsDown)

    suspend fun ancestorIdsOf(personId: String): Set<String> =
        FamilyGraph.ancestorsOf(personId) { relationships.parentIdsOf(it) }

    suspend fun descendantIdsOf(personId: String): Set<String> =
        FamilyGraph.descendantsOf(personId) { relationships.childIdsOf(it) }

    /** One person's household and the households under it — see [FamilyGraph.branchFrom]. */
    suspend fun branchIdsOf(personId: String): Set<String> = FamilyGraph.branchFrom(
        personId = personId,
        childrenOf = { relationships.childIdsOf(it) },
        spousesOf = { relationships.spouseIdsOfAll(listOf(it)) },
    )

    suspend fun addPerson(person: Person): Person {
        people.insert(person)
        return person
    }

    suspend fun updatePerson(person: Person) {
        people.update(person.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deletePerson(id: String, mode: DeletionMode) {
        // Read the photo before the row goes, so the file can be cleaned up afterwards rather
        // than being left behind as an orphan nothing will ever reference again.
        val photoId = people.findById(id)?.photoId
        db.withTransaction {
            when (mode) {
                DeletionMode.KEEP_AS_UNKNOWN -> people.clearDetails(id)
                DeletionMode.DELETE_COMPLETELY -> people.deleteById(id)
            }
        }
        photos.delete(photoId)
    }

    /**
     * Records an edge if the rules allow it.
     *
     * Validation runs inside the transaction that writes, so two rapid taps cannot both pass the
     * duplicate check. The unique index is the final backstop.
     */
    suspend fun addRelationship(
        fromPersonId: String,
        toPersonId: String,
        type: RelationshipType,
        subtype: String? = null,
    ): Result<Relationship> = db.withTransaction {
        val check = RelationshipRules.check(
            fromPersonId = fromPersonId,
            toPersonId = toPersonId,
            type = type,
            existingEdgeExists = { from, to, edgeType ->
                relationships.countExact(from, to, edgeType) > 0
            },
            parentsOf = { relationships.parentIdsOf(it) },
        )
        when (check) {
            is RelationshipCheck.Rejected -> Result.failure(RelationshipRejectedException(check.reason))
            RelationshipCheck.Allowed -> {
                val edge = Relationship.of(fromPersonId, toPersonId, type, subtype)
                relationships.insert(edge)
                Result.success(edge)
            }
        }
    }

    suspend fun removeRelationship(id: String) = relationships.deleteById(id)

    /** Joins two people in a Circle, with an optional label ("colleague"). Same rules as any edge: no self, no duplicates. */
    suspend fun addConnection(aId: String, bId: String, label: String?): Result<Unit> =
        addRelationship(aId, bId, RelationshipType.CONNECTED, Circle.cleanLabel(label)).map {}

    /** Creates a person and connects them, or neither. */
    suspend fun addNewConnection(anchorId: String, person: Person, label: String?): Result<Person> = runCatching {
        db.withTransaction {
            people.insert(person)
            addConnection(anchorId, person.id, label).getOrThrow()
            person
        }
    }

    suspend fun setConnectionLabel(edgeId: String, label: String?) =
        relationships.setSubtype(edgeId, Circle.cleanLabel(label))

    /**
     * Records a relationship the way the user described it.
     *
     * Adding a sibling is the one case that is not a single edge. If the anchor already has known
     * parents, the new sibling is attached to those same parents — which is what actually makes
     * them siblings, keeps half-siblings and later-added relatives consistent, and avoids inventing
     * a placeholder parent nobody asked for. Only when no parents are known does an explicit
     * sibling edge get written, because that is the one case shared parents cannot express.
     */
    suspend fun addRelative(
        anchorId: String,
        relativeId: String,
        kind: RelativeKind,
    ): Result<Unit> = when (kind) {
        RelativeKind.PARENT -> addRelationship(relativeId, anchorId, RelationshipType.PARENT).map {}
        RelativeKind.CHILD -> addRelationship(anchorId, relativeId, RelationshipType.PARENT).map {}
        RelativeKind.SPOUSE -> addRelationship(anchorId, relativeId, RelationshipType.SPOUSE).map {}
        RelativeKind.SIBLING -> addSibling(anchorId, relativeId)
    }

    private suspend fun addSibling(anchorId: String, siblingId: String): Result<Unit> {
        if (anchorId == siblingId) {
            return Result.failure(RelationshipRejectedException(RelationshipRejection.SELF_REFERENCE))
        }
        val parents = db.withTransaction { relationships.parentIdsOf(anchorId) }
        if (parents.isEmpty()) {
            return addRelationship(anchorId, siblingId, RelationshipType.SIBLING).map {}
        }

        // Attaching to shared parents is what makes them siblings. A parent the sibling already
        // has is not an error, so a duplicate is skipped rather than failing the whole operation.
        var attached = false
        var lastFailure: Throwable? = null
        parents.forEach { parentId ->
            val result = addRelationship(parentId, siblingId, RelationshipType.PARENT)
            result.onSuccess { attached = true }.onFailure { failure ->
                val duplicate = (failure as? RelationshipRejectedException)?.reason ==
                    RelationshipRejection.DUPLICATE
                if (duplicate) attached = true else lastFailure = failure
            }
        }
        return if (attached) Result.success(Unit) else Result.failure(
            lastFailure ?: RelationshipRejectedException(RelationshipRejection.DUPLICATE)
        )
    }

    /**
     * Creates a person and relates them, atomically.
     *
     * Failure anywhere — the insert or the relationship rules — throws out of the transaction so
     * the database rolls the whole thing back. That is stronger than deleting the person again by
     * hand, which would only undo the cases it remembered to, and it means a caller mistake
     * surfaces as a failed Result rather than a half-written tree.
     */
    suspend fun addNewRelative(
        anchorId: String,
        person: Person,
        kind: RelativeKind,
    ): Result<Person> = runCatching {
        db.withTransaction {
            people.insert(person)
            addRelative(anchorId, person.id, kind).getOrThrow()
            person
        }
    }

    /*
     * Relatives more than one link away: uncles, aunts, cousins, nephews, nieces, grandparents, grandchildren.
     *
     * None of these is a link of its own. Each is the ordinary links through somebody in between: an uncle is a
     * brother or sister of a parent, a cousin a child of an uncle or aunt, and so on, so the kinship rules name
     * them as they do anyone. The person at the far end is either made ([relative]) or already in the tree
     * ([existingId]), so somebody added earlier with no connection can be joined up. Where the person in between
     * is not in the tree, an unknown stand-in is made. Everything goes in together or not at all, and a link the
     * rules refuse (somebody's own grandchild as their grandparent) undoes the lot.
     */

    private suspend fun attach(anchorId: String, kind: RelativeKind, relative: Person?, existingId: String?): Person {
        if (existingId != null) {
            addRelative(anchorId, existingId, kind).getOrThrow()
            return people.findById(existingId) ?: throw IllegalArgumentException("No such person")
        }
        return addNewRelative(anchorId, relative ?: Person(), kind).getOrThrow()
    }

    /** An uncle or aunt of [personId]: a brother or sister of the parent [parentId], or of one not in the tree (null). */
    suspend fun addUncleOrAunt(
        personId: String, parentId: String?, relative: Person?, existingId: String? = null,
    ): Result<Person> = runCatching {
        db.withTransaction {
            val parent = parentId ?: addNewRelative(personId, Person(), RelativeKind.PARENT).getOrThrow().id
            attach(parent, RelativeKind.SIBLING, relative, existingId)
        }
    }

    /**
     * A cousin of [personId]: a child of an uncle or aunt. With [auntUncleId] that uncle or aunt is in the tree;
     * without, one is made as an unknown sibling of the parent [parentId] (and that parent, too, if none is given).
     */
    suspend fun addCousin(
        personId: String, auntUncleId: String?, parentId: String?, relative: Person?, existingId: String? = null,
    ): Result<Person> = runCatching {
        db.withTransaction {
            val auntUncle = auntUncleId ?: run {
                val parent = parentId ?: addNewRelative(personId, Person(), RelativeKind.PARENT).getOrThrow().id
                addNewRelative(parent, Person(), RelativeKind.SIBLING).getOrThrow().id
            }
            attach(auntUncle, RelativeKind.CHILD, relative, existingId)
        }
    }

    /** A nephew or niece of [personId]: a child of the brother or sister [siblingId], or of one not in the tree (null). */
    suspend fun addNephewOrNiece(
        personId: String, siblingId: String?, relative: Person?, existingId: String? = null,
    ): Result<Person> = runCatching {
        db.withTransaction {
            val sibling = siblingId ?: addNewRelative(personId, Person(), RelativeKind.SIBLING).getOrThrow().id
            attach(sibling, RelativeKind.CHILD, relative, existingId)
        }
    }

    /** A grandparent of [personId]: a parent of the parent [parentId], or of one not in the tree (null). */
    suspend fun addGrandparent(
        personId: String, parentId: String?, relative: Person?, existingId: String? = null,
    ): Result<Person> = runCatching {
        db.withTransaction {
            val parent = parentId ?: addNewRelative(personId, Person(), RelativeKind.PARENT).getOrThrow().id
            attach(parent, RelativeKind.PARENT, relative, existingId)
        }
    }

    /** A grandchild of [personId]: a child of the child [childId], or of one not in the tree (null). */
    suspend fun addGrandchild(
        personId: String, childId: String?, relative: Person?, existingId: String? = null,
    ): Result<Person> = runCatching {
        db.withTransaction {
            val child = childId ?: addNewRelative(personId, Person(), RelativeKind.CHILD).getOrThrow().id
            attach(child, RelativeKind.CHILD, relative, existingId)
        }
    }

    /**
     * Makes two entries for one person into one: [dropId] is folded into [keepId] and removed.
     *
     * Everything the dropped entry knew that the kept one does not (a name, a birth date, a photograph, notes)
     * moves across, and every link it had is given to the kept one, so a mother added twice ends as one mother
     * with all her children. Links both already had are not doubled. It goes in as one step: if a link would
     * break the family's rules (two entries that are really a parent and a child cannot be one person) nothing
     * is changed and the reason is returned.
     */
    suspend fun mergePeople(keepId: String, dropId: String): Result<Unit> =
        mergePeopleUndoably(keepId, dropId).map { finishMerge(it) }

    /**
     * [mergePeople], but keeping what is needed to take it back. The dropped entry's photograph file is left on
     * disk until [finishMerge] (the undo window closing) so that [undoMerge] can give it back.
     */
    suspend fun mergePeopleUndoably(keepId: String, dropId: String): Result<MergeReceipt> = runCatching {
        if (keepId == dropId) throw RelationshipRejectedException(RelationshipRejection.SELF_REFERENCE)
        db.withTransaction {
            val keep = people.findById(keepId) ?: throw IllegalArgumentException("No such person")
            val drop = people.findById(dropId) ?: throw IllegalArgumentException("No such person")
            val edges = relationships.edgesOf(dropId)

            // One entry cannot be a parent, spouse or sibling of the other and be the same person.
            if (edges.any { it.other(dropId) == keepId }) {
                throw RelationshipRejectedException(RelationshipRejection.CONTRADICTS_EXISTING)
            }

            val notes = when {
                keep.notes.isNullOrBlank() -> drop.notes
                drop.notes.isNullOrBlank() || drop.notes == keep.notes -> keep.notes
                else -> keep.notes + "\n\n" + drop.notes
            }
            val merged = keep.copy(
                name = keep.name?.takeIf { it.isNotBlank() } ?: drop.name,
                gender = if (keep.gender == Gender.UNSPECIFIED) drop.gender else keep.gender,
                birthDate = keep.birthDate ?: drop.birthDate,
                deathDate = keep.deathDate ?: drop.deathDate,
                deceased = keep.deceased || drop.deceased,
                photoId = keep.photoId ?: drop.photoId,
                notes = notes,
                updatedAt = System.currentTimeMillis(),
            )
            people.update(merged)

            // Move the links. Taken off the dropped entry first, so the rules judge the family as it will be.
            edges.forEach { relationships.deleteById(it.id) }
            val added = ArrayList<String>()
            for (edge in edges) {
                val from = if (edge.fromPersonId == dropId) keepId else edge.fromPersonId
                val to = if (edge.toPersonId == dropId) keepId else edge.toPersonId
                val result = addRelationship(from, to, edge.type, edge.subtype)
                result.getOrNull()?.let { added += it.id }
                val failure = result.exceptionOrNull() as? RelationshipRejectedException
                if (failure != null && failure.reason != RelationshipRejection.DUPLICATE) throw failure
            }
            origins.insertAll(origins.originsOf(listOf(dropId)).map { it.copy(personId = keepId) })
            people.deleteById(dropId)
            MergeReceipt(
                keepBefore = keep,
                keepMerged = merged,
                dropped = drop,
                droppedEdges = edges,
                addedEdgeIds = added,
                photoToDelete = drop.photoId?.takeIf { it != merged.photoId },
            )
        }
    }

    /** The undo window has closed: only now is the dropped entry's own photograph file removed. */
    suspend fun finishMerge(receipt: MergeReceipt) {
        photos.delete(receipt.photoToDelete)
    }

    /**
     * Takes a merge back: the kept entry gets its old details back, the links the merge gave it are removed, and
     * the dropped entry returns with its details, photograph and links.
     *
     * The returned entry has a **new id**. Tree sync treats a deleted person as gone for good (a local re-insert
     * of the same id is deleted again by the next projection), so the entry comes back as an ordinary new person
     * and the undo reaches everyone else as ordinary edits. Two things are not restored: a detail of the kept
     * entry that was changed after the merge is left as changed, and import provenance stays with the kept entry.
     * Everything runs in one step; if a link cannot be put back, nothing changes and the reason is thrown.
     */
    suspend fun undoMerge(receipt: MergeReceipt): Result<Person> = runCatching {
        db.withTransaction {
            val before = receipt.keepBefore
            val merged = receipt.keepMerged
            people.findById(before.id)?.let { now ->
                people.update(
                    now.copy(
                        name = if (now.name == merged.name) before.name else now.name,
                        gender = if (now.gender == merged.gender) before.gender else now.gender,
                        birthDate = if (now.birthDate == merged.birthDate) before.birthDate else now.birthDate,
                        deathDate = if (now.deathDate == merged.deathDate) before.deathDate else now.deathDate,
                        deceased = if (now.deceased == merged.deceased) before.deceased else now.deceased,
                        photoId = if (now.photoId == merged.photoId) before.photoId else now.photoId,
                        notes = if (now.notes == merged.notes) before.notes else now.notes,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            receipt.addedEdgeIds.forEach { relationships.deleteById(it) }

            val back = receipt.dropped.copy(id = java.util.UUID.randomUUID().toString(), updatedAt = System.currentTimeMillis())
            people.insert(back)
            for (edge in receipt.droppedEdges) {
                val from = if (edge.fromPersonId == receipt.dropped.id) back.id else edge.fromPersonId
                val to = if (edge.toPersonId == receipt.dropped.id) back.id else edge.toPersonId
                // Someone on the far end may have been removed during the window; that link is simply gone.
                if (people.findById(from) == null || people.findById(to) == null) continue
                val failure = addRelationship(from, to, edge.type, edge.subtype).exceptionOrNull()
                    as? RelationshipRejectedException
                if (failure != null && failure.reason != RelationshipRejection.DUPLICATE) throw failure
            }
            back
        }
    }

    suspend fun originsOf(personIds: List<String>): List<PersonOrigin> = origins.originsOf(personIds)

    /** Whole-tree reads, used only by export. Everything else asks a narrower question. */
    /**
     * The entire graph, observed.
     *
     * Deliberately the one wide read in here. Every other query is narrow because the graph is
     * expected to reach thousands of people; this exists for the whole-tree chart, which cannot be
     * drawn from a neighbourhood by definition — the people it must show are exactly the ones no
     * neighbourhood reaches.
     */
    fun observeWholeGraph(): Flow<FamilySnapshot> =
        combine(observeAllPeople(), relationships.observeAllRelationships()) { everyone, edges ->
            val byId = everyone.associateBy { it.id }
            FamilySnapshot(
                people = byId,
                parentEdges = edges.filter { it.type == RelationshipType.PARENT }
                    .map { it.fromPersonId to it.toPersonId },
                spouseEdges = edges.filter { it.type == RelationshipType.SPOUSE }
                    .map { it.fromPersonId to it.toPersonId },
                siblingEdges = edges.filter { it.type == RelationshipType.SIBLING }
                    .map { it.fromPersonId to it.toPersonId },
                spouseKinds = edges.filter { it.type == RelationshipType.SPOUSE }
                    .mapNotNull { edge ->
                        SpouseKind.fromName(edge.subtype)?.let { (edge.fromPersonId to edge.toPersonId) to it }
                    }
                    .toMap(),
            )
        }

    suspend fun allPeople(): List<Person> = people.allPeople()
    suspend fun allRelationships(): List<Relationship> = relationships.allRelationships()
}

class RelationshipRejectedException(val reason: RelationshipRejection) :
    IllegalArgumentException("Relationship rejected: $reason")

/** What [FamilyRepository.mergePeopleUndoably] kept so the merge can be taken back by [FamilyRepository.undoMerge]. */
class MergeReceipt internal constructor(
    internal val keepBefore: Person,
    internal val keepMerged: Person,
    /** The entry that was folded away, as it was. */
    val dropped: Person,
    internal val droppedEdges: List<Relationship>,
    internal val addedEdgeIds: List<String>,
    internal val photoToDelete: String?,
)

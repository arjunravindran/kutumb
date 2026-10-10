package com.vibethroughcode.ftree.kutumb.treesync

import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import com.vibethroughcode.ftree.data.FTreeDatabase
import com.vibethroughcode.ftree.data.Gender
import com.vibethroughcode.ftree.data.PhotoStore
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.Relationship
import com.vibethroughcode.ftree.data.RelationshipType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** What [TreeSyncCoordinator.receive] did with a batch. */
data class Received(val applied: Int, val deferred: Int, val invalid: Int)

/**
 * Keeps one tree's database in step with the shared tree, in both directions.
 *
 * - [reconcile] notices what the user changed in the tables and records it as operations.
 * - [receive] takes operations that arrived from other phones, remembers them, and rewrites the tables to match.
 *
 * Everything the replica knows lives in the operation log (`tree_sync_ops`) and is rebuilt from it on first
 * use, so there is one source of truth and a crash can lose nothing: a change written to the tables but not
 * yet recorded is simply found by the next [reconcile]. A tree that has not been shared has no marker row and
 * every method here returns at once, so it behaves exactly as it did before sync existed.
 *
 * The pure rules live in `kutumb-core` (`treesync`); this class only reads and writes Room.
 */
class TreeSyncCoordinator(
    private val db: FTreeDatabase,
    private val photos: PhotoStore? = null,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : TreeSyncSink, TreeSyncSource, TreeSyncMembers, TreeSyncSwitch {
    private val dao = db.treeSyncDao()
    private val people = db.personDao()
    private val relationships = db.relationshipDao()
    private val memberDao = db.treeSyncMemberDao()
    private val mutex = Mutex()

    private class Loaded(val replica: TreeReplica, val clock: HybridClock, val recorder: EditRecorder)

    private var loaded: Loaded? = null

    suspend fun isShared(): Boolean = dao.meta() != null

    /** The id every phone sharing this tree agrees on, or null if it is not shared. */
    override suspend fun sharedId(): String? = dao.meta()?.sharedId

    /**
     * Starts sharing this tree, taking in everyone and every relationship already in it. Safe to call
     * again: a tree that is already shared is left alone. [sharedId] is given when joining a tree that
     * another phone started, so both use the same one.
     */
    suspend fun share(sharedId: String = newId(), deviceId: String = newId()): Boolean = mutex.withLock {
        if (dao.meta() != null) return@withLock false
        dao.putMeta(TreeSyncMetaEntity(sharedId = sharedId, deviceId = deviceId, lastHlc = Hlc(0, 0, deviceId).format()))
        loaded = null
        reconcileLocked()
        true
    }

    override suspend fun members(): List<String> = memberDao.memberIds()

    override suspend fun isMember(personId: String): Boolean = personId in memberDao.memberIds()

    /**
     * Shares the tree with somebody. Returns false if the tree is not shared or they already were a
     * member. Whether the person is *eligible* (paired in person) is decided by [shareTreeWith], which is
     * what callers use; this only records the fact.
     */
    override suspend fun addMember(personId: String): Boolean = mutex.withLock {
        if (dao.meta() == null) return@withLock false
        memberDao.add(TreeSyncMemberEntity(personId, wallClock())) != -1L
    }

    override suspend fun removeMember(personId: String) = mutex.withLock { memberDao.remove(personId) }

    override suspend fun startSharing(): Boolean = share()

    /**
     * Stops sharing: forgets the marker, the operation log and the members. The people and relationships
     * themselves are the tree and stay exactly as they are. With the marker gone every method here returns
     * at once, so changes are no longer recorded and anything still arriving from the other phones is dropped.
     * Sharing again later starts afresh, as if it had never been shared.
     */
    override suspend fun stopSharing(): Boolean = mutex.withLock {
        if (dao.meta() == null) return@withLock false
        db.withTransaction {
            memberDao.clear()
            dao.clearOps()
            dao.clearMeta()
        }
        loaded = null
        true
    }

    /** Records whatever the user has changed since last time. Returns how many operations that made. */
    suspend fun reconcile(): Int = mutex.withLock { reconcileLocked() }

    private suspend fun reconcileLocked(): Int {
        val s = load() ?: return 0
        try {
            return db.withTransaction {
                val ops = Reconciler.ops(s.replica, readTree(), s.recorder)
                if (ops.isEmpty()) return@withTransaction 0
                val rows = ArrayList<TreeSyncOpEntity>(ops.size)
                for (op in ops) {
                    // An operation this phone itself made is never refused for its clock; only a malformed value
                    // (a name past the limit) is, and that is left out rather than allowed to block the rest.
                    if (s.replica.apply(op, wallClock()) !is Verdict.Invalid) rows += entity(op, local = true, applied = true)
                }
                dao.insertOps(rows)
                dao.setLastHlc(s.clock.last().format())
                rows.size
            }
        } catch (e: Throwable) {
            loaded = null // the replica may be ahead of the log; rebuild it from the log next time
            throw e
        }
    }

    /**
     * Takes in operations from other phones: remembers them, applies them, and rewrites the tables to
     * show the result. Operations already held change nothing. Does nothing for a tree that is not shared.
     */
    override suspend fun receive(ops: List<TreeOp>): Received = mutex.withLock {
        val s = load() ?: return@withLock Received(0, 0, 0)
        try {
            db.withTransaction {
                var applied = 0
                var deferred = 0
                var invalid = 0
                val rows = ArrayList<TreeSyncOpEntity>(ops.size)
                for (op in ops) {
                    when (s.replica.apply(op, wallClock())) {
                        Verdict.Applied -> { s.clock.observe(op.hlc); rows += entity(op, local = false, applied = true); applied++ }
                        // Kept all the same: the log is what is handed to a phone that needs to catch up.
                        Verdict.NoChange -> { s.clock.observe(op.hlc); rows += entity(op, local = false, applied = true) }
                        Verdict.Deferred -> { rows += entity(op, local = false, applied = false); deferred++ }
                        is Verdict.Invalid -> invalid++
                    }
                }
                dao.insertOps(rows)
                retryDeferred(s)
                project(s)
                dao.setLastHlc(s.clock.last().format())
                Received(applied, deferred, invalid)
            }
        } catch (e: Throwable) {
            loaded = null
            throw e
        }
    }

    /** Operations this phone made that have not been sent anywhere yet. */
    override suspend fun unpublished(): List<TreeOp> = dao.unpublishedLocalOps().mapNotNull { TreeOpCodec.fromJson(it.json) }

    /** How many of this phone's own changes have not been handed to the transport yet. */
    override suspend fun pendingCount(): Int = dao.unpublishedLocalCount()

    override suspend fun markPublished(ids: List<String>) = markPublished(ids, wallClock())

    suspend fun markPublished(ids: List<String>, at: Long) {
        ids.chunked(500).forEach { dao.markPublished(it, at) }
    }

    /** Every operation held, local and received, oldest first: what a phone that is catching up needs. */
    override suspend fun allOps(): List<TreeOp> = (dao.appliedOps() + dao.deferredOps()).mapNotNull { TreeOpCodec.fromJson(it.json) }

    /** What the family should look at: edits to someone who was deleted, relationships held back. */
    suspend fun inbox(): List<InboxItem> = mutex.withLock { load()?.replica?.view()?.inbox.orEmpty() }

    /**
     * Records the user's changes whenever the people or relationship tables change, including ones made
     * by an import or the seeder. Changes are coalesced, so a burst of edits is one pass, and [afterPass] runs
     * after each (and once at start), which is where the new operations are sent. Call [Handle.close]
     * to stop. The first pass runs straight away, which picks up anything left unrecorded by a crash.
     */
    fun start(scope: CoroutineScope, settleMs: Long = 250, afterPass: suspend () -> Unit = {}): Handle {
        val pending = Channel<Unit>(Channel.CONFLATED)
        val observer = object : InvalidationTracker.Observer(arrayOf("people", "relationships")) {
            override fun onInvalidated(tables: Set<String>) { pending.trySend(Unit) }
        }
        db.invalidationTracker.addObserver(observer)
        pending.trySend(Unit)
        val job = scope.launch {
            for (signal in pending) {
                delay(settleMs)
                runCatching { reconcile() }
                runCatching { afterPass() } // e.g. send what was just recorded; a failure waits for the next pass
            }
        }
        return Handle {
            db.invalidationTracker.removeObserver(observer)
            pending.close()
            job.cancel()
        }
    }

    class Handle(private val onClose: () -> Unit) : AutoCloseable {
        override fun close() = onClose()
    }

    // --- loading and projecting -----------------------------------------------------------------------

    private suspend fun load(): Loaded? {
        loaded?.let { return it }
        val meta = dao.meta() ?: return null
        val replica = TreeReplica()
        // Replaying what was already accepted: the clock check does not apply a second time.
        for (row in dao.appliedOps()) TreeOpCodec.fromJson(row.json)?.let { replica.apply(it, Long.MAX_VALUE / 2) }
        val clock = HybridClock(meta.deviceId, wallClock, start = Hlc.parse(meta.lastHlc))
        val recorder = EditRecorder(clock, newId)
        return Loaded(replica, clock, recorder).also { loaded = it }
    }

    private suspend fun retryDeferred(s: Loaded) {
        val rows = dao.deferredOps()
        if (rows.isEmpty()) return
        val now = wallClock()
        val ready = rows.mapNotNull { row -> TreeOpCodec.fromJson(row.json)?.takeIf { s.replica.apply(it, now) != Verdict.Deferred }?.let { row.opId } }
        if (ready.isNotEmpty()) dao.markApplied(ready)
    }

    private suspend fun readTree(): DbTree {
        val ps = people.allPeople().associate { p ->
            p.id to PersonData(p.name, p.gender.name, p.birthDate, p.deathDate, p.deceased, p.notes)
        }
        val es = relationships.allRelationships().filter { it.type in SYNCED_TYPES }
            .associate { EdgeKey.of(it.type.name, it.fromPersonId, it.toPersonId) to it.subtype }
        return DbTree(ps, es)
    }

    /** Rewrites the tables to match the shared tree. Runs inside the caller's transaction. */
    private suspend fun project(s: Loaded) {
        val steps = Projection.plan(s.replica, readTree())
        val orphanedPhotos = ArrayList<String>()
        val now = wallClock()
        for (step in steps) when (step) {
            is ProjectionStep.InsertPerson -> people.insert(newPerson(step.id, step.data, now))
            is ProjectionStep.UpdatePerson -> people.findById(step.id)?.let { people.update(withData(it, step.data, now)) }
            is ProjectionStep.DeletePerson -> {
                people.findById(step.id)?.photoId?.let(orphanedPhotos::add)
                people.deleteById(step.id)
            }
            is ProjectionStep.InsertEdge -> syncedType(step.edge)?.let {
                relationships.insertIgnoringDuplicates(listOf(Relationship.of(step.edge.from, step.edge.to, it, step.subtype)))
            }
            is ProjectionStep.SetEdgeSubtype -> findEdge(step.edge)?.let { relationships.setSubtype(it.id, step.subtype) }
            is ProjectionStep.DeleteEdge -> findEdge(step.edge)?.let { relationships.deleteById(it.id) }
        }
        // The files go after the rows; a failure here leaves an orphaned file, never a dangling reference.
        orphanedPhotos.forEach { id -> photos?.delete(id) }
    }

    private fun syncedType(edge: EdgeKey): RelationshipType? = SYNCED_TYPES.firstOrNull { it.name == edge.type }

    private suspend fun findEdge(edge: EdgeKey): Relationship? {
        val type = syncedType(edge) ?: return null
        return relationships.edgesBetween(edge.from, edge.to)
            .firstOrNull { it.type == type && it.fromPersonId == edge.from && it.toPersonId == edge.to }
    }

    private fun newPerson(id: String, d: PersonData, now: Long) = withData(Person(id = id, createdAt = now), d, now)

    private fun withData(p: Person, d: PersonData, now: Long) = p.copy(
        name = d.name,
        gender = Gender.entries.firstOrNull { it.name == d.gender } ?: Gender.UNSPECIFIED,
        birthDate = d.birthDate,
        deathDate = d.deathDate,
        deceased = d.deceased,
        notes = d.notes,
        updatedAt = now,
    )

    private fun entity(op: TreeOp, local: Boolean, applied: Boolean) =
        TreeSyncOpEntity(opId = op.id, json = TreeOpCodec.toJson(op), local = local, applied = applied, publishedAt = null)

    private companion object {
        /** Edge kinds this build understands. A kind from a newer release is left out of sync, not guessed at. */
        val SYNCED_TYPES = setOf(RelationshipType.PARENT, RelationshipType.SPOUSE, RelationshipType.SIBLING, RelationshipType.CONNECTED)
    }
}

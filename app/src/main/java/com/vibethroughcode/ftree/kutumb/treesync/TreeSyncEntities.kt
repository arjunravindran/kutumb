package com.vibethroughcode.ftree.kutumb.treesync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Present only in a tree that is shared with other phones; its absence is what "not shared" means, so
 * a tree that never shares carries one empty table and behaves exactly as before.
 *
 * One row (id 1), because each tree has its own database. [lastHlc] is the newest clock reading this phone
 * has made or seen, saved so a restart cannot reuse a stamp.
 */
@Entity(tableName = "tree_sync_meta")
data class TreeSyncMetaEntity(
    @PrimaryKey val id: Int = 1,
    val sharedId: String,
    val deviceId: String,
    val lastHlc: String,
)

/**
 * The log of every operation this phone has made or received for the shared tree. The tree's registers
 * are rebuilt from it at start-up, so it is the source of truth for sync and is never edited, only appended to.
 *
 * [applied] is false for an operation stamped too far ahead of this phone's clock to accept yet; it is kept
 * and offered again later. [publishedAt] is null until a local operation has been sent to the relays.
 */
@Entity(tableName = "tree_sync_ops", indices = [Index(value = ["local", "publishedAt"])])
data class TreeSyncOpEntity(
    @PrimaryKey val opId: String,
    val json: String,
    val local: Boolean,
    val applied: Boolean,
    val publishedAt: Long?,
)

@Dao
interface TreeSyncDao {
    @Query("SELECT * FROM tree_sync_meta WHERE id = 1")
    suspend fun meta(): TreeSyncMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(meta: TreeSyncMetaEntity)

    @Query("UPDATE tree_sync_meta SET lastHlc = :lastHlc WHERE id = 1")
    suspend fun setLastHlc(lastHlc: String)

    /** Ignores an operation already held, so a repeated delivery adds nothing. Returns the row ids (-1 for a repeat). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOps(ops: List<TreeSyncOpEntity>): List<Long>

    @Query("SELECT * FROM tree_sync_ops WHERE applied = 1 ORDER BY rowid")
    suspend fun appliedOps(): List<TreeSyncOpEntity>

    @Query("SELECT * FROM tree_sync_ops WHERE applied = 0 ORDER BY rowid")
    suspend fun deferredOps(): List<TreeSyncOpEntity>

    @Query("UPDATE tree_sync_ops SET applied = 1 WHERE opId IN (:ids)")
    suspend fun markApplied(ids: List<String>)

    @Query("SELECT * FROM tree_sync_ops WHERE local = 1 AND publishedAt IS NULL ORDER BY rowid")
    suspend fun unpublishedLocalOps(): List<TreeSyncOpEntity>

    @Query("UPDATE tree_sync_ops SET publishedAt = :at WHERE opId IN (:ids)")
    suspend fun markPublished(ids: List<String>, at: Long)

    @Query("SELECT COUNT(*) FROM tree_sync_ops WHERE local = 1 AND publishedAt IS NULL")
    suspend fun unpublishedLocalCount(): Int

    @Query("SELECT COUNT(*) FROM tree_sync_ops")
    suspend fun opCount(): Int
}

/**
 * The people this shared tree is shared with: the ones who may send changes to it and receive them.
 * Keyed by person rather than by key, so a member who gets a new phone (a key rotation) stays a member.
 * Only people paired in person (a direct pairing) can be added, and that is checked again on every
 * message, because a pairing can be replaced.
 */
@Entity(tableName = "tree_sync_members")
data class TreeSyncMemberEntity(
    @PrimaryKey val personId: String,
    val addedAt: Long,
)

@Dao
interface TreeSyncMemberDao {
    @Query("SELECT personId FROM tree_sync_members ORDER BY addedAt, personId")
    suspend fun memberIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(member: TreeSyncMemberEntity): Long

    @Query("DELETE FROM tree_sync_members WHERE personId = :personId")
    suspend fun remove(personId: String)
}

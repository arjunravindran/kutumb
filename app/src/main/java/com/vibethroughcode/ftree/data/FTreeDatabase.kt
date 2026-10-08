package com.vibethroughcode.ftree.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vibethroughcode.ftree.kutumb.db.FactAnswerEntity
import com.vibethroughcode.ftree.kutumb.db.FactEntity
import com.vibethroughcode.ftree.kutumb.db.FactResolutionEntity
import com.vibethroughcode.ftree.kutumb.db.IdentityRotationEntity
import com.vibethroughcode.ftree.kutumb.db.KutumbDao
import com.vibethroughcode.ftree.kutumb.db.LedgerEntity
import com.vibethroughcode.ftree.kutumb.db.LocalIdentityEntity
import com.vibethroughcode.ftree.kutumb.db.MessageEntity
import com.vibethroughcode.ftree.kutumb.db.OutboxEntity
import com.vibethroughcode.ftree.kutumb.db.PersonLocationEntity
import com.vibethroughcode.ftree.kutumb.db.RedemptionEntity
import com.vibethroughcode.ftree.kutumb.db.TrustedContactEntity
import com.vibethroughcode.ftree.kutumb.treesync.TreeSyncDao
import com.vibethroughcode.ftree.kutumb.treesync.TreeSyncMemberDao
import com.vibethroughcode.ftree.kutumb.treesync.TreeSyncMemberEntity
import com.vibethroughcode.ftree.kutumb.treesync.TreeSyncMetaEntity
import com.vibethroughcode.ftree.kutumb.treesync.TreeSyncOpEntity

@Database(
    entities = [
        Person::class, Relationship::class, PersonOrigin::class,
        LocalIdentityEntity::class, TrustedContactEntity::class, IdentityRotationEntity::class,
        PersonLocationEntity::class, FactEntity::class, FactAnswerEntity::class, FactResolutionEntity::class,
        LedgerEntity::class, OutboxEntity::class, RedemptionEntity::class, MessageEntity::class,
        TreeSyncMetaEntity::class, TreeSyncOpEntity::class, TreeSyncMemberEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FTreeDatabase : RoomDatabase() {

    abstract fun personDao(): PersonDao
    abstract fun relationshipDao(): RelationshipDao
    abstract fun personOriginDao(): PersonOriginDao
    abstract fun kutumbDao(): KutumbDao
    abstract fun treeSyncDao(): TreeSyncDao
    abstract fun treeSyncMemberDao(): TreeSyncMemberDao

    companion object {
        private const val NAME = "f-tree.db"

        /**
         * Foreign keys are off by default in SQLite, and the ON DELETE CASCADE rules are the only
         * thing stopping a hard delete from leaving orphaned edges behind. Exposed so tests build
         * their in-memory database with exactly the same guarantees as the real one.
         */
        val enforceForeignKeys = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }

        /** [name] is the file; each tree has its own (see `TreeCatalog`), and the default is the original. */
        fun build(context: Context, name: String = NAME): FTreeDatabase =
            Room.databaseBuilder(context.applicationContext, FTreeDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(enforceForeignKeys)
                .build()
    }
}

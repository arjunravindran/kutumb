package com.vibethroughcode.ftree.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 adds the family-network tables (trust store, facts game, sync outbox, rewards). It only
 * creates; nothing in the tree, the relationships or the import history is touched, so an app
 * updated in place keeps its family. The statements are the ones Room generated into
 * `schemas/.../2.json`, and `MigrationTest` checks the result against that file.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `local_identity` (`id` INTEGER NOT NULL, `personId` TEXT NOT NULL, `publicKey` TEXT NOT NULL, `sealedPrivateKey` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `trusted_contacts` (`personId` TEXT NOT NULL, `pubKeyCurrent` TEXT NOT NULL, `pubKeyHistory` TEXT NOT NULL, `pairedAt` INTEGER NOT NULL, `pairingMethod` TEXT NOT NULL, `vouchedByPersonId` TEXT, PRIMARY KEY(`personId`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_trusted_contacts_pubKeyCurrent` ON `trusted_contacts` (`pubKeyCurrent`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `identity_rotations` (`signature` TEXT NOT NULL, `personId` TEXT NOT NULL, `oldPubKey` TEXT NOT NULL, `newPubKey` TEXT NOT NULL, `vouchedByPubKey` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`signature`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_identity_rotations_personId` ON `identity_rotations` (`personId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `person_locations` (`personId` TEXT NOT NULL, `label` TEXT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `source` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `sharingEnabled` INTEGER NOT NULL, PRIMARY KEY(`personId`, `label`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `facts` (`id` TEXT NOT NULL, `personId` TEXT NOT NULL, `category` TEXT NOT NULL, `prompt` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_facts_personId` ON `facts` (`personId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `fact_answers` (`id` TEXT NOT NULL, `factId` TEXT NOT NULL, `answererId` TEXT NOT NULL, `answerText` TEXT NOT NULL, `isSelfReported` INTEGER NOT NULL, `submittedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_fact_answers_factId` ON `fact_answers` (`factId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `fact_resolutions` (`factId` TEXT NOT NULL, `winningAnswerId` TEXT NOT NULL, `resolvedByPersonId` TEXT NOT NULL, `pointsAwarded` INTEGER NOT NULL, PRIMARY KEY(`factId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `points_ledger` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `personId` TEXT NOT NULL, `points` INTEGER NOT NULL, `reasonFactId` TEXT NOT NULL, `awardedAt` INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_points_ledger_reasonFactId` ON `points_ledger` (`reasonFactId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_points_ledger_personId` ON `points_ledger` (`personId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_outbox` (`eventId` TEXT NOT NULL, `payloadEncrypted` TEXT NOT NULL, `targetPubKeys` TEXT NOT NULL, `relayUrls` TEXT NOT NULL, `publishedAt` INTEGER, `ackedBy` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, PRIMARY KEY(`eventId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `reward_redemptions` (`id` TEXT NOT NULL, `fromPersonId` TEXT NOT NULL, `toPersonId` TEXT NOT NULL, `rewardType` TEXT NOT NULL, `pointsThreshold` INTEGER NOT NULL, `status` TEXT NOT NULL, `redeemedAt` INTEGER, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reward_redemptions_fromPersonId_toPersonId` ON `reward_redemptions` (`fromPersonId`, `toPersonId`)")
    }
}

/**
 * Version 3 adds the messages between people who have met in person. Like version 2 it only
 * creates: nothing in the tree or the family network is touched, so an update in place keeps
 * everything. The statements are the ones Room generated into `schemas/.../3.json`.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `threadPersonId` TEXT NOT NULL, `senderId` TEXT NOT NULL, `body` TEXT NOT NULL, `sentAt` INTEGER NOT NULL, `deliveryStatus` TEXT NOT NULL, `isRead` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_threadPersonId` ON `messages` (`threadPersonId`)")
    }
}

/**
 * Version 4 adds the tables behind tree sync: a one-row marker that says the tree is shared, who it is shared with, and the log
 * of operations. Like versions 2 and 3 it only creates, so nothing in the tree, the family network or the
 * import history is touched and an update in place keeps everything; a tree that is never shared keeps
 * both tables empty. The statements are the ones Room generated into `schemas/.../4.json`.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `tree_sync_meta` (`id` INTEGER NOT NULL, `sharedId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `lastHlc` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `tree_sync_ops` (`opId` TEXT NOT NULL, `json` TEXT NOT NULL, `local` INTEGER NOT NULL, `applied` INTEGER NOT NULL, `publishedAt` INTEGER, PRIMARY KEY(`opId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tree_sync_ops_local_publishedAt` ON `tree_sync_ops` (`local`, `publishedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `tree_sync_members` (`personId` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`personId`))")
    }
}

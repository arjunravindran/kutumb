package com.vibethroughcode.ftree.kutumb.treesync

import android.content.Context
import com.vibethroughcode.ftree.data.CatalogStore

/**
 * The phone's one [TreeInvitations], kept in preferences. One instance for the whole app, because every
 * tree's container reads and writes it and each must see what the others have just done; a second
 * instance over the same text would hold a stale copy.
 */
object AppInvitations {
    @Volatile private var instance: TreeInvitations? = null

    fun get(context: Context): TreeInvitations = instance ?: synchronized(this) {
        instance ?: TreeInvitations(prefsStore(context.applicationContext)).also { instance = it }
    }

    private fun prefsStore(context: Context): CatalogStore {
        val prefs = context.getSharedPreferences("tree-invitations", Context.MODE_PRIVATE)
        return object : CatalogStore {
            override fun read(): String? = prefs.getString("invitations", null)
            // Committed, not applied in the background: an invitation answered must not come back after a crash.
            override fun write(text: String) { prefs.edit().putString("invitations", text).commit() }
        }
    }
}

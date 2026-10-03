package com.vibethroughcode.ftree

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.vibethroughcode.ftree.data.CatalogStore
import com.vibethroughcode.ftree.data.TreeCatalog
import com.vibethroughcode.ftree.data.TreeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.vibethroughcode.ftree.kutumb.PhoneKey
import com.vibethroughcode.ftree.kutumb.sync.SyncPreferences
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class FTreeApplication : Application() {
    lateinit var catalog: TreeCatalog
        private set

    /** The open tree's wiring. Replaced as a whole when another tree is opened. */
    lateinit var container: AppContainer
        private set

    /** The sync switch and relay list: the phone's, so one instance for every tree's container. */
    val syncPreferences: SyncPreferences by lazy { SyncPreferences(this) }

    /**
     * Trees other than the open one that keep syncing while sync is on, each with its own database,
     * engine and relay connections. Every use is under its own lock: the sync switch is collected
     * on a background thread while [switchTo] runs on the main one.
     */
    private val background = HashMap<String, AppContainer>()

    /** Ids of the trees syncing unseen right now. For tests and diagnostics. */
    val syncingInBackground: Set<String> get() = synchronized(background) { background.keys.toSet() }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("tree-catalog", Context.MODE_PRIVATE)
        catalog = TreeCatalog(
            object : CatalogStore {
                override fun read(): String? = prefs.getString("catalog", null)
                override fun write(text: String) { prefs.edit().putString("catalog", text).apply() }
            },
        )
        open(catalog.active)
        appScope.launch {
            syncPreferences.enabled.collect { on -> if (on) syncOtherTrees() else releaseOtherTrees() }
        }
    }

    private fun open(tree: TreeInfo) {
        // A tree that was syncing in the background already has its wiring running; reuse it.
        synchronized(background) { container = background.remove(tree.id) ?: AppContainer(this, tree, syncPreferences) }
        // An alarm does not survive being force-stopped, so every start puts the morning's back.
        // With reminders off this reads one preference and builds nothing else.
        if (container.reminderPreferences.enabled.value) container.reminders.ensure()
        // Sync is opt-in: with the switch off this reads one preference and opens nothing.
        if (container.syncPreferences.enabled.value) container.syncService
        adoptTreeKey(container)
    }

    /**
     * A tree that already holds a key (made before the phone had one of its own) lends it to the
     * phone if the phone has none, so the key a relative paired with stays the phone's key. Trees
     * that disagree keep their own; see [PhoneKey.adopt].
     */
    private fun adoptTreeKey(opened: AppContainer) {
        appScope.launch {
            opened.kutumbRepository.observeIdentity().first()?.let { opened.phoneKey.adopt(it.keyPair) }
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Starts a service for every tree but the open one; each stays idle until its tree has an identity. */
    private fun syncOtherTrees() {
        for (tree in catalog.trees) {
            val held = synchronized(background) {
                if (tree.id == container.tree.id || tree.id in background) null
                else AppContainer(this, tree, syncPreferences).also { background[tree.id] = it }
            } ?: continue
            held.syncService
            adoptTreeKey(held)
        }
    }

    /** Sync was switched off: the other trees' services have stopped themselves; let go of their databases. */
    private fun releaseOtherTrees() {
        val released = synchronized(background) { background.values.toList().also { background.clear() } }
        released.forEach { it.stopServices(); it.closeDatabase() }
    }

    /**
     * Opens another tree: remembers the choice, shuts the current wiring down and builds the new
     * one. Screens and view models still hold the old tree, so the caller must restart the UI
     * ([relaunch]) straight after.
     */
    fun switchTo(id: String): Boolean {
        if (id == container.tree.id) return true
        if (!catalog.setActive(id)) return false
        val old = container
        if (syncPreferences.enabled.value) {
            // Leaving a tree must not silence it: it keeps syncing, unseen, with its database open.
            old.stopForeground()
            synchronized(background) { background[old.tree.id] = old }
            open(catalog.active)
        } else {
            old.stopServices()
            open(catalog.active)
            Handler(Looper.getMainLooper()).postDelayed({ old.closeDatabase() }, CLOSE_DELAY_MS)
        }
        return true
    }

    /** A fresh task for the main screen, so no view model of the previous tree survives. */
    fun relaunch() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    private companion object {
        const val CLOSE_DELAY_MS = 3_000L
    }

    /** Deletes what a removed tree left behind: its database, photographs and identity file. */
    fun deleteFilesOf(tree: TreeInfo) {
        synchronized(background) { background.remove(tree.id) }?.let { it.stopServices(); it.closeDatabase() }
        deleteDatabase(tree.databaseName)
        File(filesDir, tree.photoDirectory).deleteRecursively()
        deleteSharedPreferences(tree.identityFile)
    }
}

package com.vibethroughcode.ftree

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.vibethroughcode.ftree.data.CatalogStore
import com.vibethroughcode.ftree.data.TreeCatalog
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.TreeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.vibethroughcode.ftree.kutumb.PhoneKey
import com.vibethroughcode.ftree.kutumb.sync.SyncPreferences
import com.vibethroughcode.ftree.kutumb.sync.SyncJobs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        // The periodic job exists exactly while sync is on and a relay is named.
        appScope.launch {
            combine(syncPreferences.enabled, syncPreferences.relays, SyncJobs::shouldRun)
                .distinctUntilChanged()
                .collect { run -> if (run) SyncJobs.schedule(this@FTreeApplication) else SyncJobs.cancel(this@FTreeApplication) }
        }
    }

    private fun open(tree: TreeInfo) {
        // A tree that was syncing in the background already has its wiring running; reuse it.
        synchronized(background) { container = background.remove(tree.id) ?: AppContainer(this, tree, syncPreferences, ::everyoneInAllTrees) }
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

    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One run of the periodic background job ([SyncJobs]): makes sure sync is up for every tree, gives
     * it [window] to collect what waited at the relays and to send what waited here, and returns.
     * A no-op with the switch off. The connections are not closed afterwards: they belong to the
     * process, which the system ends when it likes, and are used at once if the app is opened.
     */
    suspend fun backgroundSyncPass(window: Long = BACKGROUND_PASS_MS) {
        if (!syncPreferences.enabled.value) return
        container.syncService.poke()
        syncOtherTrees()
        synchronized(background) { background.values.toList() }.forEach { it.syncService.poke() }
        delay(window)
    }

    /**
     * Everyone in every tree, for the birthday reminders: a reminder is about people the reader knows,
     * and which tree holds them is their own business. The same person recorded in two trees (same
     * name and dates) is one line, not two. A tree that is not open is read and closed again; this runs
     * once a morning, so the cost is a few small queries.
     */
    suspend fun everyoneInAllTrees(): List<Person> = withContext(Dispatchers.IO) {
        val seen = HashSet<String>()
        val out = ArrayList<Person>()
        for (tree in catalog.trees) {
            for (person in peopleOf(tree)) {
                val name = person.name?.trim()?.lowercase()
                val same = if (name.isNullOrEmpty() || (person.birthDate == null && person.deathDate == null)) null
                else "$name|${person.birthDate}|${person.deathDate}"
                if (same == null || seen.add(same)) out += person
            }
        }
        out
    }

    private suspend fun peopleOf(tree: TreeInfo): List<Person> {
        val open = synchronized(background) { if (tree.id == container.tree.id) container else background[tree.id] }
        if (open != null) return open.familyRepository.allPeople()
        val shut = AppContainer(this, tree, syncPreferences)
        try {
            return shut.familyRepository.allPeople()
        } finally {
            shut.closeDatabase()
        }
    }

    /** Starts a service for every tree but the open one; each stays idle until its tree has an identity. */
    private fun syncOtherTrees() {
        for (tree in catalog.trees) {
            val held = synchronized(background) {
                if (tree.id == container.tree.id || tree.id in background) null
                else AppContainer(this, tree, syncPreferences, ::everyoneInAllTrees).also { background[tree.id] = it }
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
        const val BACKGROUND_PASS_MS = 30_000L
    }

    /** Deletes what a removed tree left behind: its database, photographs and identity file. */
    fun deleteFilesOf(tree: TreeInfo) {
        synchronized(background) { background.remove(tree.id) }?.let { it.stopServices(); it.closeDatabase() }
        deleteDatabase(tree.databaseName)
        File(filesDir, tree.photoDirectory).deleteRecursively()
        deleteSharedPreferences(tree.identityFile)
    }
}

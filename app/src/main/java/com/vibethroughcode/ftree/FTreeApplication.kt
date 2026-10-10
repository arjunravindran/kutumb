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
import com.vibethroughcode.ftree.data.TreeKind
import com.vibethroughcode.ftree.data.CatalogResult
import com.vibethroughcode.ftree.kutumb.treesync.PendingInvite
import com.vibethroughcode.ftree.kutumb.treesync.TreeInviteCodec
import com.vibethroughcode.ftree.kutumb.treesync.TreeJoin
import com.vibethroughcode.ftree.kutumb.treesync.prepareJoinedTree
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

/** What came of saying yes to an invitation to a shared tree. */
sealed interface JoinResult {
    /** A new tree now holds the shared one. [asked] is whether the request for its people was handed to sync. */
    data class Joined(val tree: TreeInfo, val asked: Boolean) : JoinResult

    /** Family sync is off, so nothing could arrive; nothing was created. */
    data object SyncOff : JoinResult

    data object AlreadyJoined : JoinResult

    /** Something went wrong before the tree was complete; whatever was started has been removed. */
    data object Failed : JoinResult
}

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
        // A shared tree records its changes whether or not sync is on; sending waits for sync. An unshared one is one query.
        container.startTreeSyncIfShared()
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

    /**
     * Says yes to an invitation: makes a new tree beside the others, marks it as the shared tree, and gives it
     * what it needs to hear from the person who shared it (who this phone is in that tree, and the inviter as
     * somebody met in person). It then opens it and asks for the people. The new tree holds nobody until they
     * arrive, and nothing in any other tree is touched.
     *
     * The caller restarts the UI ([relaunch]) after a [JoinResult.Joined], as for any change of tree.
     */
    suspend fun joinSharedTree(invite: PendingInvite): JoinResult {
        val invitations = container.invitations
        if (invitations.hasJoined(invite.tree)) return JoinResult.AlreadyJoined
        if (!syncPreferences.enabled.value) return JoinResult.SyncOff
        val created = withContext(Dispatchers.IO) {
            // The key the inviter knows this phone by is the one the tree that received the invitation holds.
            val key = withTreeContainer(invite.viaTree) { it.kutumbRepository.observeIdentity().first()?.keyPair }
                ?: return@withContext null
            val tree = createTreeFor(invite.name) ?: return@withContext null
            try {
                val fresh = AppContainer(this@FTreeApplication, tree, syncPreferences, ::everyoneInAllTrees)
                try {
                    prepareJoinedTree(fresh.treeSync, fresh.kutumbRepository, invite, key, System.currentTimeMillis())
                } finally {
                    fresh.closeDatabase()
                }
                tree
            } catch (e: Throwable) {
                catalog.remove(tree.id)
                deleteFilesOf(tree)
                null
            }
        } ?: return JoinResult.Failed
        invitations.joined(invite.tree, created.id)
        val opened = withContext(Dispatchers.Main) { switchTo(created.id) }
        if (!opened) return JoinResult.Failed
        val asked = withContext(Dispatchers.IO) {
            container.syncPublisher.publish(TreeInviteCodec.join(TreeJoin(invite.tree)), listOf(invite.from))
        }
        return JoinResult.Joined(created, asked)
    }

    /** Runs [block] against the wiring of tree [treeId]: the open one, one syncing unseen, or one opened just for this. */
    private suspend fun <T> withTreeContainer(treeId: String, block: suspend (AppContainer) -> T): T? {
        val info = catalog.trees.firstOrNull { it.id == treeId } ?: return null
        val live = synchronized(background) { if (info.id == container.tree.id) container else background[info.id] }
        if (live != null) return block(live)
        val shut = AppContainer(this, info, syncPreferences)
        try {
            return block(shut)
        } finally {
            shut.closeDatabase()
        }
    }

    /** A new Family tree called [wanted], or "wanted (2)" and so on if that name is taken. Null if none would do. */
    private fun createTreeFor(wanted: String): TreeInfo? {
        val base = wanted.trim().take(TreeCatalog.MAX_NAME)
        for (n in 1..MAX_NAME_TRIES) {
            val name = if (n == 1) base else "${base.take(TreeCatalog.MAX_NAME - 5)} ($n)"
            when (val made = catalog.create(name, TreeKind.FAMILY)) {
                is CatalogResult.Ok -> return made.tree
                is CatalogResult.Refused -> if (made.reason != CatalogResult.Reason.NAME_TAKEN) return null
            }
        }
        return null
    }

    private companion object {
        const val MAX_NAME_TRIES = 9
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

package com.vibethroughcode.ftree.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibethroughcode.ftree.FTreeApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opening another tree gives a different database; nothing leaks between them; deleting cleans up. */
@RunWith(AndroidJUnit4::class)
class TreeSwitchingTest {
    private val app: FTreeApplication = ApplicationProvider.getApplicationContext()
    private var created: TreeInfo? = null

    @Before fun startOnTheOriginal() {
        assertTrue(app.switchTo(TreeCatalog.ORIGINAL.id))
    }

    private val syncWasOn = app.syncPreferences.enabled.value

    @After fun cleanUp() {
        app.syncPreferences.setEnabled(syncWasOn)
        app.switchTo(TreeCatalog.ORIGINAL.id)
        created?.let { t ->
            (app.catalog.remove(t.id) as? CatalogResult.Ok)?.let { app.deleteFilesOf(it.tree) }
        }
    }

    @Test fun eachTreeKeepsItsOwnPeople() = runBlocking {
        val marker = "switching-test-${System.nanoTime()}"
        val circle = (app.catalog.create("Work $marker", TreeKind.CIRCLE) as CatalogResult.Ok).tree.also { created = it }

        app.container.familyRepository.addPerson(Person(id = marker, name = "In the family"))

        assertTrue(app.switchTo(circle.id))
        assertEquals(circle, app.container.tree)
        assertTrue(app.container.familyRepository.observeAllPeople().first().none { it.id == marker })
        app.container.familyRepository.addPerson(Person(id = "$marker-colleague", name = "A colleague"))

        assertTrue(app.switchTo(TreeCatalog.ORIGINAL.id))
        val people = app.container.familyRepository.observeAllPeople().first()
        assertTrue(people.any { it.id == marker })
        assertTrue(people.none { it.id == "$marker-colleague" })

        app.container.familyRepository.deletePerson(marker, DeletionMode.DELETE_COMPLETELY)
    }

    @Test fun deletingATreeRemovesItsFiles() {
        val tree = (app.catalog.create("Temp ${System.nanoTime()}", TreeKind.CIRCLE) as CatalogResult.Ok).tree
        assertTrue(app.switchTo(tree.id))
        runBlocking { app.container.familyRepository.addPerson(Person(name = "x")) }
        assertTrue(app.getDatabasePath(tree.databaseName).exists())

        assertTrue(app.switchTo(TreeCatalog.ORIGINAL.id))
        Thread.sleep(3_500) // the old database is closed a moment after the switch
        assertTrue(app.catalog.remove(tree.id) is CatalogResult.Ok)
        app.deleteFilesOf(tree)

        assertFalse(app.getDatabasePath(tree.databaseName).exists())
        assertFalse(File(app.filesDir, tree.photoDirectory).exists())
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for $what" }
            Thread.sleep(50)
        }
    }

    @Test fun withSyncOnTheOtherTreesKeepRunningAndSwitchingKeepsThemOpen() = runBlocking {
        val circle = (app.catalog.create("Sync ${System.nanoTime()}", TreeKind.CIRCLE) as CatalogResult.Ok).tree.also { created = it }
        val original = TreeCatalog.ORIGINAL.id

        app.syncPreferences.setEnabled(true)
        waitFor("the other tree to start syncing") { circle.id in app.syncingInBackground }
        assertFalse(original in app.syncingInBackground) // the open tree is not a background one

        // Leaving the original keeps it running, and its database stays usable well after the usual close delay.
        val left = app.container
        assertTrue(app.switchTo(circle.id))
        assertTrue(original in app.syncingInBackground)
        assertFalse(circle.id in app.syncingInBackground)
        Thread.sleep(3_500)
        left.familyRepository.observeAllPeople().first()

        // Coming back reuses the running wiring instead of building a second one.
        assertTrue(app.switchTo(original))
        assertTrue(app.container === left)

        // Switching sync off lets go of the unseen trees.
        app.syncPreferences.setEnabled(false)
        waitFor("the background trees to be released") { app.syncingInBackground.isEmpty() }
    }

    @Test fun aTreeSyncingInTheBackgroundCanStillBeDeleted() {
        val tree = (app.catalog.create("Gone ${System.nanoTime()}", TreeKind.CIRCLE) as CatalogResult.Ok).tree
        app.syncPreferences.setEnabled(true)
        waitFor("the tree to start syncing") { tree.id in app.syncingInBackground }

        assertTrue(app.catalog.remove(tree.id) is CatalogResult.Ok)
        app.deleteFilesOf(tree)

        assertFalse(tree.id in app.syncingInBackground)
        assertFalse(app.getDatabasePath(tree.databaseName).exists())
    }

    @Test fun theOpenTreeCannotBeDeleted() {
        assertEquals(CatalogResult.Refused(CatalogResult.Reason.IS_ACTIVE), app.catalog.remove(app.container.tree.id))
    }
}

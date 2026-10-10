package com.vibethroughcode.ftree.ui

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.data.CatalogResult
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.RelationshipType
import com.vibethroughcode.ftree.data.TreeCatalog
import com.vibethroughcode.ftree.data.TreeInfo
import com.vibethroughcode.ftree.data.TreeKind
import com.vibethroughcode.ftree.phonebook.ContactRelation
import com.vibethroughcode.ftree.phonebook.PhoneContact
import com.vibethroughcode.ftree.phonebook.PhonebookSource
import com.vibethroughcode.ftree.ui.phonebook.ContactRowTagPrefix
import com.vibethroughcode.ftree.ui.phonebook.ContactsAddTag
import com.vibethroughcode.ftree.ui.phonebook.ContactsDoneTag
import com.vibethroughcode.ftree.ui.phonebook.ContactsImportDialog
import com.vibethroughcode.ftree.ui.phonebook.ContactsMeFieldTag
import com.vibethroughcode.ftree.ui.phonebook.contactRelationTag
import com.vibethroughcode.ftree.ui.theme.FTreeTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Adding people from contacts, as a reader meets it. The address book is a list here; the permission is granted
 * ahead of time, because asking for it is the system's dialog and not something this app draws. On a throwaway
 * tree, so the emulator's real one is never touched.
 */
@RunWith(AndroidJUnit4::class)
class ContactsImportFlowTest {
    @get:Rule val compose = createComposeRule()

    private val app: FTreeApplication = ApplicationProvider.getApplicationContext()
    private lateinit var tree: TreeInfo

    private val fake = object : PhonebookSource {
        override suspend fun contacts() = listOf(
            PhoneContact(1, "Asha Devi", "1960-02-11"),
            PhoneContact(2, "Ravi", null),
            PhoneContact(3, "Meera", "--09-30"),
        )
    }

    @Before fun aTreeOfItsOwnAndThePermission() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, Manifest.permission.READ_CONTACTS)
        app.switchTo(TreeCatalog.ORIGINAL.id)
        tree = (app.catalog.create("Contacts test ${System.nanoTime()}", TreeKind.FAMILY) as CatalogResult.Ok).tree
        check(app.switchTo(tree.id))
    }

    @After fun cleanUp() {
        app.switchTo(TreeCatalog.ORIGINAL.id)
        Thread.sleep(3_500)
        (app.catalog.remove(tree.id) as? CatalogResult.Ok)?.let { app.deleteFilesOf(it.tree) }
    }

    private fun show(onDismiss: () -> Unit = {}) {
        compose.setContent { FTreeTheme { ContactsImportDialog(onDismiss = onDismiss, source = fake) } }
    }

    private fun scrollToRow(id: Long) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(ContactRowTagPrefix + id))
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test fun anEmptyTreeIsFilledByTickingPeopleAndSayingHowTheyAreRelated() {
        var dismissed = false
        show { dismissed = true }
        compose.waitUntil(10_000) { count(ContactsMeFieldTag) == 1 }

        // Nothing can be added until the reader has said who they are and ticked somebody.
        compose.onNodeWithTag(ContactsAddTag).assertIsNotEnabled()
        compose.onNodeWithTag(ContactsMeFieldTag).performTextInput("Priya")
        compose.onNodeWithTag(ContactsAddTag).assertIsNotEnabled()

        compose.onNodeWithTag(ContactRowTagPrefix + 1).performClick()
        compose.onNodeWithTag(contactRelationTag(1, ContactRelation.MOTHER)).performClick()
        // The keyboard is still up after typing the name and the first row has opened out, so the next row may
        // be off the screen; scroll to it as a person would.
        scrollToRow(2)
        compose.onNodeWithTag(ContactRowTagPrefix + 2).performClick()
        compose.onNodeWithTag(contactRelationTag(2, ContactRelation.FATHER)).performClick()

        compose.onNodeWithTag(ContactsAddTag).performClick()
        compose.waitUntil(10_000) { count(ContactsDoneTag) == 1 }
        compose.onAllNodesWithText("Added: 3").assertCountIs(1)
        compose.onAllNodesWithText("Connected to you: 2").assertCountIs(1)

        compose.onNodeWithTag(ContactsDoneTag).performClick()
        assertTrue("the page closed", dismissed)

        val people = runBlocking { app.container.familyRepository.allPeople() }
        assertEquals(setOf("Priya", "Asha Devi", "Ravi"), people.map { it.name }.toSet())
        val me = people.single { it.name == "Priya" }
        val parents = runBlocking { app.container.database.relationshipDao().allRelationships() }
            .filter { it.type == RelationshipType.PARENT && it.toPersonId == me.id }.map { it.fromPersonId }.toSet()
        assertEquals(people.filter { it.name != "Priya" }.map { it.id }.toSet(), parents)
        assertEquals("1960-02-11", people.single { it.name == "Asha Devi" }.birthDate)
    }

    @Test fun aTreeWithPeopleSkipsWhoIsThereAndAddsTheRestUnconnected() {
        runBlocking { app.container.familyRepository.addPerson(Person(name = "asha devi")) }
        show()
        compose.waitUntil(10_000) { count(ContactRowTagPrefix + 3) == 1 }

        assertEquals("no question about who the reader is", 0, count(ContactsMeFieldTag))
        compose.onAllNodesWithText("Already in your tree").assertCountIs(1)
        // A row already in the tree cannot be ticked, so there is still nothing to add.
        compose.onNodeWithTag(ContactRowTagPrefix + 1).performClick()
        compose.onNodeWithTag(ContactsAddTag).assertIsNotEnabled()

        compose.onNodeWithTag(ContactRowTagPrefix + 3).performClick()
        assertEquals("no relation to choose without a reader", 0, count(contactRelationTag(3, ContactRelation.MOTHER)))
        compose.onNodeWithTag(ContactsAddTag).performClick()
        compose.waitUntil(10_000) { count(ContactsDoneTag) == 1 }
        compose.onNodeWithTag(ContactsDoneTag).assertIsDisplayed()

        val people = runBlocking { app.container.familyRepository.allPeople() }
        assertEquals(setOf("asha devi", "Meera"), people.map { it.name }.toSet())
        assertTrue(runBlocking { app.container.database.relationshipDao().allRelationships() }.isEmpty())
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountIs(n: Int) =
        assertEquals(n, fetchSemanticsNodes().size)
}

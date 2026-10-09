package com.vibethroughcode.ftree.ui

import com.vibethroughcode.ftree.ui.more.MoreSettingsTag
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.CatalogResult
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.TreeCatalog
import com.vibethroughcode.ftree.data.TreeInfo
import com.vibethroughcode.ftree.data.TreeKind
import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.messaging.MessageNotifier
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import com.vibethroughcode.ftree.ui.network.ThreadInputTag
import com.vibethroughcode.ftree.ui.settings.SettingsMessageNotesToggleTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Switching the notes on, and what tapping one does. */
@RunWith(AndroidJUnit4::class)
class MessageNoteTapTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: FTreeApplication = ApplicationProvider.getApplicationContext()
    private val wasOn = app.container.messageNotificationPreferences.isEnabled()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var circle: TreeInfo? = null

    @Before fun grantAndStartOnTheOriginal() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        app.switchTo(TreeCatalog.ORIGINAL.id)
    }

    @After fun cleanUp() {
        scenario?.close()
        app.container.messageNotificationPreferences.setEnabled(wasOn)
        app.switchTo(TreeCatalog.ORIGINAL.id)
        circle?.let { t ->
            Thread.sleep(3_500)
            (app.catalog.remove(t.id) as? CatalogResult.Ok)?.let { app.deleteFilesOf(it.tree) }
        }
    }

    private fun seedKiran(): Person = runBlocking {
        val kiran = app.container.familyRepository.addPerson(Person(name = "Kiran Rao"))
        val me = app.container.familyRepository.addPerson(Person(name = "Yash Rao"))
        app.container.kutumbRepository.clearAll()
        app.container.kutumbRepository.setIdentity(LocalIdentity(me.id, Bip340Scheme.generateKeyPair()))
        app.container.kutumbRepository.saveContact(
            TrustedContact(kiran.id, Bip340Scheme.generateKeyPair().publicKey, pairedAt = 1, pairingMethod = PairingMethod.DIRECT),
        )
        kiran
    }

    private fun tapIntent(treeId: String, personId: String) =
        Intent(app, MainActivity::class.java)
            .setAction(MessageNotifier.ACTION_OPEN_THREAD)
            .putExtra(MessageNotifier.EXTRA_PERSON, personId)
            .putExtra(MessageNotifier.EXTRA_TREE, treeId)

    @Test fun theSwitchTurnsNotesOnAndOffAndStartsOff() {
        app.container.messageNotificationPreferences.setEnabled(false)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(NavMoreTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(NavMoreTag).performClick()
        compose.onNodeWithTag(MoreSettingsTag).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(SettingsMessageNotesToggleTag).fetchSemanticsNodes().isNotEmpty() }

        // Notifications are already allowed for this run, so turning it on needs no dialog.
        compose.onNodeWithTag(SettingsMessageNotesToggleTag).performScrollTo().performClick()
        compose.waitUntil(5_000) { app.container.messageNotificationPreferences.isEnabled() }

        compose.onNodeWithTag(SettingsMessageNotesToggleTag).performScrollTo().performClick()
        compose.waitUntil(5_000) { !app.container.messageNotificationPreferences.isEnabled() }
        assertFalse(app.container.messageNotificationPreferences.isEnabled())
    }

    @Test fun tappingANoteOpensTheConversation() {
        val kiran = seedKiran()
        scenario = ActivityScenario.launch(tapIntent(app.container.tree.id, kiran.id))
        compose.waitUntil(8_000) { compose.onAllNodesWithTag(ThreadInputTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ThreadInputTag).assertIsDisplayed()
        compose.onAllNodesWithText("Kiran Rao").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    @Test fun aNoteForAnotherTreeOpensThatTreeAndItsConversation() {
        // The conversation lives in a circle; the app is on the original tree when the note is tapped.
        circle = (app.catalog.create("Notes ${System.nanoTime()}", TreeKind.CIRCLE) as CatalogResult.Ok).tree
        assertTrue(app.switchTo(circle!!.id))
        val kiran = seedKiran()
        assertTrue(app.switchTo(TreeCatalog.ORIGINAL.id))
        assertEquals(TreeCatalog.ORIGINAL.id, app.container.tree.id)

        scenario = ActivityScenario.launch(tapIntent(circle!!.id, kiran.id))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(ThreadInputTag).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(circle!!.id, app.container.tree.id)
        compose.onNodeWithTag(ThreadInputTag).assertIsDisplayed()
    }
}

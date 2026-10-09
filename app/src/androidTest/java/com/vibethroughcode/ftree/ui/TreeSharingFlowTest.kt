package com.vibethroughcode.ftree.ui

import com.vibethroughcode.ftree.ui.more.MoreSettingsTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.CatalogResult
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.data.TreeCatalog
import com.vibethroughcode.ftree.data.TreeInfo
import com.vibethroughcode.ftree.data.TreeKind
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import com.vibethroughcode.ftree.ui.settings.SharingButtonTag
import com.vibethroughcode.ftree.ui.settings.SharingMemberTagPrefix
import com.vibethroughcode.ftree.ui.settings.SharingTurnOnTag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sharing a tree from Settings > Your trees, on a throwaway tree so the emulator's real one is never shared.
 */
@RunWith(AndroidJUnit4::class)
class TreeSharingFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: FTreeApplication = ApplicationProvider.getApplicationContext()
    private lateinit var tree: TreeInfo
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun aTreeOfItsOwn() {
        app.switchTo(TreeCatalog.ORIGINAL.id)
        tree = (app.catalog.create("Sharing test ${System.nanoTime()}", TreeKind.FAMILY) as CatalogResult.Ok).tree
        check(app.switchTo(tree.id))
    }

    @After fun cleanUp() {
        scenario?.close()
        app.switchTo(TreeCatalog.ORIGINAL.id)
        Thread.sleep(3_500)
        (app.catalog.remove(tree.id) as? CatalogResult.Ok)?.let { app.deleteFilesOf(it.tree) }
    }

    private fun openSharing() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(8_000) { compose.onAllNodesWithTag(NavMoreTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(NavMoreTag).performClick()
        compose.onNodeWithTag(MoreSettingsTag).performClick()
        compose.onNodeWithTag(SharingButtonTag).performScrollTo().performClick()
    }

    /** The dialog loads what it shows after it opens, so the button is not there for the first instant. */
    private fun waitForTurnOn() {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(SharingTurnOnTag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun turningSharingOnWithNobodyPairedSaysWhatToDoNext() {
        openSharing()
        waitForTurnOn()
        compose.onNodeWithTag(SharingTurnOnTag).assertIsDisplayed()
        assertFalse(runBlocking { app.container.treeSync.isShared() })

        compose.onNodeWithTag(SharingTurnOnTag).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Nobody to share with yet", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(runBlocking { app.container.treeSync.isShared() })
        assertTrue(runBlocking { app.container.treeSync.members().isEmpty() })
    }

    @Test fun onlyPeoplePairedInPersonAreOfferedAndSwitchingOneOnMakesThemAMember() {
        runBlocking {
            val c = app.container
            c.familyRepository.addPerson(Person(id = "kiran", name = "Kiran"))
            c.familyRepository.addPerson(Person(id = "meera", name = "Meera"))
            c.kutumbRepository.saveContact(TrustedContact("kiran", "a".repeat(64), pairedAt = 1, pairingMethod = PairingMethod.DIRECT))
            c.kutumbRepository.saveContact(
                TrustedContact("meera", "b".repeat(64), pairedAt = 1, pairingMethod = PairingMethod.VOUCHED, vouchedByPersonId = "kiran"),
            )
        }
        openSharing()
        waitForTurnOn()
        compose.onNodeWithTag(SharingTurnOnTag).performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(SharingMemberTagPrefix + "kiran").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Kiran").assertIsDisplayed()
        compose.onAllNodesWithTag(SharingMemberTagPrefix + "meera").assertCountIsZero()

        compose.onNodeWithTag(SharingMemberTagPrefix + "kiran").performClick()
        compose.waitUntil(5_000) { runBlocking { app.container.treeSync.isMember("kiran") } }
        assertEquals(listOf("kiran"), runBlocking { app.container.treeSync.members() })

        // Switching them off again stops them being a member.
        compose.onNodeWithTag(SharingMemberTagPrefix + "kiran").performClick()
        compose.waitUntil(5_000) { !runBlocking { app.container.treeSync.isMember("kiran") } }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountIsZero() =
        assertEquals(0, fetchSemanticsNodes().size)
}

package com.vibethroughcode.ftree.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.TreeCatalog
import com.vibethroughcode.ftree.kutumb.treesync.PendingInvite
import com.vibethroughcode.ftree.ui.more.JoinNotNowTag
import com.vibethroughcode.ftree.ui.more.JoinOpenTag
import com.vibethroughcode.ftree.ui.more.MoreInvitationTagPrefix
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An invitation to a shared tree, as the reader meets it: a row at the top of More, then one plain question.
 * Saying yes with family sync off is refused with a reason and changes nothing; the real join, which needs
 * the relays, is covered on real databases in `TreeSyncCoordinatorTest`.
 */
@RunWith(AndroidJUnit4::class)
class JoinInvitationFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app: FTreeApplication = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val invite = PendingInvite(
        tree = "join-ui-test", name = "Ravindran family", people = 42, from = "asha", you = "ravi",
        senderKey = "a".repeat(64), fromName = "Asha", viaTree = TreeCatalog.ORIGINAL.id, receivedAt = 1L,
    )

    @Before fun anInvitationIsWaiting() {
        app.switchTo(TreeCatalog.ORIGINAL.id)
        app.container.invitations.dismiss(invite.tree)
        assertTrue(app.container.invitations.receive(invite))
    }

    @After fun cleanUp() {
        scenario?.close()
        app.container.invitations.dismiss(invite.tree)
    }

    private fun openMore() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(8_000) { compose.onAllNodesWithTag(NavMoreTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(NavMoreTag).performClick()
    }

    @Test fun theInvitationIsARowOnMoreAndNotNowForgetsIt() {
        openMore()
        compose.onNodeWithTag(MoreInvitationTagPrefix + invite.tree).assertIsDisplayed()
        compose.onNodeWithText("Asha shared a tree with you").assertIsDisplayed()

        compose.onNodeWithTag(MoreInvitationTagPrefix + invite.tree).performClick()
        compose.onNodeWithText("People in it: 42", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(JoinNotNowTag).performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(MoreInvitationTagPrefix + invite.tree).fetchSemanticsNodes().isEmpty() }
        assertTrue(app.container.invitations.pending.value.none { it.tree == invite.tree })
    }

    @Test fun sayingYesWithSyncOffExplainsWhyAndChangesNothing() {
        assertTrue("these tests assume sync is off, as it is by default", !app.syncPreferences.enabled.value)
        val treesBefore = app.catalog.trees.map { it.id }
        openMore()
        compose.onNodeWithTag(MoreInvitationTagPrefix + invite.tree).performClick()
        compose.onNodeWithTag(JoinOpenTag).performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Family sync is off", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("no tree was made", treesBefore, app.catalog.trees.map { it.id })
        assertTrue("and the invitation still waits", app.container.invitations.pending.value.any { it.tree == invite.tree })
    }
}

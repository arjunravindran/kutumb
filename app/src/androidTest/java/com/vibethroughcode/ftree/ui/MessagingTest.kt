package com.vibethroughcode.ftree.ui

import com.vibethroughcode.ftree.ui.more.MoreNetworkTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.MainActivity
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.Bip340Scheme
import com.vibethroughcode.ftree.kutumb.LocalIdentity
import com.vibethroughcode.ftree.kutumb.messaging.DeliveryStatus
import com.vibethroughcode.ftree.kutumb.messaging.Message
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import com.vibethroughcode.ftree.ui.network.NetworkListTag
import com.vibethroughcode.ftree.ui.network.ThreadInputTag
import com.vibethroughcode.ftree.ui.network.ThreadLockedTag
import com.vibethroughcode.ftree.ui.network.ThreadSendTag
import com.vibethroughcode.ftree.ui.network.networkMessageTag
import com.vibethroughcode.ftree.ui.network.networkPreviewTag
import com.vibethroughcode.ftree.ui.network.threadResendTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A conversation with someone met in person: write, see that it was not sent, hear back, read it. */
@RunWith(AndroidJUnit4::class)
class MessagingTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app: FTreeApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FTreeApplication

    private val kutumb get() = app.container.kutumbRepository

    private lateinit var me: Person
    private lateinit var devika: Person
    private lateinit var asha: Person

    @Before
    fun seed() {
        app.container.updatePreferences.setEnabled(false)
        app.syncPreferences.setEnabled(false)
        app.container.database.clearAllTables()
        runBlocking {
            kutumb.clearAll()
            me = app.container.familyRepository.addPerson(Person(name = "Yash Rao"))
            devika = app.container.familyRepository.addPerson(Person(name = "Devika Nair"))
            asha = app.container.familyRepository.addPerson(Person(name = "Asha Menon"))
            kutumb.setIdentity(LocalIdentity(me.id, Bip340Scheme.generateKeyPair()))
            kutumb.saveContact(TrustedContact(devika.id, Bip340Scheme.generateKeyPair().publicKey, pairedAt = 1, pairingMethod = PairingMethod.DIRECT))
            kutumb.saveContact(
                TrustedContact(asha.id, Bip340Scheme.generateKeyPair().publicKey, pairedAt = 1, pairingMethod = PairingMethod.VOUCHED, vouchedByPersonId = devika.id),
            )
        }
        rule.onNodeWithTag(NavMoreTag).performClick()
        rule.onNodeWithTag(MoreNetworkTag).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(NetworkListTag).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aMessageIsKeptWhenNothingCanSendItAndCanBeSentAgain() {
        rule.onNodeWithTag(networkMessageTag(devika.id)).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(ThreadInputTag).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("No messages with Devika Nair yet. Say hello.").assertIsDisplayed()

        rule.onNodeWithTag(ThreadInputTag).performTextInput("Dinner on Sunday?")
        rule.onNodeWithTag(ThreadSendTag).performClick()

        // Family sync is off, so it is saved here and says so rather than pretending.
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Dinner on Sunday?").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("not sent", substring = true).assertIsDisplayed()
        val saved = runBlocking { kutumb.observeMessages().first().single() }
        assertEquals(DeliveryStatus.NOT_SENT, saved.deliveryStatus)
        assertEquals(devika.id, saved.threadPersonId)
        assertEquals(me.id, saved.senderId)

        // Sending again with sync still off changes nothing, and does not lose it.
        rule.onNodeWithTag(threadResendTag(saved.id)).performClick()
        rule.waitForIdle()
        assertEquals(DeliveryStatus.NOT_SENT, runBlocking { kutumb.observeMessages().first().single().deliveryStatus })
    }

    @Test
    fun whatArrivesWhileTheConversationIsOpenIsShownAndReadAndPreviewedInTheList() {
        rule.onNodeWithTag(networkMessageTag(devika.id)).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(ThreadInputTag).fetchSemanticsNodes().isNotEmpty() }

        runBlocking {
            kutumb.saveMessage(Message("${devika.id}/1", devika.id, devika.id, "Sunday works. 8?", System.currentTimeMillis(), DeliveryStatus.RECEIVED, isRead = false))
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Sunday works. 8?").fetchSemanticsNodes().isNotEmpty() }
        // Open on screen, so it is marked read as it appears.
        rule.waitUntil(5_000) { runBlocking { kutumb.observeMessages().first() }.all { it.isRead } }

        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        // A row that can be tapped merges what is inside it, so the preview is looked for unmerged.
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(networkPreviewTag(devika.id), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(networkPreviewTag(devika.id), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Sunday works. 8?", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun someoneOnlyVouchedForCannotBeMessaged() {
        // The list shows a lock, not a button; and a thread reached some other way says why it is shut.
        assertTrue(runBlocking { kutumb.observeContacts().first() }.any { it.personId == asha.id && !it.messagingEnabled })
        rule.onAllNodesWithTag(ThreadLockedTag).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        rule.onNodeWithTag(networkMessageTag(asha.id), useUnmergedTree = true).assertIsDisplayed()
    }
}

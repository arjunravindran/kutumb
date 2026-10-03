package com.vibethroughcode.ftree.kutumb

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.kutumb.messaging.DeliveryStatus
import com.vibethroughcode.ftree.kutumb.messaging.Message
import com.vibethroughcode.ftree.kutumb.messaging.MessageNotifier
import com.vibethroughcode.ftree.kutumb.messaging.OpenThread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The note for a new message: when it is posted, what it says, and when it is not. */
@RunWith(AndroidJUnit4::class)
class MessageNotificationTest {
    private val app: FTreeApplication = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notifier get() = app.container.messageNotifier
    private val preferences get() = app.container.messageNotificationPreferences
    private val wasOn = preferences.isEnabled()

    private val tag get() = "${app.container.tree.id}/kiran"

    private fun inbound(id: String = "kiran/1", body: String = "see you at 8") =
        Message(id, "kiran", "kiran", body, 1, DeliveryStatus.RECEIVED, isRead = false)

    private fun mine() = Message("m1", "kiran", "me", "ok", 2, DeliveryStatus.QUEUED, isRead = true)

    private fun ours() = manager.activeNotifications.filter { it.id == MessageNotifier.NOTIFICATION && it.tag == tag }

    /** The system lists a posted note a moment later, so a positive check waits for it (a negative one cannot, and checks after one). */
    private fun awaitNotes(count: Int = 1): List<android.service.notification.StatusBarNotification> {
        val until = System.currentTimeMillis() + 3_000
        while (ours().size != count && System.currentTimeMillis() < until) Thread.sleep(50)
        return ours()
    }

    @Before fun clean() {
        // Granted the way a person would, for this run only; the app itself never asks for it here.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
        OpenThread.personId = null
    }

    @After fun putBack() {
        preferences.setEnabled(wasOn)
        OpenThread.personId = null
        manager.cancelAll()
    }

    @Test fun aMessageEarnsANoteOnlyOnceTheReaderSwitchedThemOn() = runBlocking {
        preferences.setEnabled(false)
        notifier.onReceived(inbound())
        assertTrue(ours().isEmpty())

        preferences.setEnabled(true)
        notifier.onReceived(inbound())
        val note = awaitNotes().single().notification
        assertEquals("see you at 8", note.extras.getCharSequence("android.text").toString())
        // The lock screen gets the other, empty-handed version.
        assertNotNull(note.publicVersion)
        assertEquals(app.getString(com.vibethroughcode.ftree.R.string.message_public_title), note.publicVersion.extras.getCharSequence("android.title").toString())
    }

    @Test fun oneNotePerConversationWhichAFurtherMessageReplaces() = runBlocking {
        preferences.setEnabled(true)
        notifier.onReceived(inbound("kiran/1", "first"))
        notifier.onReceived(inbound("kiran/2", "second"))
        awaitNotes()
        Thread.sleep(300)
        assertEquals("second", awaitNotes().single().notification.extras.getCharSequence("android.text").toString())
    }

    @Test fun whatTheReaderWroteAndTheConversationOnScreenEarnNothing() = runBlocking {
        preferences.setEnabled(true)
        notifier.onReceived(mine())
        assertTrue(ours().isEmpty())

        OpenThread.personId = "kiran"
        notifier.onReceived(inbound())
        assertTrue(ours().isEmpty())

        OpenThread.personId = null
        notifier.onReceived(inbound())
        assertEquals(1, awaitNotes().size)
    }

    @Test fun openingTheConversationTakesItsNoteDown() = runBlocking {
        preferences.setEnabled(true)
        notifier.onReceived(inbound())
        assertEquals(1, awaitNotes().size)
        notifier.cancel("kiran")
        assertTrue(ours().isEmpty())
    }

    @Test fun theNoteOpensTheConversationInTheTreeItBelongsTo() = runBlocking {
        preferences.setEnabled(true)
        notifier.onReceived(inbound())
        val intent = awaitNotes().single().notification.contentIntent
        assertNotNull(intent)
        // The tap names the tree and the person; MainActivity switches to the one and opens the other.
        assertEquals(app.packageName, intent.creatorPackage)
    }
}

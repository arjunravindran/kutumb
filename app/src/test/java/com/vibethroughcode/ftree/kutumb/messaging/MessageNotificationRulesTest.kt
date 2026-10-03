package com.vibethroughcode.ftree.kutumb.messaging

import com.vibethroughcode.ftree.kutumb.InMemoryKutumbRepository
import com.vibethroughcode.ftree.kutumb.sync.MessageSyncHandler
import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.UnsignedEvent
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNotificationRulesTest {
    private fun message(from: String, thread: String = "kiran") =
        Message("id-$from", thread, from, "hi", 1, if (from == thread) DeliveryStatus.RECEIVED else DeliveryStatus.QUEUED, isRead = from != thread)

    private val theirs = message("kiran")
    private val mine = message("me")

    private fun post(switchOn: Boolean = true, allowed: Boolean = true, open: String? = null, m: Message = theirs) =
        MessageNotificationRules.shouldPost(switchOn, allowed, open, m)

    @Test fun aNewMessageFromSomeoneElseEarnsANoteWhenAskedFor() {
        assertTrue(post())
    }

    @Test fun nothingIsPostedUnlessTheReaderAskedAndAndroidAllows() {
        assertFalse(post(switchOn = false))
        assertFalse(post(allowed = false))
        assertFalse(post(switchOn = false, allowed = false))
    }

    @Test fun whatTheReaderWroteThemselvesNeverEarnsANote() {
        assertFalse(post(m = mine))
    }

    @Test fun theConversationOnScreenNeedsNoNoteButAnotherOneDoes() {
        assertFalse(post(open = "kiran"))
        assertTrue(post(open = "devika"))
        assertTrue(post(open = null))
    }

    // ---- the handler tells the notifier only about what it kept

    private val kiran = TrustedContact("kiran", "a".repeat(64), pairedAt = 1, pairingMethod = PairingMethod.DIRECT)
    private val asha = TrustedContact("asha", "b".repeat(64), pairedAt = 1, pairingMethod = PairingMethod.VOUCHED, vouchedByPersonId = "kiran")
    private fun rumor() = UnsignedEvent("a".repeat(64), 1, SyncEnvelope.RUMOR_KIND, emptyList(), "")
    private fun envelope(id: String) = Messaging.envelope(Message(id, "x", "x", "text $id", 5, DeliveryStatus.QUEUED, true))

    @Test fun theNotifierHearsOfEachKeptMessageOnceAndOfNothingElse() = runTest {
        val told = mutableListOf<Message>()
        val handler = MessageSyncHandler(InMemoryKutumbRepository(), clock = { 100 }) { told += it }

        handler.onEnvelope(envelope("w1"), kiran, rumor())
        handler.onEnvelope(envelope("w1"), kiran, rumor()) // redelivered: already held
        handler.onEnvelope(envelope("w2"), asha, rumor())   // not met in person: dropped
        handler.onEnvelope(envelope("w3"), kiran, rumor())

        assertEquals(listOf("kiran/w1", "kiran/w3"), told.map { it.id })
        assertTrue(told.all { it.isFromThem && !it.isRead })
    }
}

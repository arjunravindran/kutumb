package com.vibethroughcode.ftree.kutumb

import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.messaging.DeliveryStatus
import com.vibethroughcode.ftree.kutumb.messaging.Message
import com.vibethroughcode.ftree.kutumb.messaging.Messaging
import com.vibethroughcode.ftree.kutumb.sync.MessageSyncHandler
import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncPublisher
import com.vibethroughcode.ftree.kutumb.sync.SyncType
import com.vibethroughcode.ftree.kutumb.sync.UnsignedEvent
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import com.vibethroughcode.ftree.ui.network.MessageThreadViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Writing, sending again, and receiving: the conversation as the phone keeps it. */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagingFlowTest {
    private class FakePublisher(var accepts: Boolean) : SyncPublisher {
        val sent = mutableListOf<Pair<SyncEnvelope, Collection<String>>>()
        override suspend fun publish(envelope: SyncEnvelope, personIds: Collection<String>): Boolean {
            sent += envelope to personIds
            return accepts
        }
    }

    private val kutumb = InMemoryKutumbRepository()
    private val publisher = FakePublisher(accepts = true)
    private var ids = 0
    private val kiran = Person(id = "kiran", name = "Kiran")

    private fun contact(id: String, method: PairingMethod, key: Char) = TrustedContact(
        id, key.toString().repeat(64), pairedAt = 1, pairingMethod = method,
        vouchedByPersonId = if (method == PairingMethod.VOUCHED) "kiran" else null,
    )

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMain() = Dispatchers.resetMain()

    private suspend fun thread(with: String = "kiran", met: Boolean = true): MessageThreadViewModel {
        kutumb.setIdentity(LocalIdentity("me", Bip340Scheme.generateKeyPair()))
        kutumb.saveContact(contact(with, if (met) PairingMethod.DIRECT else PairingMethod.VOUCHED, if (with == "kiran") 'a' else 'b'))
        return MessageThreadViewModel(MutableStateFlow(kiran), kutumb, publisher, with, clock = { 1_000L + ids }, newId = { "m${++ids}" })
    }

    // ---- sending

    @Test fun aMessageHandedToTheOutboxIsQueued() = runTest {
        val vm = thread()
        assertTrue(vm.send("  hello there  "))
        val saved = kutumb.observeMessages().first().single()
        assertEquals("hello there", saved.body)
        assertEquals(DeliveryStatus.QUEUED, saved.deliveryStatus)
        assertEquals("me", saved.senderId)
        assertEquals("kiran", saved.threadPersonId)
        assertTrue(saved.isRead)
        // It went to Kiran, as the envelope the other phone will decode.
        val (envelope, to) = publisher.sent.single()
        assertEquals(SyncType.MESSAGE, envelope.type)
        assertEquals(listOf("kiran"), to.toList())
        assertEquals(saved.id, Messaging.decode(envelope.payload)!!.id)
    }

    @Test fun aMessageNobodyCouldSendIsKeptAndCanBeSentAgain() = runTest {
        publisher.accepts = false
        val vm = thread()
        vm.send("are you there?")
        val first = kutumb.observeMessages().first().single()
        assertEquals(DeliveryStatus.NOT_SENT, first.deliveryStatus)

        publisher.accepts = true
        vm.resend(first.id)
        assertEquals(DeliveryStatus.QUEUED, kutumb.observeMessages().first().single().deliveryStatus)
        assertEquals(2, publisher.sent.size)

        // A queued one is not sent a second time.
        vm.resend(first.id)
        assertEquals(2, publisher.sent.size)
    }

    @Test fun blankTextIsNotTakenAndNeitherIsASomeoneNotMetInPerson() = runTest {
        val vm = thread()
        assertFalse(vm.send("   "))
        assertFalse(vm.send("x".repeat(Messaging.MAX_BODY + 1)))
        assertTrue(kutumb.observeMessages().first().isEmpty())

        val vouchedOnly = thread(with = "asha", met = false)
        assertTrue(vouchedOnly.send("hi")) // the screen would not offer it; the model still refuses to store it
        assertTrue(kutumb.observeMessages().first().isEmpty())
        assertTrue(publisher.sent.isEmpty())
    }

    @Test fun openingAConversationMarksTheirMessagesRead() = runTest {
        val vm = thread()
        kutumb.saveMessage(Message("kiran/1", "kiran", "kiran", "hi", 1, DeliveryStatus.RECEIVED, isRead = false))
        kutumb.saveMessage(Message("kiran/2", "other", "other", "psst", 2, DeliveryStatus.RECEIVED, isRead = false))
        val job = vm.state.launchIn(backgroundScope)
        assertEquals(1, vm.state.first { it.loaded }.unread)
        vm.markRead()
        assertEquals(0, vm.state.first { it.unread == 0 }.unread)
        // Someone else's conversation is untouched.
        assertFalse(kutumb.observeMessages().first().single { it.threadPersonId == "other" }.isRead)
        job.cancel()
    }

    @Test fun theStateKnowsWhetherThePairHasMet() = runTest {
        val met = thread()
        val job = met.state.launchIn(backgroundScope)
        assertTrue(met.state.first { it.loaded }.canMessage)
        job.cancel()

        val vouched = thread(with = "asha", met = false)
        val job2 = vouched.state.launchIn(backgroundScope)
        assertFalse(vouched.state.first { it.loaded }.canMessage)
        job2.cancel()
    }

    // ---- receiving

    private fun rumor() = UnsignedEvent("a".repeat(64), 1, SyncEnvelope.RUMOR_KIND, emptyList(), "")

    private fun inbound(id: String, body: String, at: Long = 10) =
        Messaging.envelope(Message(id, "x", "x", body, at, DeliveryStatus.QUEUED, true))

    @Test fun aMessageFromSomeoneMetInPersonIsKeptUnreadOnce() = runTest {
        val handler = MessageSyncHandler(kutumb, clock = { 500 })
        val sender = contact("kiran", PairingMethod.DIRECT, 'a')
        handler.onEnvelope(inbound("w1", "dinner?"), sender, rumor())
        handler.onEnvelope(inbound("w1", "dinner?"), sender, rumor()) // redelivered

        val kept = kutumb.observeMessages().first().single()
        assertEquals("dinner?", kept.body)
        assertEquals("kiran", kept.threadPersonId)
        assertFalse(kept.isRead)
        assertEquals(DeliveryStatus.RECEIVED, kept.deliveryStatus)
    }

    @Test fun nothingIsKeptFromAVouchedContactOrForAnotherKindOfMessage() = runTest {
        val handler = MessageSyncHandler(kutumb, clock = { 500 })
        handler.onEnvelope(inbound("w1", "hi"), contact("asha", PairingMethod.VOUCHED, 'b'), rumor())
        handler.onEnvelope(SyncEnvelope(SyncType.FACT, linkedMapOf("id" to "x", "body" to "y", "at" to 1L)), contact("kiran", PairingMethod.DIRECT, 'a'), rumor())
        handler.onEnvelope(SyncEnvelope(SyncType.MESSAGE, linkedMapOf("id" to "x")), contact("kiran", PairingMethod.DIRECT, 'a'), rumor())
        assertTrue(kutumb.observeMessages().first().isEmpty())
    }

    @Test fun twoPeopleUsingTheSameMessageIdDoNotOverwriteEachOther() = runTest {
        val handler = MessageSyncHandler(kutumb, clock = { 500 })
        handler.onEnvelope(inbound("same", "from kiran"), contact("kiran", PairingMethod.DIRECT, 'a'), rumor())
        handler.onEnvelope(inbound("same", "from devika"), contact("devika", PairingMethod.DIRECT, 'c'), rumor())
        assertEquals(setOf("from kiran", "from devika"), kutumb.observeMessages().first().map { it.body }.toSet())
    }
}

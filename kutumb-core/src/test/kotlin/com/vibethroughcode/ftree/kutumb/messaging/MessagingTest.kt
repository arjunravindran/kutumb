package com.vibethroughcode.ftree.kutumb.messaging

import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncType
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingTest {
    private val key = "a".repeat(64)
    private val met = TrustedContact("kiran", key, pairedAt = 1, pairingMethod = PairingMethod.DIRECT)
    private val vouched = TrustedContact("asha", "b".repeat(64), pairedAt = 1, pairingMethod = PairingMethod.VOUCHED, vouchedByPersonId = "kiran")

    private fun message(
        id: String = "m1",
        from: String = "me",
        body: String = "hello",
        at: Long = 100,
        status: DeliveryStatus = DeliveryStatus.QUEUED,
        read: Boolean = true,
    ) = Message(id, "kiran", from, body, at, status, read)

    @Test fun aMessageSurvivesTheWire() {
        val sent = Messaging.envelope(message(body = "Dinner at 8? 🍛"))
        assertEquals(SyncType.MESSAGE, sent.type)
        val received = SyncEnvelope.parse(sent.toJson())!!
        assertEquals(WireMessage("m1", "Dinner at 8? 🍛", 100), Messaging.decode(received.payload))
    }

    @Test fun anythingMalformedDecodesToNull() {
        val good = Messaging.envelope(message()).payload
        assertNotNull(Messaging.decode(good))
        assertNull(Messaging.decode(good - "id"))
        assertNull(Messaging.decode(good + ("body" to "   ")))
        assertNull(Messaging.decode(good + ("body" to "x".repeat(Messaging.MAX_BODY + 1))))
        assertNull(Messaging.decode(good + ("id" to "x".repeat(Messaging.MAX_ID + 1))))
        assertNull(Messaging.decode(good + ("at" to "100")))
        assertNull(Messaging.decode(good + ("at" to -5L)))
        assertNotNull(Messaging.decode(good + ("body" to "x".repeat(Messaging.MAX_BODY))))
    }

    @Test fun onlyAPersonMetInPersonMayWrite() {
        val wire = WireMessage("w1", "hi", 100)
        val kept = Messaging.acceptInbound(met, wire, now = 200, heldIds = emptySet())!!
        assertEquals("kiran", kept.threadPersonId)
        assertEquals("kiran", kept.senderId)
        assertEquals(DeliveryStatus.RECEIVED, kept.deliveryStatus)
        assertFalse(kept.isRead)
        assertTrue(kept.isFromThem)
        assertNull(Messaging.acceptInbound(vouched, wire, now = 200, heldIds = emptySet()))
    }

    @Test fun aRotatedKeyKeepsTheRightToWrite() {
        val rotated = met.withRotatedKey("c".repeat(64))
        assertNotNull(Messaging.acceptInbound(rotated, WireMessage("w1", "new phone", 1), 2, emptySet()))
    }

    @Test fun aRedeliveryIsDroppedAndTwoPeopleCannotShareAnId() {
        val wire = WireMessage("same", "hi", 1)
        val first = Messaging.acceptInbound(met, wire, 2, emptySet())!!
        assertNull(Messaging.acceptInbound(met, wire, 2, setOf(first.id)))
        // Another person met in person, sending the same wire id, neither collides with nor overwrites the first.
        val other = TrustedContact("devika", "d".repeat(64), pairedAt = 1, pairingMethod = PairingMethod.DIRECT)
        val second = Messaging.acceptInbound(other, wire, 2, setOf(first.id))!!
        assertTrue(first.id != second.id)
    }

    @Test fun aMessageCannotClaimToBeFromTheFuture() {
        val kept = Messaging.acceptInbound(met, WireMessage("w", "hi", Long.MAX_VALUE / 2), now = 1_000, heldIds = emptySet())!!
        assertEquals(1_000 + Messaging.MAX_CLOCK_SKEW_MS, kept.sentAt)
    }

    @Test fun aSummaryIsTheNewestMessageAndHowManyOfTheirsAreUnread() {
        assertNull(Messaging.summary(emptyList()))
        val thread = listOf(
            message("a", from = "kiran", at = 1, status = DeliveryStatus.RECEIVED, read = true),
            message("b", from = "kiran", at = 2, status = DeliveryStatus.RECEIVED, read = false),
            message("c", from = "me", at = 3),
            message("d", from = "kiran", at = 4, status = DeliveryStatus.RECEIVED, read = false),
        )
        val (newest, unread) = Messaging.summary(thread)!!
        assertEquals("d", newest.id)
        assertEquals(2, unread)
        // Ones I wrote are never unread, whatever their flag.
        assertEquals(0, Messaging.summary(listOf(message(read = false)))!!.second)
    }

    @Test fun aMessageIsRefusedWhenItsPartsAreNonsense() {
        listOf(
            { message(body = " ") },
            { message(body = "x".repeat(Messaging.MAX_BODY + 1)) },
            { message(id = "") },
            { message(at = -1) },
        ).forEach { make -> assertTrue(runCatching { make() }.isFailure) }
    }
}

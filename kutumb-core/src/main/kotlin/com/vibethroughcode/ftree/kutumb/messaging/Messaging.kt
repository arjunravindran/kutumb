package com.vibethroughcode.ftree.kutumb.messaging

import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncType
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact

enum class DeliveryStatus {
    /** Written here, and not handed to anything that can send it (sync was off, or no relay). Can be sent again. */
    NOT_SENT,

    /** Handed to the outbox, which keeps trying until a relay takes it. Not a promise that it has been read. */
    QUEUED,

    /** Arrived from the other person. */
    RECEIVED,
}

/**
 * One message in a conversation with one other person.
 *
 * A conversation is identified by the other person alone ([threadPersonId]): this phone is always the
 * other end, so the spec's pair of ids needs no table of its own. [senderId] is whoever wrote it,
 * either this phone's person or the other.
 */
data class Message(
    val id: String,
    val threadPersonId: String,
    val senderId: String,
    val body: String,
    val sentAt: Long,
    val deliveryStatus: DeliveryStatus,
    /** Whether the reader has seen it. Messages they wrote are read by definition. */
    val isRead: Boolean,
) {
    init {
        require(id.isNotBlank() && id.length <= Messaging.MAX_ID) { "bad message id" }
        require(threadPersonId.isNotBlank()) { "threadPersonId is blank" }
        require(senderId.isNotBlank()) { "senderId is blank" }
        require(body.isNotBlank() && body.length <= Messaging.MAX_BODY) { "bad message body" }
        require(sentAt >= 0) { "sentAt is negative" }
    }

    val isFromThem: Boolean get() = senderId == threadPersonId
}

/** What travels: an id, the text and the sender's clock. Who sent it is the gift wrap's author, not a field. */
data class WireMessage(val id: String, val body: String, val at: Long)

object Messaging {
    const val MAX_BODY = 2_000
    const val MAX_ID = 200

    /** A message cannot claim to be from further ahead than this, so a wrong clock cannot pin one to the top for ever. */
    const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L

    fun envelope(message: Message) = SyncEnvelope(
        SyncType.MESSAGE,
        linkedMapOf("id" to message.id, "body" to message.body, "at" to message.sentAt),
    )

    fun decode(payload: Map<String, Any?>): WireMessage? {
        val id = (payload["id"] as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_ID } ?: return null
        val body = (payload["body"] as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_BODY } ?: return null
        val at = (payload["at"] as? Long)?.takeIf { it >= 0 } ?: return null
        return WireMessage(id, body, at)
    }

    /**
     * Whether, and as what, to keep a message that arrived from [sender]. Null to drop it.
     *
     * Only somebody met in person may write (a vouched contact never unlocks it, a rotated key does
     * not take it away). The stored id carries the sender, so nobody can reuse another person's id to
     * overwrite what they said. A message already held is dropped, which makes redelivery harmless.
     */
    fun acceptInbound(sender: TrustedContact, wire: WireMessage, now: Long, heldIds: Set<String>): Message? {
        if (!sender.messagingEnabled) return null
        val id = "${sender.personId}/${wire.id}"
        if (id.length > MAX_ID || id in heldIds) return null
        return Message(
            id = id,
            threadPersonId = sender.personId,
            senderId = sender.personId,
            body = wire.body,
            sentAt = minOf(wire.at, now + MAX_CLOCK_SKEW_MS),
            deliveryStatus = DeliveryStatus.RECEIVED,
            isRead = false,
        )
    }

    /** What to show for a conversation in a list: the newest message and how many of theirs are unread. */
    fun summary(messages: List<Message>): Pair<Message, Int>? {
        val newest = messages.maxWithOrNull(compareBy({ it.sentAt }, { it.id })) ?: return null
        return newest to messages.count { it.isFromThem && !it.isRead }
    }
}

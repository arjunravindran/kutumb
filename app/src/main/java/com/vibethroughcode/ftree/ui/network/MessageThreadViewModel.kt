package com.vibethroughcode.ftree.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.messaging.DeliveryStatus
import com.vibethroughcode.ftree.kutumb.messaging.Message
import com.vibethroughcode.ftree.kutumb.messaging.Messaging
import com.vibethroughcode.ftree.kutumb.sync.NoSyncPublisher
import com.vibethroughcode.ftree.kutumb.sync.SyncPublisher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class ThreadUiState(
    val loaded: Boolean = false,
    /** The person on the other end; null if the tree no longer has them. */
    val other: Person? = null,
    /** Whether this pair has met in person, which is all that unlocks messaging. */
    val canMessage: Boolean = false,
    /** Oldest first. */
    val messages: List<Message> = emptyList(),
) {
    val unread: Int get() = messages.count { it.isFromThem && !it.isRead }
}

/**
 * One conversation. A message is saved before anything tries to send it, so it is never lost with
 * the connection: it is [DeliveryStatus.QUEUED] once the outbox has it, and [DeliveryStatus.NOT_SENT]
 * (with a way to send it again) when sync was off or had no relay.
 */
class MessageThreadViewModel(
    person: Flow<Person?>,
    private val kutumb: KutumbRepository,
    private val publisher: SyncPublisher = NoSyncPublisher,
    val personId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    val state: StateFlow<ThreadUiState> = combine(
        person, kutumb.observeContacts(), kutumb.observeMessages(),
    ) { person, contacts, all ->
        ThreadUiState(
            loaded = true,
            other = person,
            canMessage = contacts.any { it.personId == personId && it.messagingEnabled },
            messages = all.filter { it.threadPersonId == personId },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThreadUiState())

    /** True if the text was taken (and is being sent); false for blank or too-long text, or a pair that has not met. */
    fun send(text: String): Boolean {
        val body = text.trim()
        if (body.isEmpty() || body.length > Messaging.MAX_BODY) return false
        viewModelScope.launch {
            val me = kutumb.observeIdentity().first()?.personId ?: return@launch
            if (kutumb.observeContacts().first().none { it.personId == personId && it.messagingEnabled }) return@launch
            val message = Message(newId(), personId, me, body, clock(), DeliveryStatus.NOT_SENT, isRead = true)
            kutumb.saveMessage(message)
            deliver(message)
        }
        return true
    }

    /** Tries again with a message that was never handed on. */
    fun resend(messageId: String) {
        viewModelScope.launch {
            val message = kutumb.observeMessages().first()
                .firstOrNull { it.id == messageId && it.threadPersonId == personId && !it.isFromThem && it.deliveryStatus == DeliveryStatus.NOT_SENT }
                ?: return@launch
            deliver(message)
        }
    }

    fun markRead() {
        viewModelScope.launch { kutumb.markThreadRead(personId) }
    }

    private suspend fun deliver(message: Message) {
        if (publisher.publish(Messaging.envelope(message), listOf(personId))) {
            kutumb.saveMessage(message.copy(deliveryStatus = DeliveryStatus.QUEUED))
        }
    }
}

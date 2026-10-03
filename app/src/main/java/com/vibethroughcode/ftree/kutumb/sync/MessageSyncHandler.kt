package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.messaging.Messaging
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.flow.first

/**
 * Keeps a message from somebody met in person. The rules (who may write, no overwriting, no
 * claiming to be from the future) are `Messaging.acceptInbound`; this reads what is held, asks them,
 * and stores the result. Quiet on anything it refuses, like the other handlers.
 */
class MessageSyncHandler(
    private val repository: KutumbRepository,
    private val clock: () -> Long,
) : SyncHandler {
    override suspend fun onEnvelope(envelope: SyncEnvelope, sender: TrustedContact, rumor: UnsignedEvent) {
        if (envelope.type != SyncType.MESSAGE) return
        val wire = Messaging.decode(envelope.payload) ?: return
        val held = repository.observeMessages().first().mapTo(HashSet()) { it.id }
        val message = Messaging.acceptInbound(sender, wire, clock(), held) ?: return
        repository.saveMessage(message)
    }
}

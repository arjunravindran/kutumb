package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.recovery.RotationOutcome
import com.vibethroughcode.ftree.kutumb.recovery.RotationRules
import com.vibethroughcode.ftree.kutumb.recovery.RotationSync
import com.vibethroughcode.ftree.kutumb.recovery.TrustStore
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.flow.first

/**
 * Applies a relative's word that somebody has a new key. The rules (valid signature, a voucher this
 * phone trusts, the old key still being the current one) are `RotationRules`; this reads the trust
 * store, asks them, and writes the result.
 *
 * The sender need not be the voucher: a rotation is passed along by whoever trusts the voucher, and
 * it is the voucher's signature inside, not the sender, that is checked. Idempotent and quiet: a
 * redelivery, a stale event or one from somebody this phone does not trust changes nothing.
 */
class RotationSyncHandler(
    private val repository: KutumbRepository,
    private val scheme: SignatureScheme,
) : SyncHandler {
    override suspend fun onEnvelope(envelope: SyncEnvelope, sender: TrustedContact, rumor: UnsignedEvent) {
        if (envelope.type != SyncType.ROTATION) return
        val event = RotationSync.decode(envelope.payload) ?: return
        val store = TrustStore(repository.observeContacts().first())
        val outcome = RotationRules.apply(store, event, scheme) as? RotationOutcome.Accepted ?: return
        // The store was valid before, and `with` replaced exactly one contact; save that one.
        repository.saveContact(outcome.store[event.personId] ?: return)
        repository.saveRotation(event)
    }
}

/** Offers every message to each handler in turn; each acts on the types it knows and ignores the rest. */
class CompositeSyncHandler(private val handlers: List<SyncHandler>) : SyncHandler {
    constructor(vararg handlers: SyncHandler) : this(handlers.toList())

    override suspend fun onEnvelope(envelope: SyncEnvelope, sender: TrustedContact, rumor: UnsignedEvent) {
        for (handler in handlers) handler.onEnvelope(envelope, sender, rumor)
    }
}

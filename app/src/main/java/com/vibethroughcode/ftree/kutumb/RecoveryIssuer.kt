package com.vibethroughcode.ftree.kutumb

import com.vibethroughcode.ftree.kutumb.recovery.RotationSync
import com.vibethroughcode.ftree.kutumb.sync.SyncPublisher
import com.vibethroughcode.ftree.kutumb.trust.IdentityRotationEvent
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme
import kotlinx.coroutines.flow.first

enum class RecoveryRefusal {
    /** This phone does not yet know who it is, so it has no key to vouch with. */
    NO_IDENTITY,

    /** Nobody trusted by this phone is that person. */
    UNKNOWN_PERSON,

    /** A phone cannot vouch for a new key of its own owner. */
    IS_ME,

    /** The key just met is already the person's own current key; nothing has changed. */
    SAME_KEY,

    /** The key just met already belongs to somebody trusted here, or to this phone. */
    KEY_IN_USE,
}

sealed interface RecoveryResult {
    data object Done : RecoveryResult
    data class Refused(val reason: RecoveryRefusal) : RecoveryResult
}

/**
 * The relative's side of getting somebody back: they have just met, in person, a phone whose new
 * key belongs to a person already trusted here. Vouching records that on this phone (the old key
 * revoked, the person now met in person), signs the attestation with this phone's own key, and
 * passes it to everyone else this phone trusts, who accept it if they trust this phone.
 *
 * One relative's word is enough, as decided in the spec; nothing here asks the person or anyone
 * else to confirm.
 */
class RecoveryIssuer(
    private val repository: KutumbRepository,
    private val scheme: SignatureScheme,
    private val publisher: SyncPublisher,
    private val clock: () -> Long,
) {
    suspend fun vouch(personId: String, newKey: String): RecoveryResult {
        val me = repository.observeIdentity().first() ?: return refuse(RecoveryRefusal.NO_IDENTITY)
        if (personId == me.personId) return refuse(RecoveryRefusal.IS_ME)
        val contacts = repository.observeContacts().first()
        val person = contacts.firstOrNull { it.personId == personId } ?: return refuse(RecoveryRefusal.UNKNOWN_PERSON)
        if (newKey == person.pubKeyCurrent) return refuse(RecoveryRefusal.SAME_KEY)
        if (newKey == me.keyPair.publicKey || contacts.any { newKey == it.pubKeyCurrent || newKey in it.pubKeyHistory }) {
            return refuse(RecoveryRefusal.KEY_IN_USE)
        }

        val event = IdentityRotationEvent.attest(scheme, me.keyPair, personId, person.pubKeyCurrent, newKey, clock())
        // Met in person just now, whatever the earlier relationship was: that is what DIRECT means.
        repository.saveContact(person.withRotatedKey(newKey).copy(pairingMethod = PairingMethod.DIRECT, vouchedByPersonId = null))
        repository.saveRotation(event)
        // Everyone but the person: the others have no way to reach the new key until they accept this.
        publisher.publish(RotationSync.envelope(event), contacts.map { it.personId }.filter { it != personId })
        return RecoveryResult.Done
    }

    private fun refuse(reason: RecoveryRefusal) = RecoveryResult.Refused(reason)
}

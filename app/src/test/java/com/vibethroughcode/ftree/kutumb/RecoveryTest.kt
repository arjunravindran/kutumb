package com.vibethroughcode.ftree.kutumb

import com.vibethroughcode.ftree.kutumb.recovery.RotationSync
import com.vibethroughcode.ftree.kutumb.sync.CompositeSyncHandler
import com.vibethroughcode.ftree.kutumb.sync.NoOpSyncHandler
import com.vibethroughcode.ftree.kutumb.sync.RotationSyncHandler
import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncPublisher
import com.vibethroughcode.ftree.kutumb.sync.SyncType
import com.vibethroughcode.ftree.kutumb.sync.UnsignedEvent
import com.vibethroughcode.ftree.kutumb.trust.IdentityRotationEvent
import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Getting somebody back: the relative who meets the new phone, and everyone who hears of it. */
class RecoveryTest {
    private val scheme = Bip340Scheme

    private class RecordingPublisher : SyncPublisher {
        val sent = mutableListOf<Pair<SyncEnvelope, Set<String>>>()
        override suspend fun publish(envelope: SyncEnvelope, personIds: Collection<String>) { sent += envelope to personIds.toSet() }
    }

    private fun key() = scheme.generateKeyPair()

    private fun contact(id: String, k: KeyPair, method: PairingMethod = PairingMethod.DIRECT, voucher: String? = null) =
        TrustedContact(id, k.publicKey, pairedAt = 1, pairingMethod = method, vouchedByPersonId = voucher)

    private fun rumor(from: String) = UnsignedEvent(me.publicKey, 1, SyncEnvelope.RUMOR_KIND, emptyList(), from)

    // ---- the relative who meets the new phone

    private val me = key()
    private val devikaOld = key()
    private val devikaNew = key()
    private val kiran = key()
    private val repo = InMemoryKutumbRepository()
    private val publisher = RecordingPublisher()
    private val issuer = RecoveryIssuer(repo, scheme, publisher, clock = { 777 })

    private fun setUp() = runBlocking {
        repo.setIdentity(LocalIdentity("me", me))
        repo.saveContact(contact("devika", devikaOld, PairingMethod.VOUCHED, voucher = "kiran"))
        repo.saveContact(contact("kiran", kiran))
    }

    @Test fun vouchingRevokesTheOldKeyUpgradesToMetInPersonAndTellsEveryoneElse() = runBlocking {
        setUp()
        assertEquals(RecoveryResult.Done, issuer.vouch("devika", devikaNew.publicKey))

        val devika = repo.observeContacts().first().single { it.personId == "devika" }
        assertEquals(devikaNew.publicKey, devika.pubKeyCurrent)
        assertEquals(listOf(devikaOld.publicKey), devika.pubKeyHistory)
        assertEquals(PairingMethod.DIRECT, devika.pairingMethod)
        assertEquals(null, devika.vouchedByPersonId)

        val event = repo.observeRotations().first().single()
        assertTrue(event.hasValidSignature(scheme))
        assertEquals(me.publicKey, event.vouchedByPubKey)

        val (envelope, to) = publisher.sent.single()
        assertEquals(SyncType.ROTATION, envelope.type)
        assertEquals(event, RotationSync.decode(envelope.payload))
        // Not to Devika: nobody else can reach her new key until they accept this.
        assertEquals(setOf("kiran"), to)
    }

    @Test fun vouchingIsRefusedWhenItCouldNotMeanWhatItSays() = runBlocking {
        assertEquals(RecoveryResult.Refused(RecoveryRefusal.NO_IDENTITY), issuer.vouch("devika", devikaNew.publicKey))
        setUp()
        assertEquals(RecoveryResult.Refused(RecoveryRefusal.IS_ME), issuer.vouch("me", devikaNew.publicKey))
        assertEquals(RecoveryResult.Refused(RecoveryRefusal.UNKNOWN_PERSON), issuer.vouch("stranger", devikaNew.publicKey))
        assertEquals(RecoveryResult.Refused(RecoveryRefusal.SAME_KEY), issuer.vouch("devika", devikaOld.publicKey))
        // Somebody else's key, or this phone's own, is never a new key for Devika.
        assertEquals(RecoveryResult.Refused(RecoveryRefusal.KEY_IN_USE), issuer.vouch("devika", kiran.publicKey))
        assertEquals(RecoveryResult.Refused(RecoveryRefusal.KEY_IN_USE), issuer.vouch("devika", me.publicKey))
        assertTrue(repo.observeRotations().first().isEmpty())
        assertTrue(publisher.sent.isEmpty())
    }

    // ---- everyone else

    private val kiranPhone = InMemoryKutumbRepository()
    private val handler = RotationSyncHandler(kiranPhone, scheme)

    private fun kiranTrusts() = runBlocking {
        kiranPhone.saveContact(contact("me", me))
        kiranPhone.saveContact(contact("devika", devikaOld))
    }

    private fun attested(voucher: KeyPair = me, old: KeyPair = devikaOld, new: KeyPair = devikaNew, ts: Long = 5) =
        IdentityRotationEvent.attest(scheme, voucher, "devika", old.publicKey, new.publicKey, ts)

    private fun deliver(event: IdentityRotationEvent, from: String = "me") = runBlocking {
        handler.onEnvelope(RotationSync.envelope(event), kiranPhone.observeContacts().first().first { it.personId == from }, rumor(from))
    }

    @Test fun aTrustedRelativesRotationMovesTheContactToTheNewKey() = runBlocking {
        kiranTrusts()
        val event = attested()
        deliver(event)
        val devika = kiranPhone.observeContacts().first().single { it.personId == "devika" }
        assertEquals(devikaNew.publicKey, devika.pubKeyCurrent)
        assertTrue(devikaOld.publicKey in devika.pubKeyHistory)
        assertEquals(listOf(event), kiranPhone.observeRotations().first())
    }

    @Test fun aRedeliveryChangesNothingMore() = runBlocking {
        kiranTrusts()
        val event = attested()
        deliver(event)
        val once = kiranPhone.observeContacts().first()
        deliver(event)
        assertEquals(once, kiranPhone.observeContacts().first())
        assertEquals(1, kiranPhone.observeRotations().first().size)
    }

    @Test fun aVoucherThisPhoneDoesNotTrustCountsForNothing() = runBlocking {
        kiranTrusts()
        val stranger = key()
        deliver(attested(voucher = stranger))
        assertEquals(devikaOld.publicKey, kiranPhone.observeContacts().first().single { it.personId == "devika" }.pubKeyCurrent)
        assertTrue(kiranPhone.observeRotations().first().isEmpty())
    }

    @Test fun aRotationFromTheWrongOldKeyOrWithATamperedSignatureIsIgnored() = runBlocking {
        kiranTrusts()
        deliver(attested(old = key()))
        val good = attested()
        deliver(good.copy(signature = good.signature.reversed()))
        deliver(good.copy(newPubKey = key().publicKey))
        assertEquals(devikaOld.publicKey, kiranPhone.observeContacts().first().single { it.personId == "devika" }.pubKeyCurrent)
    }

    @Test fun otherMessageTypesPassThroughTheCompositeUntouched() = runBlocking {
        kiranTrusts()
        val composite = CompositeSyncHandler(NoOpSyncHandler, RotationSyncHandler(kiranPhone, scheme))
        val envelope = SyncEnvelope(SyncType.FACT, linkedMapOf("x" to 1L))
        composite.onEnvelope(envelope, kiranPhone.observeContacts().first().first(), rumor("me"))
        composite.onEnvelope(RotationSync.envelope(attested()), kiranPhone.observeContacts().first().first { it.personId == "me" }, rumor("me"))
        assertEquals(devikaNew.publicKey, kiranPhone.observeContacts().first().single { it.personId == "devika" }.pubKeyCurrent)
    }
}

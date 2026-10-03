package com.vibethroughcode.ftree.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibethroughcode.ftree.data.Person
import com.vibethroughcode.ftree.kutumb.KutumbRepository
import com.vibethroughcode.ftree.kutumb.PairingFlow
import com.vibethroughcode.ftree.kutumb.PairingState
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import com.vibethroughcode.ftree.nearby.wire.QrLink
import com.vibethroughcode.ftree.kutumb.RecoveryIssuer
import com.vibethroughcode.ftree.kutumb.RecoveryRefusal
import com.vibethroughcode.ftree.kutumb.RecoveryResult
import com.vibethroughcode.ftree.ui.common.matchingPeople
import com.vibethroughcode.ftree.ui.nearby.NearbyMessages
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PairingUiState(
    val flow: PairingState = PairingState.Idle,
    val me: Person? = null,
    /** Who the reader may say the other person is: not themselves, and not somebody already trusted. */
    val candidates: List<Person> = emptyList(),
    val query: String = "",
    /** People this phone already trusts, for "they are on a new phone"; matched against [query]. */
    val trusted: List<Person> = emptyList(),
    /** The reader said the person just met is someone already trusted, on a new phone. */
    val recovering: Boolean = false,
    /** Why vouching was refused, if it was. */
    val refusal: RecoveryRefusal? = null,
    /** True once the new contact has been saved and the screen can close. */
    val saved: Boolean = false,
)

class PairingViewModel(
    private val pairing: PairingFlow,
    people: Flow<List<Person>>,
    private val kutumb: KutumbRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val recovery: RecoveryIssuer? = null,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val saved = MutableStateFlow(false)
    private val recovering = MutableStateFlow(false)
    private val refusal = MutableStateFlow<RecoveryRefusal?>(null)

    val state: StateFlow<PairingUiState> = combine(
        pairing.state, kutumb.observeIdentity(), kutumb.observeContacts(), people, query,
    ) { flow, identity, contacts, everyone, q ->
        val trusted = contacts.map { it.personId }.toSet()
        PairingUiState(
            flow = flow,
            me = everyone.firstOrNull { it.id == identity?.personId },
            candidates = matchingPeople(everyone.filter { it.id != identity?.personId && it.id !in trusted }, q),
            query = q,
            trusted = matchingPeople(everyone.filter { it.id in trusted }, q),
        )
    }.combine(saved) { ui, done -> ui.copy(saved = done) }
        .combine(recovering) { ui, on -> ui.copy(recovering = on) }
        .combine(refusal) { ui, why -> ui.copy(refusal = why) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PairingUiState())

    init { pairing.start() }

    fun onQueryChange(value: String) { query.value = value }

    /** Switches between "somebody new" and "someone I already trust, on a new phone". */
    fun setRecovering(on: Boolean) {
        recovering.value = on
        refusal.value = null
        query.value = ""
    }

    fun scanned(link: QrLink) = pairing.joinByLink(link)

    /** Pairs with a typed `a.b.c.d:port`. False, doing nothing, when it is not a private address and port. */
    fun typedAddress(text: String): Boolean {
        val (address, port) = NearbyMessages.parseAddress(text) ?: return false
        pairing.joinByAddress(address, port)
        return true
    }

    fun confirmCode(matched: Boolean) = pairing.confirmCode(matched)

    fun retry() {
        pairing.cancel()
        pairing.start()
    }

    /**
     * "That was them." Saved as met in person, the only way a contact becomes one - nothing the
     * network says can do it.
     */
    fun choosePerson(personId: String) {
        val verified = pairing.state.value as? PairingState.Verified ?: return
        viewModelScope.launch {
            // One key, one person: the trust store refuses a key two contacts share, so refuse it
            // here rather than save a row that would make the whole store unreadable.
            val taken = kutumb.observeContacts().first()
                .any { verified.peerPublicKey == it.pubKeyCurrent || verified.peerPublicKey in it.pubKeyHistory }
            if (taken) return@launch
            kutumb.saveContact(
                TrustedContact(
                    personId = personId,
                    pubKeyCurrent = verified.peerPublicKey,
                    pairedAt = clock(),
                    pairingMethod = PairingMethod.DIRECT,
                )
            )
            pairing.cancel()
            saved.value = true
        }
    }

    /**
     * "That is [personId], on a new phone." This phone vouches for the key it just met: the old key
     * is revoked here, and everyone else this phone trusts is told. Needs the sync switch on to
     * reach them; the change on this phone stands either way.
     */
    fun vouchNewPhone(personId: String) {
        val verified = pairing.state.value as? PairingState.Verified ?: return
        val issuer = recovery ?: return
        viewModelScope.launch {
            when (val result = issuer.vouch(personId, verified.peerPublicKey)) {
                RecoveryResult.Done -> {
                    pairing.cancel()
                    saved.value = true
                }
                is RecoveryResult.Refused -> refusal.value = result.reason
            }
        }
    }

    override fun onCleared() {
        pairing.cancel()
    }
}

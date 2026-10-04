package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme

/**
 * NIP-42: answering a relay's `["AUTH", challenge]`.
 *
 * Public relays that hold gift wraps (kind 1059) commonly refuse to hand them to a client that has
 * not shown it holds the key they are addressed to, and close the subscription with
 * `auth-required` instead. The answer is an ephemeral-looking kind-22242 event, signed by the same
 * key the inbox filter names, carrying the relay's address and its challenge.
 */
object Nip42 {
    const val KIND_AUTH = 22242

    /** The signed event that answers [challenge] from the relay at [relayUrl]. */
    fun answer(keyPair: KeyPair, relayUrl: String, challenge: String, nowSeconds: Long, scheme: SignatureScheme): NostrEvent =
        UnsignedEvent(
            pubkey = keyPair.publicKey,
            createdAt = nowSeconds,
            kind = KIND_AUTH,
            tags = listOf(listOf("relay", relayUrl), listOf("challenge", challenge)),
            content = "",
        ).sign(keyPair.privateKey, scheme)
}

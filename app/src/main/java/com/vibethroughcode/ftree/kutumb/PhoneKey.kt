package com.vibethroughcode.ftree.kutumb

import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.SignatureScheme

/** Where the phone's one long-term key is kept between runs. */
interface PhoneKeyStore {
    /** The stored key, or null if there is none or this phone cannot open it. */
    fun read(): KeyPair?
    fun write(key: KeyPair)
}

/**
 * The one long-term key this phone speaks with, shared by every tree on it.
 *
 * A person is one voice however many trees they keep: a relative who paired with you in "My family"
 * must still recognise you when your "Work" circle syncs, so the key belongs to the phone and each
 * tree only records which of its people is you ([LocalIdentity.personId]).
 *
 * Minted the first time anything asks, and never replaced afterwards. [adopt] exists for the trees
 * that already hold a key from before this existed: the first one seen becomes the phone's.
 */
class PhoneKey(
    private val store: PhoneKeyStore,
    private val scheme: SignatureScheme,
) {
    /** The phone's key, minted and stored now if there is none yet. */
    fun keyPair(): KeyPair = synchronized(lock) {
        store.read() ?: scheme.generateKeyPair().also(store::write)
    }

    /**
     * Makes [existing] the phone's key if the phone has none, and returns the phone's key either way.
     * A tree whose key differs from the returned one keeps its own: changing it would orphan every
     * contact that trusts it.
     */
    fun adopt(existing: KeyPair): KeyPair = synchronized(lock) {
        store.read() ?: existing.also(store::write)
    }

    private companion object {
        // Containers are rebuilt on every tree switch, so the lock cannot live in an instance.
        val lock = Any()
    }
}

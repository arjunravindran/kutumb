package com.vibethroughcode.ftree.kutumb

import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PhoneKeyTest {
    private class MemoryStore(var key: KeyPair? = null) : PhoneKeyStore {
        var writes = 0
        override fun read() = key
        override fun write(key: KeyPair) { this.key = key; writes++ }
    }

    @Test fun `the key is minted once and every tree gets the same one`() {
        val store = MemoryStore()
        val first = PhoneKey(store, Bip340Scheme).keyPair()
        // A second tree opens: a new container, a new PhoneKey over the same file.
        val second = PhoneKey(store, Bip340Scheme).keyPair()
        assertEquals(first.publicKey, second.publicKey)
        assertEquals(first.privateKey, second.privateKey)
        assertEquals(1, store.writes)
    }

    @Test fun `an existing tree key becomes the phone's when the phone has none`() {
        val store = MemoryStore()
        val old = Bip340Scheme.generateKeyPair()
        assertSame(old, PhoneKey(store, Bip340Scheme).adopt(old))
        assertEquals(old.publicKey, PhoneKey(store, Bip340Scheme).keyPair().publicKey)
    }

    @Test fun `adopting never replaces the phone's key`() {
        val phone = Bip340Scheme.generateKeyPair()
        val store = MemoryStore(phone)
        val other = Bip340Scheme.generateKeyPair()
        assertEquals(phone.publicKey, PhoneKey(store, Bip340Scheme).adopt(other).publicKey)
        assertEquals(phone.publicKey, store.key!!.publicKey)
        assertEquals(0, store.writes)
    }

    @Test fun `a phone whose stored key cannot be opened mints a new one`() {
        // read() returns null for a sealed key from another phone; that is "no key", not an error.
        val store = MemoryStore(null)
        assertNull(store.read())
        assertEquals(64, PhoneKey(store, Bip340Scheme).keyPair().publicKey.length)
    }
}

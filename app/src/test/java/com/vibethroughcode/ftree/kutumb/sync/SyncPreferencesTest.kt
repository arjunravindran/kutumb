package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.sync.SyncPreferences.Companion.normalizeRelay
import com.vibethroughcode.ftree.nearby.MemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPreferencesTest {
    @Test fun `only wss relays with a real host are accepted`() {
        assertEquals("wss://relay.example.com", normalizeRelay(" wss://Relay.Example.com/ "))
        assertEquals("wss://relay.example.com:8443/nostr", normalizeRelay("wss://relay.example.com:8443/nostr/"))
        assertNull(normalizeRelay("ws://relay.example.com"))
        assertNull(normalizeRelay("https://relay.example.com"))
        assertNull(normalizeRelay("relay.example.com"))
        assertNull(normalizeRelay("wss://user:pw@relay.example.com"))
        assertNull(normalizeRelay("wss://relay.example.com/#x"))
        assertNull(normalizeRelay("wss://intranet"))
        assertNull(normalizeRelay("wss://a b.example.com"))
        assertNull(normalizeRelay(""))
    }

    @Test fun `a phone that never chose relays uses the default, and a saved choice always wins`() {
        val store = MemorySharedPreferences()
        assertEquals(SyncPreferences.DEFAULT_RELAYS, SyncPreferences(store).relays.value)
        assertEquals(false, SyncPreferences(store).enabled.value)

        SyncPreferences(store).setRelaysFromText("wss://relay.example.com")
        assertEquals(listOf("wss://relay.example.com"), SyncPreferences(store).relays.value)

        SyncPreferences(store).setRelaysFromText("")
        assertEquals(emptyList<String>(), SyncPreferences(store).relays.value)
    }

    @Test fun `the default relay is in canonical form and is one of the suggested`() {
        assertEquals(SyncPreferences.DEFAULT_RELAYS, SyncPreferences.DEFAULT_RELAYS.map { normalizeRelay(it) })
        assertEquals(true, SyncPreferences.SUGGESTED_RELAYS.containsAll(SyncPreferences.DEFAULT_RELAYS))
    }

    @Test fun `every suggested relay is already in canonical form, unique, and not the broken one`() {
        val list = SyncPreferences.SUGGESTED_RELAYS
        assertEquals(list, list.map { normalizeRelay(it) })
        assertEquals(list.size, list.distinct().size)
        assertEquals(false, list.any { "damus" in it })
    }
}

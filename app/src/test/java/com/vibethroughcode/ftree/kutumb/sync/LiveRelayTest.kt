package com.vibethroughcode.ftree.kutumb.sync

import com.vibethroughcode.ftree.kutumb.InMemoryKutumbRepository
import com.vibethroughcode.ftree.kutumb.sync.relay.WebSocketRelay
import com.vibethroughcode.ftree.kutumb.trust.KeyPair
import com.vibethroughcode.ftree.kutumb.trust.PairingMethod
import com.vibethroughcode.ftree.kutumb.trust.TrustedContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The real thing, once: two engines (throwaway keys, in-memory storage) exchange one encrypted message
 * through relays on the internet, over the hand-written websocket client and real TLS.
 *
 * Off unless asked for, so a build never touches the network:
 *
 *     FTREE_LIVE_RELAYS=wss://relay.example.org,wss://other.example.net ./gradlew :app:testDebugUnitTest --tests '*LiveRelayTest*'
 *
 * What it sends is one gift-wrapped, encrypted note addressed to a key made up for the run, so no one
 * can read it and nothing of anyone's is in it. The relay keeps it as it keeps any event.
 */
class LiveRelayTest {
    private val relays: List<String> =
        System.getenv("FTREE_LIVE_RELAYS").orEmpty().split(',').map { it.trim() }.filter { it.startsWith("wss://") }

    private class Peer(val keys: KeyPair, relays: List<String>, scope: CoroutineScope, onMessage: (SyncEnvelope) -> Unit) {
        val repo = InMemoryKutumbRepository()
        val engine = SyncEngine(
            repository = repo,
            keyPair = keys,
            connector = WebSocketRelay.connector(),
            scope = scope,
            clock = System::currentTimeMillis,
            handler = { envelope, _, _ -> onMessage(envelope) },
            ackTimeoutMs = 15_000,
        )
        val relayList = relays
    }

    @Test
    fun aMessageGoesFromOnePhoneToAnotherThroughRealRelays() {
        assumeTrue("set FTREE_LIVE_RELAYS to one or more wss:// relays to run this", relays.isNotEmpty())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val heard = CopyOnWriteArrayList<SyncEnvelope>()
        val alice = Peer(NostrBip340Scheme.generateKeyPair(), relays, scope) {}
        val bob = Peer(NostrBip340Scheme.generateKeyPair(), relays, scope) { heard += it }
        try {
            runBlocking {
                alice.repo.saveContact(TrustedContact("bob", bob.keys.publicKey, pairedAt = 0, pairingMethod = PairingMethod.DIRECT))
                bob.repo.saveContact(TrustedContact("alice", alice.keys.publicKey, pairedAt = 0, pairingMethod = PairingMethod.DIRECT))

                bob.engine.startInbound(relays)
                val note = SyncEnvelope(
                    SyncType.MESSAGE,
                    linkedMapOf("id" to UUID.randomUUID().toString(), "body" to "f-tree relay test, please ignore", "at" to System.currentTimeMillis()),
                )
                alice.engine.enqueue(note, listOf(bob.keys.publicKey), relays)

                val report = alice.engine.flush()
                println("published: done=${report.done} pending=${report.pending} on $relays")
                assertEquals("a relay should have accepted the event", 1, report.done)

                var waited = 0
                while (heard.isEmpty() && waited < 30_000) { delay(250); waited += 250 }
                println("bob heard ${heard.size} message(s) after ${waited}ms")
                assertTrue("bob should have received the message through the relay", heard.isNotEmpty())
                assertEquals(note.payload["id"], heard.first().payload["id"])
                assertEquals("f-tree relay test, please ignore", heard.first().payload["body"])
            }
        } finally {
            runBlocking { alice.engine.stop(); bob.engine.stop() }
            scope.cancel()
        }
    }
}

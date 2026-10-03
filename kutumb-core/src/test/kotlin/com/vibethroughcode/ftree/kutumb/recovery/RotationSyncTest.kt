package com.vibethroughcode.ftree.kutumb.recovery

import com.vibethroughcode.ftree.kutumb.sync.SyncEnvelope
import com.vibethroughcode.ftree.kutumb.sync.SyncType
import com.vibethroughcode.ftree.kutumb.trust.IdentityRotationEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RotationSyncTest {
    private val key = { c: Char -> c.toString().repeat(64) }
    private val event = IdentityRotationEvent("p", key('a'), key('b'), key('c'), "sig-" + "0".repeat(60), 12_345L)

    @Test fun aRotationSurvivesTheWire() {
        val sent = RotationSync.envelope(event)
        assertEquals(SyncType.ROTATION, sent.type)
        // Through JSON text and back, as it travels.
        val received = SyncEnvelope.parse(sent.toJson())!!
        assertEquals(event, RotationSync.decode(received.payload))
    }

    @Test fun anythingMalformedDecodesToNull() {
        val good = RotationSync.envelope(event).payload
        assertNotNull(RotationSync.decode(good))
        assertNull(RotationSync.decode(good - "sig"))
        assertNull(RotationSync.decode(good - "at"))
        assertNull(RotationSync.decode(good + ("at" to "12345")))        // a string, not a number
        assertNull(RotationSync.decode(good + ("at" to -1L)))            // the event refuses a negative time
        assertNull(RotationSync.decode(good + ("old" to "not hex")))
        assertNull(RotationSync.decode(good + ("new" to key('a'))))      // must change the key
        assertNull(RotationSync.decode(good + ("person" to "")))
        assertNull(RotationSync.decode(good + ("person" to "x".repeat(RotationSync.MAX_ID + 1))))
        assertNull(RotationSync.decode(good + ("by" to 7L)))
    }
}

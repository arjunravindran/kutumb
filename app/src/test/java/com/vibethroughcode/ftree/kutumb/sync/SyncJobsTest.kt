package com.vibethroughcode.ftree.kutumb.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncJobsTest {
    private val relay = listOf("wss://relay.example.org")

    @Test fun theJobExistsOnlyWhileSyncIsOnAndARelayIsNamed() {
        assertTrue(SyncJobs.shouldRun(enabled = true, relays = relay))
        // Nothing to connect to, so nothing to wake the phone for.
        assertFalse(SyncJobs.shouldRun(enabled = true, relays = emptyList()))
        // The switch is the whole guarantee: off means no schedule, whatever is saved.
        assertFalse(SyncJobs.shouldRun(enabled = false, relays = relay))
        assertFalse(SyncJobs.shouldRun(enabled = false, relays = emptyList()))
    }

    @Test fun theRunsAreNoMoreOftenThanThePlatformAllows() {
        // JobScheduler raises anything shorter to 15 minutes; asking for exactly that says what is meant.
        assertEquals(15 * 60 * 1000L, SyncJobs.PERIOD_MS)
    }
}

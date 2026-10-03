package com.vibethroughcode.ftree.kutumb

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibethroughcode.ftree.FTreeApplication
import com.vibethroughcode.ftree.kutumb.sync.SyncJobs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The periodic background job: that it exists exactly while sync is on and a relay is named, and
 * that a run does nothing with the switch off. The relay is on a name that can never resolve and the
 * phone has no identity here, so nothing in this test reaches the network.
 */
@RunWith(AndroidJUnit4::class)
class SyncJobTest {
    private val app: FTreeApplication = ApplicationProvider.getApplicationContext()
    private val wasOn = app.syncPreferences.enabled.value
    private val relayText = app.syncPreferences.relays.value.joinToString("\n")

    @Before fun noIdentityAndNoJob() {
        runBlocking { app.container.kutumbRepository.clearAll() }
        app.syncPreferences.setEnabled(false)
        app.syncPreferences.setRelaysFromText("")
        waitFor("the job to be cancelled") { !SyncJobs.isScheduled(app) }
    }

    @After fun putTheSettingsBack() {
        app.syncPreferences.setRelaysFromText(relayText)
        app.syncPreferences.setEnabled(wasOn)
        waitFor("the job to follow the settings") { SyncJobs.isScheduled(app) == SyncJobs.shouldRun(wasOn, app.syncPreferences.relays.value) }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for $what" }
            Thread.sleep(50)
        }
    }

    @Test fun schedulingIsIdempotentAndCancellingRemovesIt() {
        SyncJobs.schedule(app)
        assertTrue(SyncJobs.isScheduled(app))
        SyncJobs.schedule(app) // again: still one job, still there
        assertTrue(SyncJobs.isScheduled(app))
        SyncJobs.cancel(app)
        assertFalse(SyncJobs.isScheduled(app))
    }

    @Test fun theJobFollowsTheSwitchAndTheRelayList() {
        // A relay alone is not enough, and neither is the switch alone.
        app.syncPreferences.setRelaysFromText("wss://relay.invalid")
        Thread.sleep(300)
        assertFalse(SyncJobs.isScheduled(app))

        app.syncPreferences.setEnabled(true)
        waitFor("the job to be scheduled") { SyncJobs.isScheduled(app) }

        app.syncPreferences.setRelaysFromText("")
        waitFor("the job to go when the last relay does") { !SyncJobs.isScheduled(app) }

        app.syncPreferences.setRelaysFromText("wss://relay.invalid")
        waitFor("the job to return with a relay") { SyncJobs.isScheduled(app) }
        app.syncPreferences.setEnabled(false)
        waitFor("the job to go with the switch") { !SyncJobs.isScheduled(app) }
    }

    @Test fun aRunWithTheSwitchOffReturnsAtOnceAndOpensNothing() = runBlocking {
        val started = System.currentTimeMillis()
        app.backgroundSyncPass(window = 5_000)
        assertTrue("a pass with sync off should not wait", System.currentTimeMillis() - started < 1_000)
    }

    @Test fun aRunWithSyncOnWaitsItsWindowAndThenReturns() = runBlocking {
        app.syncPreferences.setRelaysFromText("wss://relay.invalid")
        app.syncPreferences.setEnabled(true)
        val started = System.currentTimeMillis()
        app.backgroundSyncPass(window = 400)
        // With no identity the service is set up and idle: the pass still takes its window, and ends.
        assertTrue(System.currentTimeMillis() - started in 350..3_000)
    }
}

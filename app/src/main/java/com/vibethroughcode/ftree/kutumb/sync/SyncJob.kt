package com.vibethroughcode.ftree.kutumb.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.vibethroughcode.ftree.FTreeApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps family sync going while the app is closed, with what the platform already provides: a
 * periodic job, which Android runs when it suits the battery (no sooner than every 15 minutes) and
 * only with a network. No new library and no new permission: the boot permission the birthday
 * reminders declare is what lets the schedule outlive a restart.
 *
 * It exists only while sync is on and a relay is named, so with the switch off nothing is
 * scheduled and nothing wakes. Each run just brings the sync up for a short while, long enough to
 * collect what waited at the relays and send what waited here (see
 * [FTreeApplication.backgroundSyncPass]).
 */
object SyncJobs {
    const val JOB_ID = 0x6B75
    const val PERIOD_MS = 15 * 60 * 1000L

    /** Whether there is anything for a background run to do. The one rule for scheduling and cancelling. */
    fun shouldRun(enabled: Boolean, relays: List<String>): Boolean = enabled && relays.isNotEmpty()

    /** Idempotent: a job already pending is left alone, so its place in the period is not reset. */
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
            .setPeriodic(PERIOD_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    fun isScheduled(context: Context): Boolean =
        context.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID) != null
}

/** The periodic run. Bringing the application up is most of the work; the rest is waiting a moment. */
class SyncJobService : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as FTreeApplication
        work = app.appScope.launch {
            try {
                app.backgroundSyncPass()
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    /** The system wants the run over early. The period brings another, so there is nothing to reschedule. */
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return false
    }
}

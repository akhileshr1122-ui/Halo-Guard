package com.haloapps.guard

import android.app.job.JobParameters
import android.app.job.JobService

class WatchJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                Watcher.check(applicationContext)
            } catch (e: Exception) {
                // never crash in the background
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

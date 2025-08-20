package com.example.local_ai

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.local_ai.data.worker.AppUsageCollectionWorker
import java.util.concurrent.TimeUnit

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        setupRecurringWork()
    }

    private fun setupRecurringWork() {
        val constraints = androidx.work.Constraints.Builder()
            // Add constraints if needed, e.g., network, battery
            .build()

        val repeatingRequest = PeriodicWorkRequestBuilder<AppUsageCollectionWorker>(
            AppUsageCollectionWorker.REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            AppUsageCollectionWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // Or REPLACE if you want to update the worker
            repeatingRequest
        )
    }
}

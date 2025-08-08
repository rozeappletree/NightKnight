package com.example.local_ai.services

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.local_ai.db.AppDatabase
import com.example.local_ai.db.AppUsageEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppUsageMonitorService : Service() {

    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var appDatabase: AppDatabase
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    // Initialize with a value that ensures we get recent events on first run,
    // but not so far back that it's overwhelming. System boot time is a safe start.
    private var lastCheckedTimestamp: Long = 0L


    companion object {
        private const val TAG = "AppUsageMonitorService"
        private const val POLLING_INTERVAL_MS = 2000L // Check every 2 seconds
    }

    override fun onCreate() {
        super.onCreate()
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        appDatabase = AppDatabase.getDatabase(this)
        // Query events from up to 1 hour ago on first start, or a shorter period if preferred.
        // This helps in case the service was stopped for a while.
        lastCheckedTimestamp = System.currentTimeMillis() - (60 * 60 * 1000) // 1 hour ago
        Log.d(TAG, "AppUsageMonitorService created. Initial lastCheckedTimestamp: $lastCheckedTimestamp")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AppUsageMonitorService started")
        handler.post(usageMonitorRunnable)
        return START_STICKY
    }

    private val usageMonitorRunnable = object : Runnable {
        override fun run() {
            queryAndStoreUsageEvents()
            handler.postDelayed(this, POLLING_INTERVAL_MS)
        }
    }

    private fun queryAndStoreUsageEvents() {
        val currentTime = System.currentTimeMillis()
        if (currentTime <= lastCheckedTimestamp) { // Ensure we always query a positive time range
             Log.d(TAG, "Current time is not past lastCheckedTimestamp. Skipping query.")
             return
        }

        val usageEvents = usageStatsManager.queryEvents(lastCheckedTimestamp, currentTime)
        var event = UsageEvents.Event()
        var latestTimestampInBatch = lastCheckedTimestamp

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)

            if (event.timeStamp > latestTimestampInBatch) {
                latestTimestampInBatch = event.timeStamp
            }

            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND || event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                if (event.packageName != null) {
                    val appUsageEvent = AppUsageEvent(
                        packageName = event.packageName,
                        timestamp = event.timeStamp,
                        eventType = event.eventType
                    )
                    serviceScope.launch {
                        try {
                            appDatabase.appUsageDao().insert(appUsageEvent)
                            Log.d(TAG, "Logged event: ${appUsageEvent.packageName} - Type: ${appUsageEvent.eventType} at ${appUsageEvent.timestamp}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error inserting event into database", e)
                        }
                    }
                }
            }
        }
        // Update lastCheckedTimestamp to the timestamp of the latest event processed in this batch,
        // plus one millisecond to avoid re-processing the same event.
        // If no new events, it remains currentTime, so next query starts from there.
        lastCheckedTimestamp = if (latestTimestampInBatch > lastCheckedTimestamp) latestTimestampInBatch + 1 else currentTime
        Log.d(TAG, "New lastCheckedTimestamp: $lastCheckedTimestamp")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(usageMonitorRunnable)
        Log.d(TAG, "AppUsageMonitorService destroyed")
    }
}

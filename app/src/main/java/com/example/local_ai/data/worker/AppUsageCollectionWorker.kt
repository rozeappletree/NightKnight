package com.example.local_ai.data.worker // Or your preferred package

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.local_ai.data.db.AppDatabase
import com.example.local_ai.data.db.AppUsageEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

class AppUsageCollectionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val WORK_NAME = "AppUsageCollectionWorker"
        private const val TAG = "AppUsageCollector"
        // Define a shorter interval for more granular tracking, e.g., every 15 minutes.
        // For production, you might want a longer interval to save battery.
        val REPEAT_INTERVAL_MINUTES: Long = 15
    }

    private val appUsageDao = AppDatabase.getDatabase(applicationContext).appUsageDao()
    private val usageStatsManager =
        applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val packageManager = applicationContext.packageManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting app usage collection work.")
        if (!hasUsageStatsPermission()) {
            Log.w(TAG, "Usage stats permission not granted. Skipping work.")
            // Consider rescheduling or notifying the user.
            // For now, we just return success as the work itself (checking permission) is done.
            return@withContext Result.success()
        }

        try {
            // Fetch events for the last 'RE'''PEAT_INTERVAL_MINUTES''' or since the last known event.
            // For simplicity, we'll fetch for the last interval.
            // More robust implementation would store the timestamp of the last collected event.
            val endTime = System.currentTimeMillis()
            val startTime = endTime - TimeUnit.MINUTES.toMillis(REPEAT_INTERVAL_MINUTES * 2) // Overlap a bit

            val usageEvents = queryUsageEvents(startTime, endTime)
            Log.d(TAG, "Fetched ${usageEvents.size} usage events from $startTime to $endTime")

            if (usageEvents.isNotEmpty()) {
                val appUsageEvents = processUsageEvents(usageEvents, startTime)
                appUsageEvents.forEach { appUsageDao.insertEvent(it) }
                Log.d(TAG, "Inserted ${appUsageEvents.size} app usage events into DB.")
            } else {
                Log.d(TAG, "No new usage events to process.")
            }

            Log.d(TAG, "App usage collection work finished successfully.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error during app usage collection: ${e.message}", e)
            Result.failure()
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = applicationContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                applicationContext.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                applicationContext.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName // Fallback to package name
        }
    }

    private fun queryUsageEvents(startTime: Long, endTime: Long): List<UsageEvents.Event> {
        val events = mutableListOf<UsageEvents.Event>()
        val usageEventsResult = usageStatsManager.queryEvents(startTime, endTime)
        while (usageEventsResult.hasNextEvent()) {
            val event = UsageEvents.Event()
            usageEventsResult.getNextEvent(event)
            // Filter for relevant events if needed, e.g., ACTIVITY_RESUMED (foreground) and ACTIVITY_PAUSED (background)
            // or CONFIGURATION_CHANGE for screen rotations, etc.
            // For now, let's focus on foreground and background transitions.
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                event.eventType == UsageEvents.Event.SCREEN_INTERACTIVE || // Screen On
                event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE // Screen Off
            ) {
                events.add(event)
            }
        }
        return events
    }

    private fun processUsageEvents(
        rawEvents: List<UsageEvents.Event>,
        queryStartTime: Long
    ): List<AppUsageEvent> {
        val processedEvents = mutableListOf<AppUsageEvent>()
        val appSessions = mutableMapOf<String, Long>() // PackageName to StartTime

        // Add a synthetic event for the start of the query period if needed
        // to correctly calculate duration for apps already running.
        // This is a simplified approach. A more robust solution might need more context.

        for (event in rawEvents.sortedBy { it.timeStamp }) {
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    appSessions[event.packageName] = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val startTime = appSessions.remove(event.packageName)
                    if (startTime != null && event.timeStamp > startTime) {
                        processedEvents.add(
                            AppUsageEvent(
                                timestamp = startTime,
                                appName = getAppName(event.packageName),
                                usageTimeMillis = event.timeStamp - startTime,
                                eventType = "APP_USAGE"
                            )
                        )
                    }
                }
                 UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // When screen goes off, consider all currently "resumed" apps as paused for calculation
                    appSessions.forEach { (pkgName, startTime) ->
                         if (event.timeStamp > startTime) {
                            processedEvents.add(
                                AppUsageEvent(
                                    timestamp = startTime,
                                    appName = getAppName(pkgName),
                                    usageTimeMillis = event.timeStamp - startTime,
                                    eventType = "APP_USAGE_SCREEN_OFF_CONTEXT" // Special type
                                )
                            )
                        }
                    }
                    appSessions.clear() // Clear active sessions as screen is off
                    processedEvents.add(
                        AppUsageEvent(
                            timestamp = event.timeStamp,
                            appName = "Screen Off/Locked",
                            usageTimeMillis = 0, // Duration is 0, this is an event marker
                            eventType = "SYSTEM_EVENT"
                        )
                    )
                }
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                     processedEvents.add(
                        AppUsageEvent(
                            timestamp = event.timeStamp,
                            appName = "Screen On",
                            usageTimeMillis = 0, // Duration is 0, event marker
                            eventType = "SYSTEM_EVENT"
                        )
                    )
                    // We don't know what app will be resumed yet, so we don't start any sessions here.
                    // The next ACTIVITY_RESUMED event will handle that.
                }
            }
        }

        // Handle apps that were running when the query period ended (or screen turned off)
        // This part is tricky if the worker stops and restarts. A robust solution needs to
        // persist the 'ap'''pSessions''' or query a slightly overlapping window.
        // For simplicity, if screen is on and worker is just ending its period:
        val currentTime = System.currentTimeMillis()
        appSessions.forEach { (pkgName, startTime) ->
            if (currentTime > startTime) {
                 processedEvents.add(
                    AppUsageEvent(
                        timestamp = startTime,
                        appName = getAppName(pkgName),
                        usageTimeMillis = currentTime - startTime,
                        eventType = "APP_USAGE_PARTIAL_END" // Indicate it's potentially incomplete
                    )
                )
            }
        }
        return processedEvents.distinctBy {Triple(it.appName, it.timestamp, it.eventType)} // Basic deduplication
    }
}

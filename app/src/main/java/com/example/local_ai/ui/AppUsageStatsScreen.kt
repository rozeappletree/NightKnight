package com.example.local_ai.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.local_ai.db.AppDatabase
import com.example.local_ai.db.AppUsageEvent
import com.example.local_ai.db.AppUsageStat
import com.example.local_ai.services.AppUsageMonitorService
import kotlinx.coroutines.launch
import java.util.Calendar
import android.app.usage.UsageEvents // For event type constants
import kotlin.math.max
import kotlin.math.min


// --- Helper functions for permission and service ---
fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

fun requestUsageStatsPermission(context: Context) {
    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}

fun startAppUsageMonitorService(context: Context) {
    val serviceIntent = Intent(context, AppUsageMonitorService::class.java)
    context.startService(serviceIntent)
}

// --- Helper functions for Heatmap Data Processing ---
fun processEventsForHourlyHeatmap(
    events: List<AppUsageEvent>,
    dayStartMillis: Long
): List<Long> {
    val hourlyBuckets = LongArray(24) { 0L }
    val dayEndMillis = dayStartMillis + 24 * 60 * 60 * 1000 - 1

    val sortedEvents = events.sortedWith(compareBy({ it.packageName }, { it.timestamp }))
    val openSessions = mutableMapOf<String, Long>()

    for (event in sortedEvents) {
        if (event.timestamp > dayEndMillis && event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
            openSessions.remove(event.packageName)?.let { fgTimestamp ->
                 addDurationToBuckets(hourlyBuckets, fgTimestamp, dayEndMillis, dayStartMillis)
            }
            continue
        }
         if (event.timestamp < dayStartMillis && event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
            continue
        }

        when (event.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                openSessions[event.packageName]?.let { previousFgTimestamp ->
                    addDurationToBuckets(hourlyBuckets, previousFgTimestamp, event.timestamp.coerceAtMost(dayEndMillis), dayStartMillis)
                }
                openSessions[event.packageName] = event.timestamp.coerceAtLeast(dayStartMillis)
            }
            UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                openSessions.remove(event.packageName)?.let { fgTimestamp ->
                    addDurationToBuckets(hourlyBuckets, fgTimestamp, event.timestamp.coerceAtMost(dayEndMillis), dayStartMillis)
                }
            }
        }
    }

    openSessions.forEach { _, fgTimestamp ->
        addDurationToBuckets(hourlyBuckets, fgTimestamp, dayEndMillis, dayStartMillis)
    }
    return hourlyBuckets.toList()
}

private fun addDurationToBuckets(
    hourlyBuckets: LongArray,
    sessionStartMillis: Long,
    sessionEndMillis: Long,
    dayStartMillis: Long
) {
    if (sessionStartMillis >= sessionEndMillis) return

    val effectiveSessionStart = sessionStartMillis.coerceAtLeast(dayStartMillis)
    val dayEndBoundary = dayStartMillis + 24 * 60 * 60 * 1000
    val effectiveSessionEnd = sessionEndMillis.coerceAtMost(dayEndBoundary)

    if (effectiveSessionStart >= effectiveSessionEnd) return

    val startHour = ((effectiveSessionStart - dayStartMillis) / (60 * 60 * 1000)).toInt().coerceIn(0, 23)
    val endHour = ((effectiveSessionEnd - 1 - dayStartMillis) / (60 * 60 * 1000)).toInt().coerceIn(0, 23)

    for (hour in startHour..endHour) {
        val hourSlotStart = dayStartMillis + hour * 60 * 60 * 1000
        val hourSlotEnd = hourSlotStart + 60 * 60 * 1000

        val startInHour = effectiveSessionStart.coerceAtLeast(hourSlotStart)
        val endInHour = effectiveSessionEnd.coerceAtMost(hourSlotEnd)
        
        val durationInHour = endInHour - startInHour
        if (durationInHour > 0) {
            hourlyBuckets[hour] += durationInHour
        }
    }
}


// --- Main Composable Screen ---
@Composable
fun AppUsageStatsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val appUsageDao = remember { db.appUsageDao() }
    val coroutineScope = rememberCoroutineScope()

    var usagePermissionGranted by remember { mutableStateOf(hasUsageStatsPermission(context)) }
    var appUsageStats by remember { mutableStateOf<List<AppUsageStat>>(emptyList()) }
    var hourlyUsageData by remember { mutableStateOf<List<Long>>(List(24) { 0L }) }

    fun fetchAppCounts() {
        coroutineScope.launch {
            appUsageStats = appUsageDao.getAppUsageCounts()
        }
    }

    fun fetchHeatmapData() {
        coroutineScope.launch {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val todayStartMillis = calendar.timeInMillis
            val todayEndMillis = todayStartMillis + 24 * 60 * 60 * 1000 -1

            val events = appUsageDao.getEventsForHeatmap(todayStartMillis, todayEndMillis)
            hourlyUsageData = processEventsForHourlyHeatmap(events, todayStartMillis)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!usagePermissionGranted) {
            Text("Usage stats permission is required to track app usage.")
            Button(onClick = { requestUsageStatsPermission(context) }) {
                Text("Grant Permission")
            }
            Button(onClick = { // Combined button
                usagePermissionGranted = hasUsageStatsPermission(context)
                if (usagePermissionGranted) {
                    startAppUsageMonitorService(context)
                    fetchAppCounts() // Fetch initial data once permission granted
                    fetchHeatmapData()
                }
            }) {
                Text("Check Permission & Start Service")
            }
        } else {
            // Service Control and Data Refresh
            Button(onClick = {
                startAppUsageMonitorService(context)
                // Optionally add a small delay or user feedback
            }) {
                Text("Ensure Monitor Service is Running")
            }

            Spacer(modifier = Modifier.height(10.dp))

            // App Usage Counts Section
            Text("App Usage Counts:", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Button(onClick = ::fetchAppCounts) {
                Text("Refresh Usage Counts")
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (appUsageStats.isEmpty()) {
                    item { Text("No usage count data yet.") }
                } else {
                    items(appUsageStats) { stat ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stat.packageName, modifier = Modifier.weight(1f))
                            Text("${stat.usage_count} opens")
                        }
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 10.dp))

            // Heatmap Section
            Text("Today's Usage Heatmap (by hour):", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Button(onClick = ::fetchHeatmapData) {
                Text("Refresh Heatmap")
            }
            Spacer(modifier = Modifier.height(8.dp))
            DailyHeatmapRow(hourlyData = hourlyUsageData)

            // Initial data load when permission is already granted
            LaunchedEffect(usagePermissionGranted) {
                if (usagePermissionGranted) {
                    fetchAppCounts()
                    fetchHeatmapData()
                }
            }
        }
    }
}

@Composable
fun DailyHeatmapRow(hourlyData: List<Long>, modifier: Modifier = Modifier) {
    if (hourlyData.isEmpty() || hourlyData.size != 24) {
        Text("Heatmap data not available or invalid.")
        return
    }

    val maxUsage = hourlyData.maxOrNull() ?: 1L // Avoid division by zero, 1L for minimal usage
    val cellHeight = 40.dp
    val cellSpacing = 2.dp

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(cellSpacing)
        ) {
            hourlyData.forEachIndexed { index, usageMillis ->
                val intensity = if (maxUsage > 0) (usageMillis.toFloat() / maxUsage) else 0f
                val color = Color.Blue.copy(alpha = max(0.1f, intensity)) // Ensure some visibility

                Box(modifier = Modifier.weight(1f).height(cellHeight)) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(color = color, size = Size(size.width, size.height))
                    }
                    Text(
                        text = "%02d".format(index), // Hour (00-23)
                        modifier = Modifier.align(Alignment.Center),
                        fontSize = 10.sp,
                        color = if (intensity > 0.5f) Color.White else Color.Black
                    )
                }
            }
        }
        // Optional: Add labels for hours below the heatmap if needed
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Text("00h", fontSize = 10.sp)
            Text("06h", fontSize = 10.sp)
            Text("12h", fontSize = 10.sp)
            Text("18h", fontSize = 10.sp)
            Text("23h", fontSize = 10.sp)
        }

    }
}

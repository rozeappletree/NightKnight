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
import com.example.local_ai.db.AppUsageDao
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import java.text.SimpleDateFormat
import java.util.Locale


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

suspend fun processEventsForMultiDayHourlyHeatmap(
    appUsageDao: AppUsageDao,
    numberOfDays: Int
): List<List<Long>> {
    val multiDayData = mutableListOf<List<Long>>()
    val calendar = Calendar.getInstance()

    // Data is ordered from oldest (index 0) to newest/today (index numberOfDays-1)
    for (dayAgo in (numberOfDays - 1) downTo 0) {
        calendar.timeInMillis = System.currentTimeMillis()
        calendar.add(Calendar.DAY_OF_YEAR, -dayAgo)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val dayStartMillis = calendar.timeInMillis
        val dayEndMillis = dayStartMillis + 24 * 60 * 60 * 1000 - 1

        val dailyEvents = appUsageDao.getEventsForHeatmap(dayStartMillis, dayEndMillis)
        val hourlyDataForDay = processEventsForHourlyHeatmap(dailyEvents, dayStartMillis)
        multiDayData.add(hourlyDataForDay)
    }
    return multiDayData
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
    var multiDayHourlyUsageData by remember { mutableStateOf<List<List<Long>>>(List(21) { List(24) { 0L } }) }

    fun fetchAppCounts() {
        coroutineScope.launch {
            appUsageStats = appUsageDao.getAppUsageCounts()
        }
    }

    fun fetchHeatmapData() {
        coroutineScope.launch {
            multiDayHourlyUsageData = processEventsForMultiDayHourlyHeatmap(appUsageDao, 21)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp) // Overall screen padding
    ) {
        if (!usagePermissionGranted) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Usage stats permission is required to track app usage.")
                Button(onClick = { requestUsageStatsPermission(context) }) {
                    Text("Grant Permission")
                }
                Button(onClick = {
                    usagePermissionGranted = hasUsageStatsPermission(context)
                    if (usagePermissionGranted) {
                        startAppUsageMonitorService(context)
                        fetchAppCounts()
                        fetchHeatmapData()
                    }
                }) {
                    Text("Check Permission & Start Service")
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(onClick = {
                    startAppUsageMonitorService(context)
                }) {
                    Text("Ensure Monitor Service is Running")
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text("App Usage Counts:", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Button(onClick = ::fetchAppCounts) {
                    Text("Refresh Usage Counts")
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
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

                Text("Last 21 Days Usage Heatmap (by hour):", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Button(onClick = ::fetchHeatmapData) {
                    Text("Refresh Heatmap")
                }
                Spacer(modifier = Modifier.height(8.dp))
                MultiDayHourlyHeatmapGrid(dailyHourlyData = multiDayHourlyUsageData)

                LaunchedEffect(usagePermissionGranted) {
                    if (usagePermissionGranted) {
                        fetchAppCounts()
                        fetchHeatmapData()
                    }
                }
            }
        }
    }
}

@Composable
fun MultiDayHourlyHeatmapGrid(
    dailyHourlyData: List<List<Long>>,
    modifier: Modifier = Modifier
) {
    val numberOfDays = dailyHourlyData.size
    if (numberOfDays == 0 || dailyHourlyData.any { it.size != 24 }) {
        Text("Heatmap data not available or invalid.")
        return
    }

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp

    val cellHeight = 10.dp // Halved from 18.dp
    val dayLabelWidth = 35.dp // May need to be adjusted for "MMM dd"
    val hourLabelHeight = 18.dp
    val cellSpacing = 1.dp // MODIFIED
    val screenHorizontalPadding = 32.dp // 16.dp on each side from AppUsageStatsScreen's main Column

    val availableWidthForHeatmapComponent = screenWidthDp - screenHorizontalPadding
    val fixedRowWidthElements = (dayLabelWidth + (cellSpacing * 24))

    val extra = 4 
    val calculatedCellWidth = (availableWidthForHeatmapComponent - fixedRowWidthElements) / (24 + extra) 

    val finalCellWidth = if (calculatedCellWidth > 1.dp) calculatedCellWidth else 1.dp

    val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
    val calendar = Calendar.getInstance()

    Column(
        modifier = modifier.fillMaxWidth(), 
        verticalArrangement = Arrangement.spacedBy(cellSpacing)
    ) {
        // Header Row for Hours (00h to 23h)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(cellSpacing)
        ) {
            Spacer(Modifier.width(dayLabelWidth)) 
            (0 until 24).forEach { hourIndex ->
                Box(
                    modifier = Modifier
                        .width(finalCellWidth) 
                        .height(hourLabelHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "%02d".format(hourIndex),
                        fontSize = 8.sp 
                    )
                }
            }
        }

        // Grid: Rows for days, displaying Today at the top
        // dayIndex is the visual row index (0 = top row)
        (0 until numberOfDays).forEach { dayIndex ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(cellSpacing)
            ) {
                val dateText = if (dayIndex == 0) {
                    "Today"
                } else {
                    val tempCalendar = Calendar.getInstance()
                    tempCalendar.add(Calendar.DAY_OF_YEAR, -dayIndex)
                    sdf.format(tempCalendar.time).toUpperCase(Locale.getDefault())
                }
                Text(
                    text = dateText,
                    modifier = Modifier.width(dayLabelWidth),
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center
                )

                // Map visual row index (dayIndex) to data index in dailyHourlyData
                // dailyHourlyData[0] is the oldest, dailyHourlyData[numberOfDays-1] is today
                val dataIndex = numberOfDays - 1 - dayIndex
                (0 until 24).forEach { hourIndex ->
                    val usageMillis = dailyHourlyData.getOrNull(dataIndex)?.getOrNull(hourIndex) ?: 0L
                    val usageMinutes = usageMillis / (1000.0 * 60.0)
                    val alpha = (usageMinutes / 60.0).toFloat().coerceIn(0.0f, 1.0f)
                    val cellColor = Color.Red.copy(alpha = alpha)

                    Box(
                        modifier = Modifier
                            .width(finalCellWidth) 
                            .height(cellHeight)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRect(color = cellColor, size = Size(size.width, size.height))
                        }
                    }
                }
            }
        }
    }
}


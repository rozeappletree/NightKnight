package com.example.local_ai.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
// import com.example.local_ai.data.db.AppDatabase // Changed from com.example.local_ai.db.AppDatabase
// import com.example.local_ai.data.db.AppUsageEvent // Changed from com.example.local_ai.db.AppUsageEvent
// import com.example.local_ai.data.db.AppUsageStat // Changed from com.example.local_ai.db.AppUsageStat
import com.example.local_ai.FloatingIconService // Changed from com.example.local_ai.services.AppUsageMonitorService
import kotlinx.coroutines.launch
import kotlin.random.Random // Added import
import java.util.Calendar
import android.app.usage.UsageEvents // For event type constants
// import com.example.local_ai.data.db.AppUsageDao // Changed from com.example.local_ai.db.AppUsageDao
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- Dummy Data Classes ---
data class DummyAppUsageStat(val packageName: String, val usage_count: Int)
data class DummyAppUsageEvent(val packageName: String, val timestamp: Long, val eventType: Int)


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
    val serviceIntent = Intent(context, FloatingIconService::class.java) // Changed to FloatingIconService
    context.startService(serviceIntent)
}

// --- Helper functions for Heatmap Data Processing ---
fun processEventsForHourlyHeatmap(
    events: List<DummyAppUsageEvent>, // Changed to DummyAppUsageEvent
    dayStartMillis: Long,
    limitTimestamp: Long // This is the timestamp up to which events/sessions are processed
): List<Long> {
    val hourlyBuckets = LongArray(24) { 0L }
    // Note: addDurationToBuckets internally uses dayStartMillis + 24h to define the day's boundary

    val sortedEvents = events.sortedWith(compareBy({ it.packageName }, { it.timestamp }))
    val openSessions = mutableMapOf<String, Long>()

    for (event in sortedEvents) {
        // If a MOVE_TO_FOREGROUND event occurs strictly after our processing limit,
        // close any existing session for that app at limitTimestamp and skip this event for starting a new one.
        if (event.timestamp > limitTimestamp && event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
            openSessions.remove(event.packageName)?.let { fgTimestamp ->
                if (fgTimestamp < limitTimestamp) { // Ensure the session started before the limit
                    addDurationToBuckets(hourlyBuckets, fgTimestamp, limitTimestamp, dayStartMillis)
                }
            }
            continue // This event is too late to start a relevant session
        }

        // For all other event processing, ensure event times do not exceed the limitTimestamp
        val effectiveEventTimestamp = event.timestamp.coerceAtMost(limitTimestamp)

        // Skip background events that happened before the current day started if their session also started before.
        // (This check was in the original code, keeping its spirit)
        if (event.timestamp < dayStartMillis && event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
            // This might remove an app from openSessions if it was spuriously added from data before dayStartMillis
            // However, openSessions[pkg] = event.timestamp.coerceAtLeast(dayStartMillis) handles FG starts.
            // For now, let's keep it simple: if a BG event is before dayStart, it's not relevant for this day's buckets.
            continue
        }


        when (event.eventType) {
            UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                // Close any previous session for this app. It ends at effectiveEventTimestamp.
                openSessions[event.packageName]?.let { previousFgTimestamp ->
                    // Ensure previousFgTimestamp is before effectiveEventTimestamp to record valid duration
                    if (previousFgTimestamp < effectiveEventTimestamp) {
                         addDurationToBuckets(hourlyBuckets, previousFgTimestamp, effectiveEventTimestamp, dayStartMillis)
                    }
                }
                // Start a new session only if the original event timestamp is within our processing limit.
                if (event.timestamp <= limitTimestamp) {
                    openSessions[event.packageName] = event.timestamp.coerceAtLeast(dayStartMillis)
                } else {
                    // If event.timestamp > limitTimestamp, the FG event is too late to start a new session.
                    // Any prior session for this app was closed above or by the initial check in the loop.
                    openSessions.remove(event.packageName)
                }
            }
            UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                // Close the session. It ends at effectiveEventTimestamp.
                openSessions.remove(event.packageName)?.let { fgTimestamp ->
                     if (fgTimestamp < effectiveEventTimestamp) { // Ensure session started before it ended effectively
                        addDurationToBuckets(hourlyBuckets, fgTimestamp, effectiveEventTimestamp, dayStartMillis)
                     }
                }
            }
        }
    }

    // For any sessions still marked as open, they are considered to run until limitTimestamp.
    openSessions.forEach { _, fgTimestamp ->
        // Ensure the session started before the limitTimestamp to be valid.
        if (fgTimestamp < limitTimestamp) {
            addDurationToBuckets(hourlyBuckets, fgTimestamp, limitTimestamp, dayStartMillis)
        }
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

//suspend fun processEventsForMultiDayHourlyHeatmap(
//    appUsageDao: AppUsageDao, // This would need to be changed if used
//    numberOfDays: Int
//): List<List<Long>> {
//    val multiDayData = mutableListOf<List<Long>>()
//    val calendar = Calendar.getInstance()
//    val currentTime = System.currentTimeMillis() // Get current time once for consistency
//
//    // Data is ordered from oldest (index 0) to newest/today (index numberOfDays-1)
//    for (dayAgo in (numberOfDays - 1) downTo 0) {
//        calendar.timeInMillis = System.currentTimeMillis() // Reset to current day for each calculation base
//        calendar.add(Calendar.DAY_OF_YEAR, -dayAgo)
//        calendar.set(Calendar.HOUR_OF_DAY, 0)
//        calendar.set(Calendar.MINUTE, 0)
//        calendar.set(Calendar.SECOND, 0)
//        calendar.set(Calendar.MILLISECOND, 0)
//        val dayStartMillis = calendar.timeInMillis
//        // Calculate the theoretical end of the day (23:59:59.999)
//        val dayEndMillis = dayStartMillis + 24 * 60 * 60 * 1000 - 1
//
//        // Determine the timestamp up to which we process events and ongoing sessions for this day
//        val processingLimitTimestamp = if (dayAgo == 0) { // If today
//            // For today, limit processing to the current time, but not exceeding the day's actual end
//            currentTime.coerceAtMost(dayEndMillis)
//        } else { // If a past day
//            // For past days, process the full day
//            dayEndMillis
//        }
//
//        // Fetch events for the entire day from the DAO
//        // val dailyEvents = appUsageDao.getEventsForHeatmap(dayStartMillis, dayEndMillis) // Needs to be replaced with dummy data
//        val dummyEvents = listOf<DummyAppUsageEvent>() // Replace with actual dummy event generation if needed
//        // Pass the calculated processingLimitTimestamp to the hourly processing function
//        val hourlyDataForDay = processEventsForHourlyHeatmap(dummyEvents, dayStartMillis, processingLimitTimestamp)
//        multiDayData.add(hourlyDataForDay)
//    }
//    return multiDayData
//}


// --- Main Composable Screen ---
@Composable
fun AppUsageStatsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // val db = remember { AppDatabase.getDatabase(context) }
    // val appUsageDao = remember { db.appUsageDao() }
    val coroutineScope = rememberCoroutineScope()

    var usagePermissionGranted by remember { mutableStateOf(hasUsageStatsPermission(context)) }
    var appUsageStats by remember { mutableStateOf<List<DummyAppUsageStat>>(emptyList()) }
    var multiDayHourlyUsageData by remember { mutableStateOf<List<List<Long>>>(List(21) { List(24) { 0L } }) }

    fun fetchAppCounts() {
        coroutineScope.launch {
            // appUsageStats = appUsageDao.getAppUsageCounts()
            appUsageStats = listOf(
                DummyAppUsageStat("com.whatsapp", 25),
                DummyAppUsageStat("com.android.chrome", 18),
                DummyAppUsageStat("com.google.android.gm", 15),
                DummyAppUsageStat("com.discord", 12)
            )
        }
    }

    fun fetchHeatmapData() {
        coroutineScope.launch {
            val numberOfDays = 21 // This now means 21 past days, not including today
            val numberOfEarlyPeakDays = 2 // Number of days to have an early morning peak
            val earlyPeakDayIndices = (0 until numberOfDays).shuffled(Random).take(numberOfEarlyPeakDays).toSet()

            // Store the specific zero-usage hours for each day
            val dailyZeroNightHours = mutableMapOf<Int, Set<Int>>()
            // Hours from 11 PM (23) to 5 AM (inclusive, so up to 05:59:59)
            val nightHoursWindow = listOf(23, 0, 1, 2, 3, 4, 5)

            (0 until numberOfDays).forEach { dayIndex ->
                val zeroBlockSize = if (Random.nextBoolean()) 4 else 5 // 4 or 5 hours of zero usage
                val possibleStartSlots = nightHoursWindow.size - zeroBlockSize
                // This condition (possibleStartSlots >= 0) is always true here since window size is 7 and block is 4 or 5
                val startOffsetInWindow = Random.nextInt(0, possibleStartSlots + 1)
                val zeroHoursForThisDay = mutableSetOf<Int>()
                for (i in 0 until zeroBlockSize) {
                    zeroHoursForThisDay.add(nightHoursWindow[startOffsetInWindow + i])
                }
                dailyZeroNightHours[dayIndex] = zeroHoursForThisDay
            }

            val dummyHeatmap = List(numberOfDays) { dayIndex ->
                val randomJitter = Random.nextDouble(0.85, 1.15)
                val isEarlyPeakDay = dayIndex in earlyPeakDayIndices
                val isInconsistentDay = !isEarlyPeakDay && Random.nextDouble() < 0.2 // 20% chance for non-early peak days

                List(24) { hourIndex ->
                    var usageMillis: Long
                    val zeroNightHoursSet = dailyZeroNightHours[dayIndex]

                    // Apply low night usage override first for all days
                    if (zeroNightHoursSet != null && hourIndex in zeroNightHoursSet) {
                        usageMillis = 0L
                    } else {
                        // Original pattern logic
                        if (isEarlyPeakDay) {
                            // Early Morning Peak Day Pattern:
                            when (hourIndex) {
                                in 0..4 -> usageMillis = Random.nextLong(0L, 5 * 60 * 1000L)      // Night: Very low (0-5 min)
                                5 -> usageMillis = Random.nextLong(5 * 60 * 1000L, 15 * 60 * 1000L) // Dawn: Low (5-15 min)
                                in 6..8 -> usageMillis = Random.nextLong(30 * 60 * 1000L, 55 * 60 * 1000L) // Early Morning Peak (30-55 min)
                                in 9..11 -> usageMillis = Random.nextLong(10 * 60 * 1000L, 25 * 60 * 1000L) // Late Morning: Medium-Low (10-25 min)
                                in 12..16 -> usageMillis = Random.nextLong(5 * 60 * 1000L, 20 * 60 * 1000L) // Afternoon: Low-Medium (5-20 min)
                                in 17..19 -> usageMillis = Random.nextLong(3 * 60 * 1000L, 15 * 60 * 1000L) // Early Evening: Low (3-15 min)
                                in 20..22 -> usageMillis = Random.nextLong(1 * 60 * 1000L, 10 * 60 * 1000L) // Evening: Very Low (1-10 min)
                                23 -> usageMillis = Random.nextLong(0L, 5 * 60 * 1000L)       // Late Night: Very low (0-5 min)
                                else -> usageMillis = 0L
                            }
                        } else if (isInconsistentDay) {
                            // Inconsistent day patterns:
                            if (Random.nextDouble() < 0.5) {
                                // Pattern 1: Very low activity throughout the day
                                usageMillis = Random.nextLong(0L, 10 * 60 * 1000L) // 0-10 minutes
                            } else {
                                // Pattern 2: Unusual peak at a random hour, low otherwise
                                val peakHour = Random.nextInt(0, 24)
                                if (hourIndex == peakHour) {
                                    usageMillis = Random.nextLong(45 * 60 * 1000L, 60 * 60 * 1000L) // 45-60 minutes
                                } else if (hourIndex == (peakHour - 1 + 24) % 24 || hourIndex == (peakHour + 1) % 24) {
                                    usageMillis = Random.nextLong(10 * 60 * 1000L, 25 * 60 * 1000L) // 10-25 minutes around peak
                                } else {
                                    usageMillis = Random.nextLong(0L, 5 * 60 * 1000L) // 0-5 minutes otherwise
                                }
                            }
                        } else {
                            // Normal day pattern:
                            when (hourIndex) {
                                in 0..5 -> usageMillis = Random.nextLong(0L, 5 * 60 * 1000L)      // Night: Very low (0-5 min)
                                in 6..8 -> usageMillis = Random.nextLong(3 * 60 * 1000L, 15 * 60 * 1000L)  // Early Morning: Low (3-15 min)
                                in 9..11 -> usageMillis = Random.nextLong(10 * 60 * 1000L, 25 * 60 * 1000L) // Late Morning: Medium-Low (10-25 min)
                                in 12..13 -> usageMillis = Random.nextLong(15 * 60 * 1000L, 35 * 60 * 1000L)// Lunch Time: Medium (15-35 min)
                                in 14..16 -> usageMillis = Random.nextLong(30 * 60 * 1000L, 55 * 60 * 1000L)// Afternoon: Peak (30-55 min)
                                in 17..19 -> usageMillis = Random.nextLong(20 * 60 * 1000L, 45 * 60 * 1000L)// Late Afternoon: High-Medium (20-45 min)
                                in 20..22 -> usageMillis = Random.nextLong(15 * 60 * 1000L, 30 * 60 * 1000L)// Evening: Medium (15-30 min)
                                23 -> usageMillis = Random.nextLong(5 * 60 * 1000L, 15 * 60 * 1000L) // Late Night: Low (5-15 min)
                                else -> usageMillis = 0L
                            }
                        }
                    }
                    // Apply jitter and ensure within 0 to 1 hour (3,600,000 ms)
                    (usageMillis * randomJitter).toLong().coerceIn(0L, 3600000L)
                }
            }
            multiDayHourlyUsageData = dummyHeatmap
        }
    }

    LaunchedEffect(usagePermissionGranted) {
        if (usagePermissionGranted) {
            fetchAppCounts()
            fetchHeatmapData()
            // Removed continuous fetching for dummy data, can be re-enabled if needed for UI testing
            // while (true) {
            //     fetchAllEvents() // This call was removed
            //     delay(2000) // Refresh every 2 seconds
            // }
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
                Button(
                    onClick = { requestUsageStatsPermission(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5070FE), contentColor = Color.White)
                ) {
                    Text("Grant Permission")
                }
                Button(
                    onClick = {
                        usagePermissionGranted = hasUsageStatsPermission(context)
                        if (usagePermissionGranted) {
                            startAppUsageMonitorService(context)
                            // Initial fetch will be triggered by LaunchedEffect
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5070FE), contentColor = Color.White)
                ) {
                    Text("Check Permission & Start Service")
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // "Ensure Monitor Service is Running" button removed

                Spacer(modifier = Modifier.height(10.dp))

                Text("App Usage Counts:", style = MaterialTheme.typography.titleMedium)
                // "Refresh Usage Counts" button removed
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) { // Reduced height
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

                Text("Last 21 Days Usage Heatmap (by hour):", style = MaterialTheme.typography.titleMedium)
                // "Refresh Heatmap" button removed
                Spacer(modifier = Modifier.height(8.dp))
                MultiDayHourlyHeatmapGrid(dailyHourlyData = multiDayHourlyUsageData)

                // The "All App Usage Events (SQL Table Debug)" section has been removed from here.
            }
        }
    }
}

@Composable
fun HeatmapLegend(modifier: Modifier = Modifier) {
    val legendItems = listOf(
        Pair(">45min", Color.Red.copy(alpha = 1.0f)), // Very Dark Red
        Pair("30-45min", Color.Red.copy(alpha = 0.7f)), // Dark Red
        Pair("15-30min", Color.Red.copy(alpha = 0.35f)), // Light Red
        Pair(">0-15min", Color.Red.copy(alpha = 0.15f)), // Very Light Red
        Pair("0min", Color(0x10F0F0F0)) // Light Gray for no usage
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, // Distributes space evenly
        verticalAlignment = Alignment.CenterVertically
    ) {
        legendItems.forEach { (text, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) { // Added padding for spacing between items
                Box(
                    modifier = Modifier
                        .size(14.dp) // Slightly smaller box
                        .background(color)
                        .border(0.5.dp, Color.DarkGray) // Thinner border
                )
                Spacer(modifier = Modifier.width(3.dp)) // Slightly less space
                Text(text, fontSize = 9.sp) // Slightly smaller font
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

    val cellHeight = 24.dp
    val dayLabelWidth = 35.dp
    val hourLabelHeight = 18.dp
    val cellSpacing = 1.dp
    val screenHorizontalPadding = 32.dp // From AppUsageStatsScreen

    val availableWidthForHeatmapComponent = screenWidthDp - screenHorizontalPadding
    val fixedRowWidthElements = (dayLabelWidth + (cellSpacing * 24))
    
    val extra = 4
    val calculatedCellWidth = (availableWidthForHeatmapComponent - fixedRowWidthElements) / (24 + extra)
    val finalCellWidth = if (calculatedCellWidth > 1.dp) calculatedCellWidth else 1.dp

    val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(cellSpacing)
    ) {
        HeatmapLegend() // Display the legend

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

        // Grid: Rows for days
        (0 until numberOfDays).forEach { dayIndex ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(cellSpacing)
            ) {
                // val dateText = if (dayIndex == 0) { // This logic makes the top row "TODAY"
                //     "TODAY "
                // } else {
                //     val tempCalendar = Calendar.getInstance()
                //     // To show days in sequence from oldest to newest, with "TODAY" at the bottom:
                //     // tempCalendar.add(Calendar.DAY_OF_YEAR, -(numberOfDays - 1 - dayIndex))
                //     // To show days in sequence from newest to oldest, with "TODAY" at the top:
                //     tempCalendar.add(Calendar.DAY_OF_YEAR, -dayIndex)
                //     sdf.format(tempCalendar.time).toUpperCase(Locale.getDefault())
                // }
                val tempCalendar = Calendar.getInstance()
                // dayIndex = 0 (top row, newest data) is yesterday.
                // dayIndex = 1 is day before yesterday, and so on.
                // The date is (Today - (dayIndex + 1) days).
                tempCalendar.add(Calendar.DAY_OF_YEAR, -(dayIndex + 1))
                val dateText = sdf.format(tempCalendar.time).toUpperCase(Locale.getDefault())
                Text(
                    text = dateText,
                    modifier = Modifier.width(dayLabelWidth),
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center
                )

                // The dailyHourlyData is already in order from oldest (index 0) to newest (index numberOfDays-1)
                // So, if we want to display "YESTERDAY" (most recent PAST day) at the top of the grid (dayIndex 0),
                // we need to access the end of the dailyHourlyData list.
                val dataIndex = numberOfDays - 1 - dayIndex 
                (0 until 24).forEach { hourIndex ->
                    val usageMillis = dailyHourlyData.getOrNull(dataIndex)?.getOrNull(hourIndex) ?: 0L
                    val usageMinutes = usageMillis / (1000.0 * 60.0)

                    val cellColor = when {
                        usageMinutes > 45 -> Color.Red.copy(alpha = 1.0f)
                        usageMinutes > 30 -> Color.Red.copy(alpha = 0.7f)
                        usageMinutes > 15 -> Color.Red.copy(alpha = 0.35f) // Corrected to match legend
                        usageMinutes > 0 -> Color.Red.copy(alpha = 0.15f)   // Corrected to match legend
                        else -> Color(0x10F0F0F0) // transparent gray
                    }

                    val textColor = Color.White

                    Box(
                        modifier = Modifier
                            .width(finalCellWidth)
                            .height(cellHeight),
                        contentAlignment = Alignment.Center // Center content (Text) in the Box
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawRect(color = cellColor, size = Size(size.width, size.height))
                        }
                        if (usageMinutes > 0) { // Only show text if usage is greater than 0
                            Text(
                                text = usageMinutes.toInt().toString(),
                                color = textColor,
                                fontSize = 4.sp,
                                textAlign = TextAlign.Center // Ensure text itself is centered if it spans multiple lines (though unlikely here)
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.example.local_ai.ui.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.local_ai.data.db.AppUsageDao // Added this import
// import com.example.local_ai.data.db.AppUsageEvent // Keep if needed, or remove if not
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

// Data classes (assuming these are already defined as per your previous context)
data class DigestGeneralStats(
    val period: String,
    val usageTime: String,
    val usageTimeChange: String,
    val deviceChecks: String,
    val deviceChecksChange: String
)

data class AppUsageStat(
    val appName: String,
    val usageTime: String,
    val usageTimeChange: String,
    val accessCount: String,
    val accessCountChange: String
    // Removed color field
)

data class CategoryUsageStat(
    val categoryName: String,
    val usageTimePercentage: String, // e.g., "2h 37m (36.2%)"
    val usageTimeChange: String
    // Removed color field
)

data class HourlyUsageStat(
    val hour: String, // e.g., "12 AM", "1 AM", ..., "11 PM"
    val usageTime: String, // e.g., "56m 42s"
    val usageProportion: Float // Value between 0.0 and 1.0 for the bar
)

// Removed the local UsageDao interface definition

class DailyUsageDigestViewModel(
    private val usageDao: AppUsageDao // Changed to AppUsageDao
) : ViewModel() {

    private val _currentDate = MutableStateFlow("")
    val currentDate: StateFlow<String> = _currentDate

    private val _digestStats = MutableStateFlow<List<DigestGeneralStats>>(emptyList())
    val digestStats: StateFlow<List<DigestGeneralStats>> = _digestStats

    private val _topApps = MutableStateFlow<List<AppUsageStat>>(emptyList())
    val topApps: StateFlow<List<AppUsageStat>> = _topApps

    private val _pinnedApps = MutableStateFlow<List<AppUsageStat>>(emptyList())
    val pinnedApps: StateFlow<List<AppUsageStat>> = _pinnedApps

    private val _categoryUsage = MutableStateFlow<List<CategoryUsageStat>>(emptyList())
    val categoryUsage: StateFlow<List<CategoryUsageStat>> = _categoryUsage

    private val _hourlyUsage = MutableStateFlow<List<HourlyUsageStat>>(emptyList())
    val hourlyUsage: StateFlow<List<HourlyUsageStat>> = _hourlyUsage

    private val _ignoredAppCount = MutableStateFlow(0)
    val ignoredAppCount: StateFlow<Int> = _ignoredAppCount

    init {
        // For simplicity, using today's date. You might want to allow date selection.
        val todayCalendar = Calendar.getInstance()
        loadDigestData(todayCalendar)
    }

    // Overload or modify to accept a Calendar instance for the date to load
    fun loadDigestData(dateToLoad: Calendar) {
        viewModelScope.launch {
            // Set current date string
            _currentDate.value = SimpleDateFormat("EEEE, d MMMM, yyyy", Locale.getDefault()).format(dateToLoad.time)

            // Calculate dates for "Daily Average" and the two previous days
            val digestSdf = SimpleDateFormat("d MMM", Locale.getDefault())
            val calendar = dateToLoad.clone() as Calendar

            calendar.add(Calendar.DAY_OF_YEAR, -1)
            val day1Date = calendar.time
            val day1DateString = digestSdf.format(day1Date)

            calendar.add(Calendar.DAY_OF_YEAR, -1)
            val day2Date = calendar.time
            val day2DateString = digestSdf.format(day2Date)
            
            val dailyAveragePeriod = "Daily Average\n($day2DateString - $day1DateString)"

            // --- Placeholder data for other sections ---
            _digestStats.value = listOf(
                DigestGeneralStats(dailyAveragePeriod, "6h 45m", "+5%", "85", "-10%"),
                DigestGeneralStats(SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(day1Date), "7h 16m", "-19%", "121", "+15%"),
                DigestGeneralStats(SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(day2Date), "8h 30m", "+25%", "98", "-5%")
            )
            _topApps.value = listOf(
                AppUsageStat("Minimalist App", "3h 30m", "+20%", "150", "+5%"),
                AppUsageStat("Browser", "1h 45m", "-10%", "60", "-15%"),
                AppUsageStat("Email Client", "0h 55m", "+5%", "30", "+10%"),
                AppUsageStat("Reading App", "0h 45m", "+100%", "20", "+50%")
            )
            _pinnedApps.value = listOf(
                AppUsageStat("Meditation App", "0h 25m", "+15%", "10", "+2%"),
                AppUsageStat("Todo List", "0h 15m", "-5%", "25", "-8%")
            )
            _categoryUsage.value = listOf(
                CategoryUsageStat("Productivity", "3h 10m (42.0%)", "+12%"),
                CategoryUsageStat("Communication", "1h 50m (24.3%)", "-5%"),
                CategoryUsageStat("Information", "1h 15m (16.6%)", "+30%"),
                CategoryUsageStat("Utilities", "0h 40m (8.8%)", "-20%"),
                CategoryUsageStat("Wellness", "0h 25m (5.5%)", "+8%")
            )
            _ignoredAppCount.value = 3 // Example: 3 apps ignored

            // --- Dummy Hourly Usage Data for Demo ---
            val hourCalendar = Calendar.getInstance() // Keep for hour formatting
            val hourFormatter = SimpleDateFormat("h a", Locale.getDefault())
            val dummyHourlyStats = mutableListOf<HourlyUsageStat>()
            val random = Random(dateToLoad.timeInMillis) // Seed random for consistency on same date

            // Generate more varied dummy data for hourly usage
            for (hour in 0..23) {
                val tempCal = Calendar.getInstance()
                tempCal.set(Calendar.HOUR_OF_DAY, hour)
                val hourString = hourFormatter.format(tempCal.time)

                // Simulate some peak hours and some low usage hours
                val usageMillis = when (hour) {
                    0 -> random.nextLong(10 * 60 * 1000) // 0-10 mins for 12 AM (midnight)
                    in 1..5 -> 0L // 0 usage for 1 AM to 5 AM
                    in 6..8 -> random.nextLong(20 * 60 * 1000, 50 * 60 * 1000) // 20-50 mins morning peak
                    in 9..11 -> random.nextLong(15 * 60 * 1000, 40 * 60 * 1000) // 15-40 mins late morning
                    in 12..13 -> random.nextLong(30 * 60 * 1000, 60 * 60 * 1000) // 30-60 mins lunch peak
                    in 14..17 -> random.nextLong(20 * 60 * 1000, 45 * 60 * 1000) // 20-45 mins afternoon
                    in 18..20 -> random.nextLong(40 * 60 * 1000, 70 * 60 * 1000) // 40-70 mins evening peak
                    else -> random.nextLong(5 * 60 * 1000, 25 * 60 * 1000) // 5-25 mins late night
                }
                dummyHourlyStats.add(
                    HourlyUsageStat(
                        hour = hourString,
                        usageTime = formatDuration(usageMillis),
                        usageProportion = 0f // Will be calculated next
                    )
                )
            }

            // Calculate proportions based on the max usage in the dummy data
            val maxHourlyUsageForDay = dummyHourlyStats.maxOfOrNull { parseDuration(it.usageTime) } ?: 1L
            _hourlyUsage.value = dummyHourlyStats.map { stat ->
                val usageMillis = parseDuration(stat.usageTime)
                stat.copy(usageProportion = if (maxHourlyUsageForDay > 0) usageMillis.toFloat() / maxHourlyUsageForDay else 0f)
            }
        }
    }

    private fun formatDuration(millis: Long): String {
        if (millis <= 0) return "0s" // Return "0s" if no usage or negative
        val hours = (millis / (1000 * 60 * 60))
        val minutes = ((millis / (1000 * 60)) % 60)
        val seconds = ((millis / 1000) % 60)

        val sb = StringBuilder()
        if (hours > 0) sb.append("${hours}h ")
        if (minutes > 0) sb.append("${minutes}m ")
        // Always show seconds if hours and minutes are zero, or if seconds > 0
        if (seconds > 0 || (hours == 0L && minutes == 0L)) sb.append("${seconds}s")
        
        return sb.toString().trim().ifEmpty { "0s" } // Ensure "0s" if somehow empty
    }

    private fun parseDuration(durationString: String): Long {
        if (durationString == "0s" || durationString.isBlank()) return 0L
        var totalMillis: Long = 0
        val parts = durationString.split(" ")
        try {
            for (part in parts) {
                when {
                    part.endsWith("h") -> totalMillis += part.dropLast(1).toLong() * 60 * 60 * 1000
                    part.endsWith("m") -> totalMillis += part.dropLast(1).toLong() * 60 * 1000
                    part.endsWith("s") -> totalMillis += part.dropLast(1).toLong() * 1000
                }
            }
        } catch (e: NumberFormatException) {
            // Log error or handle more gracefully
            return 0L // Default to 0 if parsing fails
        }
        return totalMillis
    }
}

// Factory for creating DailyUsageDigestViewModel with UsageDao dependency
class DailyUsageDigestViewModelFactory(private val usageDao: AppUsageDao) : ViewModelProvider.Factory { // Changed to AppUsageDao
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DailyUsageDigestViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DailyUsageDigestViewModel(usageDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

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
)

data class CategoryUsageStat(
    val categoryName: String,
    val usageTimePercentage: String, // e.g., "2h 37m (36.2%)"
    val usageTimeChange: String
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

            // --- Placeholder data for other sections ---
            // TODO: Replace these with actual data fetching logic using your DAO
            _digestStats.value = listOf(
                DigestGeneralStats("Thu, 7 Aug", "7h 16m", "-19%", "#21", "-40%"),
                // ... other placeholder stats
            )
            _topApps.value = listOf(
                AppUsageStat("minimalist phone", "2h 13m", "+90%", "#217", "-3%"),
                // ... other placeholder apps
            )
            _pinnedApps.value = emptyList()
            _categoryUsage.value = listOf(
                CategoryUsageStat("Productivity", "2h 37m (36.2%)", "+74%"),
                // ... other placeholder categories
            )
            _ignoredAppCount.value = 0 // TODO: Fetch actual count
            // --- End placeholder data ---



            // --- Load Hourly Usage Data from DAO ---
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = dateToLoad.timeInMillis // Use the passed date

            val hourlyStatsList = mutableListOf<HourlyUsageStat>()
            var maxHourlyUsageForDay: Long = 1L // Avoid division by zero, min 1ms

            val hourFormatter = SimpleDateFormat("h a", Locale.getDefault())

            for (hour in 0..23) {
                // Set calendar to the start of the current hour for the given date
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val hourStartTimestamp = calendar.timeInMillis

                // Set calendar to the end of the current hour (start of next hour)
                calendar.add(Calendar.HOUR_OF_DAY, 1)
                val hourEndTimestamp = calendar.timeInMillis
                calendar.add(Calendar.HOUR_OF_DAY, -1) // Reset for next iteration


                // Fetch summed usage for this specific hour from the DAO
                val usageMillisForHour = try {
                    usageDao.getSummedUsageForHour(hourStartTimestamp, hourEndTimestamp) ?: 0L
                } catch (e: Exception) {
                    // Handle any exceptions from DAO, e.g., log error
                    // For now, defaulting to 0
                    0L
                }


                if (usageMillisForHour > maxHourlyUsageForDay) {
                    maxHourlyUsageForDay = usageMillisForHour
                }

                val usageTimeString = formatDuration(usageMillisForHour)
                // Get the string representation for the hour (e.g., "12 AM", "1 PM")
                val tempCal = Calendar.getInstance()
                tempCal.set(Calendar.HOUR_OF_DAY, hour)
                val hourString = hourFormatter.format(tempCal.time)


                hourlyStatsList.add(
                    HourlyUsageStat(
                        hour = hourString,
                        usageTime = usageTimeString,
                        usageProportion = 0f // Will be calculated in a second pass
                    )
                )
            }

            // Second pass to calculate proportion based on the max usage in any hour of that day
            _hourlyUsage.value = hourlyStatsList.map { stat ->
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

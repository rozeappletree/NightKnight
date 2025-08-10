package com.example.local_ai.ui.usage

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Data classes to represent the structure of your digest

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
    val hour: String, // e.g., "11 pm"
    val usageTime: String, // e.g., "56m 42s"
    val usageProportion: Float // Value between 0.0 and 1.0 for the bar
)

class DailyUsageDigestViewModel : ViewModel() {

    private val _currentDate = MutableStateFlow("")
    val currentDate: StateFlow<String> = _currentDate

    private val _digestStats = MutableStateFlow<List<DigestGeneralStats>>(emptyList())
    val digestStats: StateFlow<List<DigestGeneralStats>> = _digestStats

    private val _topApps = MutableStateFlow<List<AppUsageStat>>(emptyList())
    val topApps: StateFlow<List<AppUsageStat>> = _topApps

    private val _pinnedApps = MutableStateFlow<List<AppUsageStat>>(emptyList()) // Assuming similar structure
    val pinnedApps: StateFlow<List<AppUsageStat>> = _pinnedApps

    private val _categoryUsage = MutableStateFlow<List<CategoryUsageStat>>(emptyList())
    val categoryUsage: StateFlow<List<CategoryUsageStat>> = _categoryUsage

    private val _hourlyUsage = MutableStateFlow<List<HourlyUsageStat>>(emptyList())
    val hourlyUsage: StateFlow<List<HourlyUsageStat>> = _hourlyUsage

    private val _ignoredAppCount = MutableStateFlow(0)
    val ignoredAppCount: StateFlow<Int> = _ignoredAppCount

    init {
        loadDigestData()
    }

    fun loadDigestData() {
        // Placeholder data - replace with actual data fetching logic from your DB
        _currentDate.value = SimpleDateFormat("EEEE, d MMMM, yyyy", Locale.getDefault()).format(Date())

        _digestStats.value = listOf(
            DigestGeneralStats("Thu, 7 Aug", "7h 16m", "-19%", "#21", "-40%"),
            DigestGeneralStats("Last 7 days", "2d 6h", "+59%", "#261", "-12%"),
            DigestGeneralStats("Last 30 days", "8d 14h", "-14%", "#1353", "-41%")
        )

        _topApps.value = listOf(
            AppUsageStat("minimalist phone", "2h 13m", "+90%", "#217", "-3%"),
            AppUsageStat("local-ai", "1h 25m", "+208%", "#89", "+187%"),
            AppUsageStat("X", "50m 55s", "+91%", "#72", "+29%"),
            AppUsageStat("Discord", "36m 47s", "+9%", "#54", "-34%"),
            AppUsageStat("YouTube", "35m 32s", "-84%", "#24", "-45%")
            // Add more apps as needed
        )

        _pinnedApps.value = emptyList() // Populate if you have pinned apps data

        _categoryUsage.value = listOf(
            CategoryUsageStat("Productivity", "2h 37m (36.2%)", "+74%"),
            CategoryUsageStat("Others", "1h 37m (22.3%)", "+75%"),
            CategoryUsageStat("Social & Communication", "1h 29m (20.6%)", "-40%")
            // Add more categories
        )

        _hourlyUsage.value = listOf(
            HourlyUsageStat("11 pm", "56m 42s", 0.9f),
            HourlyUsageStat("10 pm", "55m 12s", 0.85f),
            HourlyUsageStat("9 pm", "56m 12s", 0.9f),
            HourlyUsageStat("8 pm", "59m 1s", 0.98f),
            HourlyUsageStat("7 pm", "59m 27s", 0.99f),
            HourlyUsageStat("6 pm", "59m 51s", 1.0f),
            HourlyUsageStat("5 pm", "59m 44s", 1.0f),
            HourlyUsageStat("4 pm", "1m 9s", 0.02f),
            HourlyUsageStat("3 pm", "18m 38s", 0.3f),
            HourlyUsageStat("2 pm", "16s", 0.01f),
            HourlyUsageStat("1 pm", "0s", 0.0f),
            HourlyUsageStat("12 pm", "0s", 0.0f)
            // ... Add all 24 hours
        )
        _ignoredAppCount.value = 6
    }
}

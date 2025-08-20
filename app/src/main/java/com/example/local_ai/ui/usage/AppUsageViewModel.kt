package com.example.local_ai.ui.usage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.local_ai.data.db.AppDatabase
import com.example.local_ai.data.db.AppUsageEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

data class FormattedAppUsageEvent(
    val time: String,
    val appName: String,
    val usageTime: String,
    val accessCount: Int
)

class AppUsageViewModel(application: Application) : AndroidViewModel(application) {

    private val appUsageDao = AppDatabase.getDatabase(application).appUsageDao()

    private val _dailyActivities = MutableStateFlow<List<FormattedAppUsageEvent>>(emptyList())
    val dailyActivities: StateFlow<List<FormattedAppUsageEvent>> = _dailyActivities.asStateFlow()

    private val timeFormatter = SimpleDateFormat("hh:mm a", Locale.getDefault())

    fun loadDailyActivitiesForToday() {
        val calendar = Calendar.getInstance()
        loadDailyActivities(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun loadDailyActivities(year: Int, month: Int, day: Int) {
        viewModelScope.launch {
            val calendar = Calendar.getInstance()
            calendar.set(year, month, day, 0, 0, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            val startTimeMillis = calendar.timeInMillis

            calendar.set(year, month, day, 23, 59, 59)
            calendar.set(Calendar.MILLISECOND, 999)
            val endTimeMillis = calendar.timeInMillis

            appUsageDao.getEventsForPeriod(startTimeMillis, endTimeMillis)
                .collect { events ->
                    val appAccessCounts = events.groupingBy { it.appName }.eachCount()
                    _dailyActivities.value = events.map { formatEvent(it, appAccessCounts[it.appName] ?: 0) }
                }
        }
    }

    private fun formatEvent(event: AppUsageEvent, accessCount: Int): FormattedAppUsageEvent {
        val timeString = timeFormatter.format(event.timestamp)
        
        val durationMillis = event.usageTimeMillis
        val hours = TimeUnit.MILLISECONDS.toHours(durationMillis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMillis) % 60

        val usageString = when {
            hours > 0 -> String.format("%dh %dm %ds", hours, minutes, seconds)
            minutes > 0 -> String.format("%dm %ds", minutes, seconds)
            seconds > 0 -> String.format("%ds", seconds)
            event.eventType == "SYSTEM_EVENT" && durationMillis == 0L && (event.appName.contains("boot", ignoreCase = true) || event.appName.contains("shutdown", ignoreCase = true)) -> "(0s)" // For instantaneous system events
            event.eventType == "SYSTEM_EVENT" && event.appName.contains("locked", ignoreCase = true) -> {
                 // For screen off/locked events, duration might represent the off-time
                 if (hours > 0) String.format("(%dh %dm)", hours, minutes)
                 else if (minutes > 0) String.format("(%dm %ds)", minutes, seconds)
                 else String.format("(%ds)", seconds)
            }
            else -> "" // No usage time or 0s for app usage
        }

        return FormattedAppUsageEvent(
            time = timeString,
            appName = event.appName,
            usageTime = usageString,
            accessCount = accessCount
        )
    }

    // TODO: Add functions and data structures for Daily Usage Digest
}
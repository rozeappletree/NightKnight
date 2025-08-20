package com.example.local_ai.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_usage_events")
data class AppUsageEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val timestamp: Long, // Start time of the event
    val appName: String,
    val usageTimeMillis: Long,
    val eventType: String, // E.g., "APP_USAGE", "SCREEN_ON", "DEVICE_BOOT"
    val sessionOpenCount: Int = 1 // Represents one access/opening for this session
)

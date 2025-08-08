package com.example.local_ai.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_usage_events")
data class AppUsageEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "event_type") val eventType: Int // e.g., UsageEvents.Event.MOVE_TO_FOREGROUND
)

package com.example.local_ai.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AppUsageDao {
    @Insert
    suspend fun insert(event: AppUsageEvent)

    // Query to get usage count per package
    @Query("SELECT package_name AS packageName, COUNT(*) as usage_count FROM app_usage_events GROUP BY package_name ORDER BY usage_count DESC")
    suspend fun getAppUsageCounts(): List<AppUsageStat>

    // Query to get events within a time range (for heatmap)
    @Query("SELECT * FROM app_usage_events WHERE timestamp BETWEEN :startTime AND :endTime")
    suspend fun getEventsForHeatmap(startTime: Long, endTime: Long): List<AppUsageEvent>

    // Query to get all events for debugging
    @Query("SELECT * FROM app_usage_events ORDER BY timestamp DESC")
    suspend fun getAllEvents(): List<AppUsageEvent>
}

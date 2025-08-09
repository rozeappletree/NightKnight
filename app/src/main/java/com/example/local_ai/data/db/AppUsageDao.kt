package com.example.local_ai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppUsageDao {

    @Insert
    suspend fun insertEvent(event: AppUsageEvent)

    // Example: Get all events between a start and end timestamp (e.g., for a specific day)
    @Query("SELECT * FROM app_usage_events WHERE timestamp BETWEEN :startTimeMillis AND :endTimeMillis ORDER BY timestamp DESC")
    fun getEventsForPeriod(startTimeMillis: Long, endTimeMillis: Long): Flow<List<AppUsageEvent>>

    // We will add more queries here for the digest, e.g., to sum usageTimeMillis and sessionOpenCount per appName
}

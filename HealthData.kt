package com.aethernova.nova.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/** Reads steps and sleep from Health Connect (fed by Google Fit, Samsung Health, watches, etc.). */
class HealthData(private val context: Context) {

    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    val available: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermissions(): Boolean =
        available && client.permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun stepsToday(): Long? = runCatching {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atStartOfDay(zone).toInstant()
        val res = client.aggregate(
            AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, java.time.Instant.now()))
        )
        res[StepsRecord.COUNT_TOTAL] ?: 0L
    }.getOrNull()

    suspend fun stepsBetween(from: java.time.LocalDateTime, to: java.time.LocalDateTime): Long? = runCatching {
        val zone = ZoneId.systemDefault()
        val res = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL),
            TimeRangeFilter.between(from.atZone(zone).toInstant(), to.atZone(zone).toInstant())))
        res[StepsRecord.COUNT_TOTAL] ?: 0L
    }.getOrNull()

    /** Total sleep between 18:00 yesterday and 12:00 today, in hours. */
    suspend fun sleepLastNight(): Double? = runCatching {
        val zone = ZoneId.systemDefault()
        val from = LocalDate.now().minusDays(1).atTime(18, 0).atZone(zone).toInstant()
        val to = LocalDate.now().atTime(12, 0).atZone(zone).toInstant()
        val recs = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, to))).records
        if (recs.isEmpty()) null
        else recs.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() } / 60.0
    }.getOrNull()
}

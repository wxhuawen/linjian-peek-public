package dev.linjian.wearablebridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class HealthConnectProvider(context: Context, private val deviceName: String, private val model: String) : WearableProvider {
    private val client = HealthConnectClient.getOrCreate(context.applicationContext)
    private val zone = ZoneId.systemDefault()

    override suspend fun read(): WearableSnapshot {
        val now = Instant.now()
        val today = ZonedDateTime.now(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val steps = runCatching {
            client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(today, now)))[StepsRecord.COUNT_TOTAL]
        }.getOrNull()
        val heart = runCatching {
            client.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(now.minusSeconds(86400), now))).records
                .asSequence().flatMap { it.samples.asSequence() }.maxByOrNull { it.time }
        }.getOrNull()
        val oxygen = runCatching {
            client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, TimeRangeFilter.between(now.minusSeconds(86400), now))).records
                .maxByOrNull { it.time }
        }.getOrNull()
        // “昨夜”按本地时间取昨日 18:00 到今日 12:00，避免把前一日白天小睡当成昨夜睡眠。
        val lastNightStart = today.minusSeconds(6 * 3600)
        val lastNightEnd = minOf(now, today.plusSeconds(12 * 3600))
        val sleep = runCatching {
            client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(lastNightStart, lastNightEnd))).records
                .filter { it.endTime.isAfter(lastNightStart) }.maxByOrNull { it.endTime }
        }.getOrNull()
        val sleepSnapshot = sleep?.let {
            SleepSnapshot(java.time.Duration.between(it.startTime, it.endTime).toMinutes(), it.startTime.toString(), it.endTime.toString(), it.endTime.toString())
        }
        val stepsMeasuredAt = steps?.let { now }
        val latest = listOfNotNull(stepsMeasuredAt, heart?.time, oxygen?.time, sleep?.endTime).maxOrNull()
        return WearableSnapshot(
            deviceName, model, latest?.toString(),
            steps, stepsMeasuredAt?.toString(), heart?.beatsPerMinute, heart?.time?.toString(),
            sleepSnapshot, oxygen?.percentage?.value?.toDouble(), oxygen?.time?.toString()
        )
    }
}

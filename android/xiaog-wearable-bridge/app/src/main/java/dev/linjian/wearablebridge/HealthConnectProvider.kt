package dev.linjian.wearablebridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.Duration
import kotlin.math.roundToLong

class HealthConnectProvider(context: Context, private val deviceName: String, private val model: String) : WearableProvider {
    private val client = HealthConnectClient.getOrCreate(context.applicationContext)
    private val zone = ZoneId.systemDefault()

    override suspend fun read(): WearableSnapshot {
        val now = Instant.now()
        val today = ZonedDateTime.now(zone).toLocalDate().atStartOfDay(zone).toInstant()
        // Steps are the required baseline metric. A read error must fail the sync instead of
        // being uploaded as a fresh null snapshot.
        val steps = client.aggregate(
            AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(today, now))
        )[StepsRecord.COUNT_TOTAL]
        val stepsMeasuredAt = steps?.let {
            client.readRecords(
                ReadRecordsRequest(
                    StepsRecord::class,
                    TimeRangeFilter.between(today, now),
                    ascendingOrder = false,
                    pageSize = 1
                )
            ).records.firstOrNull()?.endTime
        }
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
        val latest = listOfNotNull(stepsMeasuredAt, heart?.time, oxygen?.time, sleep?.endTime).maxOrNull()
        return WearableSnapshot(
            deviceName, model, latest?.toString(),
            steps, stepsMeasuredAt?.toString(), heart?.beatsPerMinute, heart?.time?.toString(),
            sleepSnapshot, oxygen?.percentage?.value?.toDouble(), oxygen?.time?.toString()
        )
    }

    suspend fun readTrends(): HealthTrendsSnapshot {
        val now = Instant.now()
        val today = ZonedDateTime.now(zone).toLocalDate()
        val periodStart = today.minusDays(6)
        val dates = (0L..6L).map { periodStart.plusDays(it) }
        val rangeStart = periodStart.minusDays(1).atStartOfDay(zone).toInstant()

        val sleepRecords = runCatching {
            client.readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(rangeStart, now))
            ).records
        }.getOrNull()
        val sleepByDate = sleepRecords?.groupBy { it.endTime.atZone(zone).toLocalDate() }
        val sleepDaily = dates.map { date ->
            val records = sleepByDate?.get(date).orEmpty()
            val duration = records.takeIf { it.isNotEmpty() }?.sumOf {
                Duration.between(it.startTime, it.endTime).toMinutes()
            }
            val measuredAt = records.maxOfOrNull { it.endTime }
            DailySleepTrend(date.toString(), duration, measuredAt?.toString())
        }

        val restingRecords = runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    RestingHeartRateRecord::class,
                    TimeRangeFilter.between(periodStart.atStartOfDay(zone).toInstant(), now)
                )
            ).records
        }.getOrNull()
        val restingByDate = restingRecords?.groupBy { it.time.atZone(zone).toLocalDate() }
        val restingHeartRateDaily = dates.map { date ->
            val records = restingByDate?.get(date).orEmpty()
            val bpm = records.takeIf { it.isNotEmpty() }
                ?.map { it.beatsPerMinute.toDouble() }
                ?.average()
                ?.roundToLong()
            val measuredAt = records.maxOfOrNull { it.time }
            DailyRestingHeartRateTrend(date.toString(), bpm, measuredAt?.toString())
        }

        val stepsDaily = (2 downTo 0).map { today.minusDays(it.toLong()) }.map { date ->
            val start = date.atStartOfDay(zone).toInstant()
            val end = if (date == today) now else date.plusDays(1).atStartOfDay(zone).toInstant()
            val count = client.aggregate(
                AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, end))
            )[StepsRecord.COUNT_TOTAL]
            val measuredAt = count?.let {
                client.readRecords(
                    ReadRecordsRequest(
                        StepsRecord::class,
                        TimeRangeFilter.between(start, end),
                        ascendingOrder = false,
                        pageSize = 1
                    )
                ).records.firstOrNull()?.endTime
            }
            DailyStepsTrend(date.toString(), count, measuredAt?.toString())
        }

        val updatedAt = buildList {
            sleepDaily.mapNotNullTo(this) { it.measuredAt?.let(Instant::parse) }
            restingHeartRateDaily.mapNotNullTo(this) { it.measuredAt?.let(Instant::parse) }
            stepsDaily.mapNotNullTo(this) { it.measuredAt?.let(Instant::parse) }
        }.maxOrNull()
        return HealthTrendsSnapshot(
            periodStart.toString(), today.toString(), zone.id, updatedAt?.toString(),
            sleepDaily, restingHeartRateDaily, stepsDaily
        )
    }
}

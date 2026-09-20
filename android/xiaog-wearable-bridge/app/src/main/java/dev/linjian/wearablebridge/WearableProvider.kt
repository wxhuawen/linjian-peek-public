package dev.linjian.wearablebridge

/** Provider boundary: a future watch/band only needs another implementation. */
interface WearableProvider { suspend fun read(): WearableSnapshot }

data class WearableSnapshot(
    val deviceName: String?, val wearableModel: String?, val updatedAt: String?,
    val stepsToday: Long?, val stepsMeasuredAt: String?,
    val heartRateLatest: Long?, val heartRateMeasuredAt: String?,
    val sleepLastNight: SleepSnapshot?, val spo2Latest: Double?, val spo2MeasuredAt: String?
)

data class SleepSnapshot(val durationMinutes: Long?, val startAt: String?, val endAt: String?, val measuredAt: String?)

data class DailySleepTrend(val date: String, val durationMinutes: Long?, val measuredAt: String?)
data class DailyRestingHeartRateTrend(val date: String, val bpm: Long?, val measuredAt: String?)
data class DailyStepsTrend(val date: String, val count: Long?, val measuredAt: String?)

data class HealthTrendsSnapshot(
    val periodStart: String,
    val periodEnd: String,
    val timeZone: String,
    val updatedAt: String?,
    val sleepDaily: List<DailySleepTrend>,
    val restingHeartRateDaily: List<DailyRestingHeartRateTrend>,
    val stepsDaily: List<DailyStepsTrend>
)

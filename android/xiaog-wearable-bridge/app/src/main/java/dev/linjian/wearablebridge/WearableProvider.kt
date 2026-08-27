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

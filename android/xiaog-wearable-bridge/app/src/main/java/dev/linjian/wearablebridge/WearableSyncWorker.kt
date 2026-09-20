package dev.linjian.wearablebridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class WearableSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val config = BridgeConfig(applicationContext)
        if (config.endpoint.isBlank() || config.token.isBlank()) return Result.failure()
        val uploadUrl = (config.endpoint.trimEnd('/') + "/api/wearable/state").toHttpUrlOrNull() ?: return Result.failure()
        val historyUploadUrl = (config.endpoint.trimEnd('/') + "/api/wearable/history").toHttpUrlOrNull() ?: return Result.failure()
        if (!uploadUrl.isHttps || !historyUploadUrl.isHttps) return Result.failure()
        if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) return Result.failure()
        return try {
            val client = HealthConnectClient.getOrCreate(applicationContext)
            val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
            val granted = client.permissionController.getGrantedPermissions()
            if (stepsPermission !in granted) return Result.failure()
            val backgroundReadAvailable = client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            if (backgroundReadAvailable && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted) {
                return Result.failure()
            }
            val provider = HealthConnectProvider(applicationContext, config.deviceName, config.model)
            val snapshot = provider.read()
            val trends = provider.readTrends()
            val body = JSONObject().apply {
                put("device_id", "android-phone")
                put("device_name", snapshot.deviceName)
                put("wearable_model", snapshot.wearableModel)
                putNullable("updated_at", snapshot.updatedAt)
                putNullable("steps_today", snapshot.stepsToday)
                putNullable("steps_measured_at", snapshot.stepsMeasuredAt)
                putNullable("heart_rate_latest", snapshot.heartRateLatest)
                putNullable("heart_rate_measured_at", snapshot.heartRateMeasuredAt)
                putNullable("spo2_latest", snapshot.spo2Latest)
                putNullable("spo2_measured_at", snapshot.spo2MeasuredAt)
                put("sleep_last_night", snapshot.sleepLastNight?.let { sleep -> JSONObject().apply {
                    putNullable("duration_minutes", sleep.durationMinutes); putNullable("start_at", sleep.startAt)
                    putNullable("end_at", sleep.endAt); putNullable("measured_at", sleep.measuredAt)
                } } ?: JSONObject.NULL)
            }.toString()
            val trendsBody = JSONObject().apply {
                put("device_id", "android-phone")
                put("period_start", trends.periodStart)
                put("period_end", trends.periodEnd)
                put("timezone", trends.timeZone)
                putNullable("updated_at", trends.updatedAt)
                put("sleep_daily", JSONArray(trends.sleepDaily.map { day -> JSONObject().apply {
                    put("date", day.date); putNullable("duration_minutes", day.durationMinutes)
                    putNullable("measured_at", day.measuredAt)
                } }))
                put("resting_heart_rate_daily", JSONArray(trends.restingHeartRateDaily.map { day -> JSONObject().apply {
                    put("date", day.date); putNullable("bpm", day.bpm); putNullable("measured_at", day.measuredAt)
                } }))
                put("steps_daily", JSONArray(trends.stepsDaily.map { day -> JSONObject().apply {
                    put("date", day.date); putNullable("count", day.count); putNullable("measured_at", day.measuredAt)
                } }))
            }.toString()
            val stateCode = postJson(uploadUrl.toString(), config.token, body)
            if (stateCode !in 200..299) {
                return if (stateCode in 400..499) Result.failure() else Result.retry()
            }
            val historyCode = postJson(historyUploadUrl.toString(), config.token, trendsBody)
            if (historyCode in 200..299) Result.success()
            else if (historyCode in 400..499) Result.failure()
            else Result.retry()
        } catch (_: SecurityException) { Result.failure() }
        catch (_: IOException) { Result.retry() }
        catch (_: Exception) { Result.retry() }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }

    private fun postJson(url: String, token: String, body: String): Int {
        val request = Request.Builder().url(url)
            .header("X-Auth-Token", token)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { it.code }
    }

    companion object {
        private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    }
}

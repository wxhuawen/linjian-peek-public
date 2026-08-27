package dev.linjian.wearablebridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class WearableSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val config = BridgeConfig(applicationContext)
        if (config.endpoint.isBlank() || config.token.isBlank()) return Result.failure()
        val uploadUrl = (config.endpoint.trimEnd('/') + "/api/wearable/state").toHttpUrlOrNull() ?: return Result.failure()
        if (!uploadUrl.isHttps) return Result.failure()
        if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) return Result.failure()
        return try {
            val client = HealthConnectClient.getOrCreate(applicationContext)
            val required = setOf(
                HealthPermission.getReadPermission(StepsRecord::class), HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(SleepSessionRecord::class), HealthPermission.getReadPermission(OxygenSaturationRecord::class)
            )
            if (!client.permissionController.getGrantedPermissions().containsAll(required)) return Result.failure()
            val snapshot = HealthConnectProvider(applicationContext, config.deviceName, config.model).read()
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
            val request = Request.Builder().url(uploadUrl)
                .header("X-Auth-Token", config.token).post(body.toRequestBody("application/json".toMediaType())).build()
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success() else if (response.code in 400..499) Result.failure() else Result.retry()
            }
        } catch (_: SecurityException) { Result.failure() }
        catch (_: IOException) { Result.retry() }
        catch (_: Exception) { Result.retry() }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }

    companion object {
        private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    }
}

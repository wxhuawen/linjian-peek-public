package dev.linjian.wearablebridge

import android.os.Bundle
import android.net.Uri
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class SetupActivity : ComponentActivity() {
    private lateinit var statusView: TextView
    private val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
    private val dataPermissions = setOf(
        stepsPermission,
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class)
    )
    private val permissionLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (stepsPermission !in granted) {
            statusView.text = "未获得步数读取权限，不会启动同步。"
            return@registerForActivityResult
        }
        val backgroundReadAvailable = HealthConnectClient.getOrCreate(this).features.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
        ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        val backgroundGranted = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted
        if (backgroundReadAvailable && !backgroundGranted) {
            statusView.text = "已获得步数读取权限，但 WorkManager 还需后台读取权限，暂未启动同步。"
            return@registerForActivityResult
        }
        scheduleSync(backgroundGranted)
    }
    override fun onCreate(state: Bundle?) { super.onCreate(state); render() }

    private fun render() {
        val config = BridgeConfig(this)
        val endpoint = EditText(this).apply { hint = "掌心窗云端地址（如 https://peek.jqgzl.com）"; setText(config.endpoint) }
        val token = EditText(this).apply { hint = "掌心窗 Token"; setText(config.token); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val name = EditText(this).apply { hint = "设备名称"; setText(config.deviceName) }
        val model = EditText(this).apply { hint = "设备型号标识（可替换）"; setText(config.model) }
        statusView = TextView(this).apply { text = "仅用于首次配置权限与同步；本组件不展示日常健康数据。" }
        val button = Button(this).apply { text = "保存并授权 Health Connect" }
        button.setOnClickListener {
            val cleanEndpoint = endpoint.text.toString().trim().trimEnd('/')
            val parsed = Uri.parse(cleanEndpoint)
            if (parsed.scheme != "https" || parsed.host.isNullOrBlank()) {
                statusView.text = "云端地址必须是有效的 HTTPS 地址。"
                return@setOnClickListener
            }
            if (token.text.toString().isBlank()) {
                statusView.text = "Token 不能为空。"
                return@setOnClickListener
            }
            if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
                statusView.text = "当前手机尚未提供可用的 Health Connect，未启动同步。"
                return@setOnClickListener
            }
            config.endpoint = cleanEndpoint; config.token = token.text.toString(); config.deviceName = name.text.toString(); config.model = model.text.toString()
            val client = HealthConnectClient.getOrCreate(this)
            val requestedPermissions = if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
                dataPermissions + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
            } else dataPermissions
            permissionLauncher.launch(requestedPermissions)
        }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32); addView(statusView); addView(endpoint); addView(token); addView(name); addView(model); addView(button) })
    }

    private fun scheduleSync(backgroundGranted: Boolean) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val manager = WorkManager.getInstance(this)
        manager.enqueueUniqueWork("wearable-sync-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<WearableSyncWorker>().setConstraints(constraints).build())
        if (backgroundGranted) {
            manager.enqueueUniquePeriodicWork("wearable-sync", ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<WearableSyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
            statusView.text = "已授权，将立即同步并定期更新。"
        } else {
            statusView.text = "已授权数据读取并执行首次同步；未获得后台读取权限，不会定期同步。"
        }
    }
}

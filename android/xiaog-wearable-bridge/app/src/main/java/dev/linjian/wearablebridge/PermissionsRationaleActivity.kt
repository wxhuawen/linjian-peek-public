package dev.linjian.wearablebridge

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply {
            setPadding(32, 48, 32, 32)
            text = "本组件只读取你在 Health Connect 中明确授权的步数、心率、睡眠和血氧，并把最新标准化状态上传到你自己的掌心窗服务器。不会展示日常健康数据，不会向其他服务发送数据，也不会记录 Token 或原始健康日志。你可以随时在 Health Connect 中撤销权限。"
        })
    }
}

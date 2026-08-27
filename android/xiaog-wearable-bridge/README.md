# xiaog-wearable-bridge

独立于掌心窗主 App 的 Health Connect 采集桥。首次打开只配置云端地址、现有 Token、设备名称/型号并申请读取权限；之后由 WorkManager 定期上传标准化快照到 `/api/wearable/state`。组件不包含日常健康数据展示页。

未来更换手环时保持云端/MCP 契约不变：新增一个 `WearableProvider` 实现并在 Worker 中选择它；`wearable_model` 只作为标识，不把厂商数据库结构带入掌心窗。

本目录是独立 Gradle Android 工程，不加入 `android/app`，不会改动掌心窗原界面或原作者 Android 代码。安装 APK、蓝牙配对或手环重置需由用户明确执行，本项目不自动执行。

仅实现 Health Connect 数据源，不包含 Gadgetbridge 或双数据源 fallback。运行时如果 Health Connect 不可用、未授权或某项没有记录，该项保持 `null`。

构建（需要 JDK 17 与 Android SDK 35）：

```bash
./gradlew :app:assembleDebug
```

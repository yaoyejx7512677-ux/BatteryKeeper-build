# 电池管家 BatteryKeeper

面向 Android 14+、重点适配小米澎湃 OS 的本地电池监测应用。当前版本为 **v1.5.0（versionCode 9）**。

主要能力：

- 实时读取电量、电流、电压、温度，并估算电池侧功率。
- 保存充电会话、历史采样和日统计，展示今日、近 7 天、近 30 天趋势。
- 导入澎湃 OS Bug 报告 ZIP，或使用离线中文 OCR 识别报告截图。
- 展示健康度、满充容量与循环次数，并明确标注数据来源。
- 提供桌面小组件、充电时悬浮胶囊和 CSV 数据导出。

项目使用 Kotlin、Jetpack Compose、Room、WorkManager、Glance 和 ML Kit。完整构建方法见 [docs/BUILD.md](docs/BUILD.md)，当前状态见 [docs/PROJECT_STATE.md](docs/PROJECT_STATE.md)。

v1.5 源码包不附带正式签名 APK。源码包不包含签名私钥或密码；如需正式构建，请按 `keystore.properties.example` 配置你自己的 release keystore。



## v1.5 原生超级岛

v1.5 已移除 `SYSTEM_ALERT_WINDOW` 和旧 `PowerOverlayService`，改用 Xiaomi HyperOS 官方原生超级岛：`Notification + miui.focus.param`。充电开始时创建、充电中更新、拔电时取消；岛内不提供按钮，点击直接打开 BatteryKeeper 主界面。正式环境上岛仍需在小米澎湃 OS 开发者平台完成场景审核、设备白名单联调和正式权限验证，详见 `docs/V1.5_NATIVE_ISLAND.md`。

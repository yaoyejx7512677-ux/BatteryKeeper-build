# v1.5.0 构建验证

当前工作环境未预装 Android SDK / Gradle 8.13，且容器网络无法解析 `services.gradle.org`，因此本轮未执行完整 APK 构建。

已完成的静态检查：

- `versionCode = 9` / `versionName = 1.5.0`。
- `SYSTEM_ALERT_WINDOW` 已从 Manifest 移除。
- `PowerOverlayService.kt` 已删除，工程源码无旧悬浮岛运行时引用。
- `XiaomiIslandController` 与 `BatteryMonitorService` 已接线。
- 原生岛与前台监测通知使用不同 notification id。
- 原生岛没有 `miui.focus.actions`，点击使用 Notification `contentIntent` 打开 `MainActivity`。
- v1.4 Bug Report 导入热修保留。

正式发布前请在 Android Studio / CI 使用 Android SDK 36、Gradle Wrapper 8.13 和正式 release keystore 执行：

```bash
./gradlew clean test lint assembleRelease
```

随后在已加入小米超级岛设备白名单的 HyperOS 3 真机验证：充电创建、更新节流、拔电下岛、息屏显示、通知权限关闭、焦点通知权限关闭、点击进入主界面。

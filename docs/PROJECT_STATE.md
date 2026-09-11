# 项目状态

更新日期：2026-09-12

## 当前目标

为小米澎湃 OS 用户提供可信、低干扰的电池监测、充电记录和健康趋势工具。所有功率数据均按公开 Android API 读取的电池电流与电压估算，不宣称为充电器协商功率。

## 当前版本

- 应用版本：v1.5.0
- versionCode：8
- 包名：`com.batterykeeper.app`
- minSdk 34 / targetSdk 36 / compileSdk 36
- ABI：arm64-v8a
- 数据库：Room v2，与 v1.3.0 结构兼容

## 当前能力

- 自适应前台采样与常驻通知。
- 当前电量、电池侧功率、电流、电压、温度和充放电状态。
- 充电会话定期落盘，中断前尽量保存；功率均值按时间加权。
- 历史趋势按当天零点或近 7/30 天查询，采样空缺不外推。
- 循环次数优先使用 Android 14+ `EXTRA_CYCLE_COUNT`，否则使用报告值或安装后累计放电估算。
- 满充容量持久化，健康度优先使用检测报告。
- Bug 报告 ZIP 与截图 OCR 导入；识别结果经用户核对后保存，可删除。
- 响应式 Glance 小组件、充电时悬浮胶囊、分页 CSV 导出。
- 首页已重排，缺失数据使用“—”而非伪造的 0 或满健康值。

## 技术栈与模块

- Kotlin 2.0.21、Compose BOM 2024.12.01、Material 3。
- Room 2.6.1 + KSP 2.0.21-1.0.28。
- WorkManager 2.10.0、Glance 1.1.1、ML Kit 中文 OCR 16.0.1。
- `battery/`：采样、计算、状态、前台服务和悬浮窗。
- `data/`：Room 实体、DAO 和数据库。
- `report/`：Bug 报告与截图识别。
- `ui/`：Compose 页面、组件和 ViewModel。
- `worker/`：日统计补算和原始数据清理。
- `widget/`：桌面小组件。

## 构建与验证状态

- Release 构建成功。
- 6 项统计算法单元测试通过。
- Android Lint：0 error，45 warning。
- APK Signature Scheme v2 校验通过。
- 签名证书 SHA-256：`27ffa5d8179a8f8d0298b0d20c1d1bb895f1a158bd5f3fd3eca3af450dd1633c`。
- APK SHA-256：`b216d1b300e508638089ee6f609bc9fb9a6b91ee9402744b56fc77d320c1af4f`。
- 原生库经 16 KB ELF 段对齐和 APK zipalign 校验。
- 尚未在小米真机上完成连续运行测试。

## 重要文件

- `app/src/main/AndroidManifest.xml`：权限与系统组件。
- `app/src/main/java/com/batterykeeper/app/battery/BatteryMonitorService.kt`：监测主循环。
- `app/src/main/java/com/batterykeeper/app/battery/HistoryMath.kt`：历史区间统计。
- `app/src/main/java/com/batterykeeper/app/data/Entities.kt`：数据库结构。
- `app/src/test/`：算法回归测试。
- `release/BUILD_VERIFICATION.json`：交付 APK 的验证记录。

## 下一步

优先在小米 17 / 澎湃 OS 4 上测试后台存活、功率方向、悬浮窗位置、小组件刷新、报告导入和旧版覆盖升级。真机结果应写入 `KNOWN_ISSUES.md` 与 `SESSION_HANDOFF.md`。


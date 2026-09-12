# 项目状态

更新日期：2026-09-12

## 当前目标

为小米澎湃 OS 用户提供可信、低干扰的电池监测、充电记录和健康趋势工具。所有功率数据均按公开 Android API 读取的电池电流与电压估算，不宣称为充电器协商功率。

## 当前版本

- 应用版本：v1.5.4
- versionCode：13
- 包名：`com.batterykeeper.app`
- minSdk 34 / targetSdk 36 / compileSdk 36
- ABI：arm64-v8a
- 数据库：Room v2，本次不改表结构

## 当前能力

- 自适应前台采样与统一常驻通知 `#1`。
- 当前电量、电池侧功率、电流、电压、温度和充放电状态。
- 充电会话实时统计已充入 mAh、平均功率与峰值功率；v1.5.4 对 Xiaomi `CURRENT_NOW` 符号差异做了方向归一化。
- 历史趋势按当天零点或近 7/30 天查询，采样空缺不外推。
- 循环次数优先使用 Android 14+ `EXTRA_CYCLE_COUNT`，否则使用报告值或安装后累计放电估算。
- 满充容量持久化，健康度优先使用检测报告。
- Bug 报告 ZIP 支持 HyperOS 3/4 多来源解析；截图 OCR 导入继续保留。
- 响应式 Glance 小组件、分页 CSV 导出。
- Xiaomi 原生超级岛个人自用模式：摘要态温度/功率，通知与前台服务合并为一张；无官方 App ID 时不保证 SystemUI 视觉层长期常驻。

## v1.5.4 关键变化

- 超级岛常规刷新 10 秒；显著功率/温度变化可提前刷新。
- 仅统一通知 `#1` 真正丢失时自动恢复。
- 诊断页拆分“BatteryKeeper 应用侧”和“HyperOS / SystemUI”。
- Bugreport 字段优先级：AIDL HealthInfo > Health HAL > `POWER_SUPPLY_*` > batterystats。
- 报告结果显示解析格式、字段来源与置信度。

## 构建与验证状态

- 本地已完成源码静态语法检查；当前执行环境无法联网下载 Gradle 8.13，因此最终 APK 编译交由 GitHub Actions。
- v1.5.4 workflow 继续使用 v1.5.3 的固定签名 Repository Secrets，不引入新密钥。
- 现有 HyperOS 3/4 风格样本 Bugreport 回归：满充 6791 mAh、设计 7000 mAh、循环 316、估算 6941 mAh 可被新规则命中。

## 重要文件

- `app/src/main/java/com/batterykeeper/app/battery/BatteryMonitorService.kt`：监测主循环和充电会话。
- `app/src/main/java/com/batterykeeper/app/battery/XiaomiIslandController.kt`：原生超级岛、统一通知与诊断。
- `app/src/main/java/com/batterykeeper/app/report/ReportImport.kt`：HyperOS 3/4 Bugreport 与截图识别。
- `app/src/main/java/com/batterykeeper/app/ui/screens/ReportsScreen.kt`：报告导入与解析来源展示。
- `app/src/main/java/com/batterykeeper/app/ui/screens/SettingsScreen.kt`：超级岛分层诊断。
- `.github/workflows/build-apk-v1.5.3.yml`：文件名为历史兼容，内容已升级为 v1.5.4 构建，直接覆盖仓库现有 workflow，避免重复 Action。

## 下一步

通过 GitHub Actions 生成固定签名 v1.5.4 APK 后，重点真机验证：

- 充电 1 分钟后会话的 mAh / 平均功率 / 峰值功率是否非 0。
- 超级岛 10 秒节流与 SystemUI 收起时诊断是否符合预期。
- HyperOS 3 Bugreport 现有样本回归。
- 获取真实 HyperOS 4 Bugreport 后补充最终格式回归。

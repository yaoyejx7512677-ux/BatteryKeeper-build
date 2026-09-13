# 电池管家 BatteryKeeper

面向 Android 14+、重点适配小米澎湃 OS 的本地电池监测应用。当前源码版本为 **v1.5.5（versionCode 15，UI/报表增强正式版）**。

主要能力：

- 实时读取电量、电流、电压、温度，并估算电池侧功率。
- 保存充电会话、历史采样和日统计，展示今日、近 7 天、近 30 天趋势。
- 导入澎湃 OS Bug 报告 ZIP；v1.5.5 同时兼容 HyperOS 3/4 常见 Health HAL、AIDL HealthInfo、`POWER_SUPPLY_*` 与 batterystats 格式。
- 报告页支持系统相册图片导入；可识别小米电池健康历史列表截图并批量导入多条记录。
- “自动查找最新报告”首次授权 MIUI/debug_log/错误报告目录后，后续一键扫描最新 ZIP。
- 使用离线中文 OCR 识别检测报告截图。
- 展示健康度、满充容量与循环次数，并标注报告解析格式、字段来源和置信度。
- 提供桌面小组件、分页 CSV 导出，以及 Xiaomi HyperOS 原生超级岛个人自用模式。

项目使用 Kotlin、Jetpack Compose、Room、WorkManager、Glance 和 ML Kit。完整构建方法见 `docs/BUILD.md`，当前状态见 `docs/PROJECT_STATE.md`。

## v1.5.5 超级岛策略

旧 `SYSTEM_ALERT_WINDOW` 仿岛已移除。当前使用 `Notification + miui.focus.param`，并与前台监测服务共用统一通知 `#1`，避免通知栏出现两张 BatteryKeeper 卡片。

v1.5.5 将常规岛刷新降至约 10 秒，只有功率变化 ≥ 3W 或温度变化 ≥ 1℃ 时允许提前刷新；只在统一通知 `#1` 真正消失时自动恢复，不再因为焦点 extras 瞬时不可见而高频补发。

未配置小米开发者平台 App ID 时属于个人自用模式：应用可以投递原生焦点通知参数，但 HyperOS SystemUI 仍可能根据权限/策略自动收起摄像头区域的岛，应用无法强制长期常驻。

## 固定签名

v1.5.3 起 GitHub Actions 使用 Repository Secrets 恢复同一张永久 keystore。源码包不包含私钥或密码；继续构建 v1.5.5 时请沿用现有四个 `BATTERYKEEPER_*` Actions Secrets，以保证后续版本可正常覆盖升级。


## v1.6.0

- 4 个纯图标底部导航，设置入口迁移到报表页右上角弹窗。
- 首页和曲线页压缩为一屏核心信息。
- 完整数据备份/恢复，支持系统云盘/文件提供商与邮件分享至 Ben0102@qq.com。
- 充电会话使用 CURRENT_NOW 积分 + CHARGE_COUNTER 增量兜底；断电 30 秒宽限、5 分钟内服务重启续接会话，减少 0mAh 碎片。
- 正式 Release 构建、固定签名、R8/资源压缩、arm64-v8a。

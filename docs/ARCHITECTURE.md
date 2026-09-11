# 架构说明

## 数据流

`BatteryMonitorService` 定期调用 `BatterySampler`，将有效快照写入 `BatteryStateHolder` 供前台 UI 即时展示，并按节流策略写入 Room。充电会话在开始后建立，过程中周期更新同一条数据库记录；拔电或服务停止时保存最终状态。

历史页面从 Room 查询完整区间，在后台线程计算统计，再对显示数据按窗口保留边界、功率极值和温度极值。统计函数忽略超过两分钟的采样空缺，避免把休眠或服务停止期间的数据外推。

## 核心模块

- `BatterySampler`：公开 Android 电池广播和 `BatteryManager` 属性读取。
- `MeasurementMath`：时长、积分、时间坐标和区间重叠纯函数。
- `HistoryMath`：按真实观测时长计算充入量、放出量、平均温度和放电功率。
- `BatteryMonitorService`：采样调度、会话、持久化、通知和小组件节流。
- `BatteryStateHolder`：进程内实时 `StateFlow`，只保存当前状态和近十分钟功率点。
- `BatteryDatabase`：原始采样、循环快照、充电会话、日统计和健康报告。
- `DailyWorker`：补算缺失日统计并清理过期原始采样。
- `ReportImport`：ZIP 文本字段解析和 ML Kit 离线中文 OCR。
- `BatteryViewModel`：组合实时状态、数据库 Flow 和设置，向 Compose 页面提供数据。

## 数据库

Room 数据库版本为 2。表包括 `samples`、`cycle_records`、`charge_sessions`、`daily_stats`、`health_reports`。v1 → v2 迁移只新增 `health_reports`；v1.4.0 未改变数据库结构。

## UI 与系统组件

单 Activity + Compose Navigation，包含概览、功率、趋势、报表和设置五个 Tab。Glance 小组件独立读取当前系统快照，避免进程重启后只能显示空状态。悬浮胶囊使用 `TYPE_APPLICATION_OVERLAY`，仅充电时显示。

## 网络层

应用运行时无网络层。OCR 模型打包在 APK 中，报告解析和数据存储均在本机完成。



## v1.5 原生超级岛

`BatteryMonitorService` 在获得每次电池快照后，将充电状态交给 `XiaomiIslandController`。控制器仅负责 Xiaomi HyperOS 原生岛通知的能力判断、JSON 构建、节流更新和取消，不再使用窗口覆盖层。普通前台监测通知与超级岛使用不同 notification id，因此两者生命周期互不绑定。

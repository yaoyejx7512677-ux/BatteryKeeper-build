# 接口与数据约定

## 外部网络 API

当前没有外部网络 API。应用运行时不上传电池数据或截图。

## Android 数据源

- `ACTION_BATTERY_CHANGED`：电量、状态、供电类型、电压、温度、健康状态和 Android 14+ 循环次数。
- `BATTERY_PROPERTY_CURRENT_NOW`：瞬时电池电流，公开单位为 μA。
- `BATTERY_PROPERTY_CHARGE_COUNTER`：当前剩余电量，公开单位为 μAh；设备可能不支持。

未知整数通常使用 `-1`，未知浮点读数使用 `NaN`，UI 统一显示“—”。功率正值表示净电流流入电池，负值表示净放电。

## 关键内部结构

- `BatterySnapshot`：一次实时电池快照。
- `Sample`：写入 Room 的有效历史采样。
- `ChargeSession`：一次供电连接期间的累计记录。
- `HealthReport`：Bug 报告或截图识别得到的健康数据。
- `HistoryMath.Summary`：区间内按时长计算的充入、放出、温度和放电功率摘要。

## 权限

- `POST_NOTIFICATIONS`：显示监测前台服务通知。
- `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`：持续采样。
- `RECEIVE_BOOT_COMPLETED`：开机后按用户设置恢复监测。
- `WAKE_LOCK`：WorkManager 和前台任务依赖。

## 文件接口

- 导入：系统文件选择器提供的 Bug 报告 ZIP 或图片 URI。
- 导出：FileProvider 分享 UTF-8 CSV。
- 导入错误通过异常消息反馈；识别结果必须在预览对话框确认后写入数据库。



## Xiaomi HyperOS 原生超级岛

- `Settings.System[notification_focus_protocol]`：查询焦点通知/超级岛协议版本。
- `persist.sys.feature.island`：按小米官方文档通过反射查询岛能力。
- `content://miui.statusbar.notification.public` / `canShowFocus`：查询当前应用焦点通知权限。
- `Notification.extras["miui.focus.param"]`：提交原生超级岛 JSON 参数。
- `Notification.extras["miui.focus.pics"]`：提交岛模板使用的本地图标。

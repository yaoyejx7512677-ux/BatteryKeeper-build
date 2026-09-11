# Keep Room 实体（kapt 生成代码引用）
-keep class com.batterykeeper.app.data.** { *; }

# Keep 电池引擎与入口类（含反射访问的场景）
-keep class com.batterykeeper.app.battery.** { *; }
-keep class com.batterykeeper.app.App { *; }
-keep class com.batterykeeper.app.MainActivity { *; }

# Keep 桌面小组件（Glance 依赖 Compose 运行时反射，R8 裁剪会导致"无法显示内容"）
-keep class com.batterykeeper.app.widget.** { *; }
-keep class androidx.glance.** { *; }
-dontwarn androidx.glance.**

# Kotlin coroutines / Room 官方规则由 AAR 自带
-dontwarn androidx.room.paging.**

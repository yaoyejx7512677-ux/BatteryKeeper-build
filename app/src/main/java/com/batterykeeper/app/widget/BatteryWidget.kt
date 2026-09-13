package com.batterykeeper.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.compose.ui.unit.DpSize
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.LocalSize
import androidx.glance.appwidget.provideContent
import androidx.glance.unit.ColorProvider
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.batterykeeper.app.battery.BatteryStateHolder
import kotlin.math.abs
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity
import com.batterykeeper.app.MainActivity
import com.batterykeeper.app.battery.display
import com.batterykeeper.app.battery.BatterySampler

/**
 * 电池管家桌面小组件（响应式单小组件，1x1 / 1x2 / 2x2 自适应）：
 *  - 1x1：图标 + 电量%（紧凑）
 *  - 1x2（宽条）：闪电图标 | 功率 | 电量%
 *  - 2x2：大号功率 + 电量% + 状态/温度明细
 */
class BatteryWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(70.dp, 70.dp),     // 1x1
            DpSize(160.dp, 70.dp),    // 1x2
            DpSize(160.dp, 160.dp),   // 2x2
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = runCatching { BatterySampler.sample(context) }.getOrNull()
        provideContent { WidgetContent(snapshot) }
    }

    @Composable
    private fun WidgetContent(snap: com.batterykeeper.app.battery.BatterySnapshot?) {
        GlanceTheme {
            Box(
                GlanceModifier
                    .fillMaxSize()
                    .clickable(actionStartActivity<MainActivity>())
                    .cornerRadius(18.dp)
                    .background(Color(0xFF101626))
                    .padding(10.dp),
            ) {
                if (snap == null) {
                    Text(
                        "电池管家\n打开App后生效",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF8A94A6)),
                            fontSize = 11.sp,
                        ),
                        modifier = GlanceModifier.padding(4.dp),
                    )
                } else {
                    ResponsiveContent(snap)
                }
            }
        }
    }

    // ---------- 响应式内容 ----------

    @Composable
    private fun ResponsiveContent(snap: com.batterykeeper.app.battery.BatterySnapshot) {
        // 关键布局判断：宽高比决定形态
        Box(GlanceModifier.fillMaxSize()) {
            // 用两个候选布局按可用空间选择：Glance Responsive 需用 LocalSize
            val size = androidx.glance.LocalSize.current
            val w = size.width.value
            val h = size.height.value
            when {
                w < 100 && h < 100 -> Content1x1(snap)                       // 1x1 方块
                w >= 100 && h < 100 -> Content1x2(snap)                      // 宽条
                else -> Content2x2(snap)                                     // 大方块
            }
        }
    }

    private val green = ColorProvider(Color(0xFF4ADE80))
    private val white = ColorProvider(Color(0xFFF5F7FA))
    private val gray = ColorProvider(Color(0xFF8A94A6))

    /** 1x1：居中图标 + 电量 */
    @Composable
    private fun Content1x1(snap: com.batterykeeper.app.battery.BatterySnapshot) {
        Column(
            GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (snap.isCharging) "⚡" else "▼",
                style = TextStyle(color = green, fontSize = 22.sp),
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                "${snap.level}%",
                style = TextStyle(color = white, fontSize = 16.sp, fontWeight = FontWeight.Bold),
            )
        }
    }

    /** 1x2 宽条：图标 | 功率 | 电量 */
    @Composable
    private fun Content1x2(snap: com.batterykeeper.app.battery.BatterySnapshot) {
        Row(
            GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (snap.isCharging) "⚡" else "▼",
                style = TextStyle(color = green, fontSize = 20.sp),
            )
            Spacer(GlanceModifier.width(8.dp))
            Column {
                Text(
                    "${snap.powerW.display(2)}W",
                    style = TextStyle(
                        color = if (snap.isCharging) green else white,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    if (snap.isCharging) "充电中" else "放电中",
                    style = TextStyle(color = gray, fontSize = 10.sp),
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            Text(
                "${snap.level}%",
                style = TextStyle(color = white, fontSize = 22.sp, fontWeight = FontWeight.Bold),
            )
        }
    }

    /** 2x2：大号功率 + 电量 + 明细 */
    @Composable
    private fun Content2x2(snap: com.batterykeeper.app.battery.BatterySnapshot) {
        Column(
            GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (snap.isCharging) "⚡ 充电中" else "▼ 放电中",
                style = TextStyle(color = green, fontSize = 13.sp),
            )
            Spacer(GlanceModifier.height(4.dp))
            Text(
                "${snap.powerW.display(2)} W",
                style = TextStyle(
                    color = if (snap.isCharging) green else white,
                    fontSize = 30.sp, fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                "${snap.level}%",
                style = TextStyle(color = white, fontSize = 24.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.height(4.dp))
            Text(
                "电池侧 · ${snap.tempC.display()}℃",
                style = TextStyle(color = gray, fontSize = 11.sp),
            )
        }
    }
}

class BatteryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BatteryWidget()
}

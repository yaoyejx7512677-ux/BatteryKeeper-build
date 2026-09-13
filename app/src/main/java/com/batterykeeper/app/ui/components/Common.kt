package com.batterykeeper.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batterykeeper.app.ui.theme.CardBg
import com.batterykeeper.app.ui.theme.CardBorder
import com.batterykeeper.app.ui.theme.TxtSecondary
import com.batterykeeper.app.ui.theme.TxtTertiary

/** 液态玻璃风卡片（Column 布局：内容自上而下排列，不重叠） */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardBg)
            .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
            .padding(contentPadding),
    ) { content() }
}

/** 卡片标题行 + 右侧徽标 */
@Composable
fun CardTitle(title: String, badge: String? = null, badgeColor: Color = Color(0x26FF8A3D)) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 14.sp, color = TxtSecondary, fontWeight = FontWeight.Medium)
        if (badge != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(badgeColor)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(badge, fontSize = 12.sp, color = Color(0xFFFF8A3D), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 小型指标块 */
@Composable
fun MetricCompact(value: String, unit: String, label: String, valueColor: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = valueColor)
            Text(" $unit", fontSize = 12.sp, color = TxtSecondary)
        }
        Text(label, fontSize = 12.sp, color = TxtTertiary, modifier = Modifier.padding(top = 3.dp))
    }
}

/** 进度条（协商/实际功率对比等） */
@Composable
fun PowerBar(label: String, valueText: String, fraction: Float, accent: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 10.5.sp, color = TxtTertiary, modifier = Modifier.width(46.dp))
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Color(0x14FFFFFF)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        if (accent) Brush.horizontalGradient(listOf(Color(0xFFFFB066), Color(0xFFFF8A3D)))
                        else Brush.horizontalGradient(listOf(Color(0xFF6B7280), Color(0xFF9CA3AF)))
                    ),
            )
        }
        Text(
            valueText, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
            color = if (accent) Color(0xFFFF8A3D) else TxtSecondary,
            modifier = Modifier.padding(start = 8.dp).width(52.dp),
        )
    }
}

/** 分段选择器 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x0FFFFFFF))
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, opt ->
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (i == selected) Color(0x33FF8A3D) else Color.Transparent)
                    .padding(vertical = 8.dp)
                    .clickableNoRipple { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    opt, fontSize = 12.5.sp,
                    color = if (i == selected) Color(0xFFFF8A3D) else TxtSecondary,
                    fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 状态点 + 文本 */
@Composable
fun StatusDot(text: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(if (active) Color(0xFFFF8A3D) else Color(0xFF4ADE80)),
        )
        Text(
            " $text", fontSize = 11.sp, color = TxtSecondary,
        )
    }
}

/** 无涟漪点击 */
@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    this.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

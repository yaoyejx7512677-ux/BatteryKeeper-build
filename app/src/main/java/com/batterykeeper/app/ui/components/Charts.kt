package com.batterykeeper.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 平滑贝塞尔曲线 */
private fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        val p0 = points[i - 1]
        val p1 = points[i]
        val mx = (p0.x + p1.x) / 2
        path.cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
    }
    return path
}

/** 健康度环形仪表 */
@Composable
fun HealthRing(percent: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 24f
        val inset = stroke / 2 + 6f
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = Color(0x14FFFFFF),
            startAngle = -90f, sweepAngle = 360f, useCenter = false,
            topLeft = topLeft, size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawArc(
            brush = Brush.linearGradient(listOf(Color(0xFFFFB066), Color(0xFFFF8A3D))),
            startAngle = -90f,
            sweepAngle = 360f * percent.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = topLeft, size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * 通用双曲线图：主曲线（面积+线）+ 次曲线（细线）。
 * data: (tMs, mainValue, secondaryValue)
 */
@Composable
fun DualLineChart(
    data: List<Triple<Long, Float, Float>>,
    mainColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier,
    mainMax: Float = 100f,
    mainMin: Float = 0f,
    secMax: Float? = null,
    secMin: Float? = null,
    gridLines: List<Float> = emptyList(),
    gridLabels: List<String> = emptyList(),
    highlightCharging: Boolean = false,
) {
    Canvas(modifier.fillMaxWidth()) {
        if (data.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val tMin = data.first().first
        val tMax = data.last().first.coerceAtLeast(tMin + 1)

        // 充电段高亮背景
        if (highlightCharging) {
            var chargeStart: Long? = null
            data.forEachIndexed { i, (t, _, sec) ->
                if (sec > 0 && chargeStart == null) chargeStart = t
                if ((sec <= 0 || i == data.size - 1) && chargeStart != null) {
                    val x0 = com.batterykeeper.app.battery.MeasurementMath.xFraction(chargeStart!!, tMin, tMax) * w
                    val x1 = com.batterykeeper.app.battery.MeasurementMath.xFraction(t, tMin, tMax) * w
                    drawRect(Color(0x14FF8A3D), topLeft = Offset(x0, 0f), size = Size(x1 - x0, h))
                    chargeStart = null
                }
            }
        }

        // 网格
        val paint = android.graphics.Paint().apply {
            color = 0xFF5B6472.toInt()
            textSize = 20f
            isAntiAlias = true
        }
        gridLines.forEachIndexed { gi, v ->
            val y = h - (v - mainMin) / (mainMax - mainMin).coerceAtLeast(0.001f) * h
            drawLine(Color(0x0FFFFFFF), Offset(0f, y), Offset(w, y), 2f)
            if (gi < gridLabels.size) {
                drawContext.canvas.nativeCanvas.drawText("${v.toInt()}", 4f, y - 6f, paint)
            }
        }

        fun mapY(v: Float, minV: Float, maxV: Float): Float =
            h - (v - minV) / (maxV - minV).coerceAtLeast(0.001f) * h

        // 主曲线（面积 + 线）
        val mainPts = data.map { (t, v, _) ->
            Offset(com.batterykeeper.app.battery.MeasurementMath.xFraction(t, tMin, tMax) * w, mapY(v, mainMin, mainMax))
        }
        val mainPath = smoothPath(mainPts)
        val areaPath = Path().apply {
            addPath(mainPath)
            lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(areaPath, mainColor.copy(alpha = 0.14f))
        drawPath(mainPath, mainColor, style = Stroke(5f, cap = StrokeCap.Round))

        // 次曲线
        val sMax = secMax ?: data.maxOf { it.third }.coerceAtLeast(1f)
        val sMin = secMin ?: min(0f, data.minOf { it.third })
        val secPts = data.map { (t, _, v) ->
            Offset(com.batterykeeper.app.battery.MeasurementMath.xFraction(t, tMin, tMax) * w, mapY(v, sMin, sMax))
        }
        drawPath(smoothPath(secPts), secondaryColor, style = Stroke(3.5f, cap = StrokeCap.Round))
    }
}

/** 单曲线滚动图（实时功率） */
@Composable
fun LiveLineChart(
    points: List<Pair<Long, Float>>,
    color: Color,
    modifier: Modifier = Modifier,
    maxV: Float = 80f,
    minV: Float = 0f,
) {
    Canvas(modifier.fillMaxWidth()) {
        if (points.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        // 网格
        listOf(0.33f, 0.66f).forEach { f ->
            val y = h * f
            drawLine(Color(0x0FFFFFFF), Offset(0f, y), Offset(w, y), 2f)
        }
        val tMin = points.first().first
        val tMax = points.last().first.coerceAtLeast(tMin + 1)
        val pts = points.map { (t, v) ->
            Offset(
                com.batterykeeper.app.battery.MeasurementMath.xFraction(t, tMin, tMax) * w,
                h - (v.coerceIn(minV, maxV) - minV) / (maxV - minV) * h,
            )
        }
        val path = Path().apply {
            pts.forEachIndexed { index, point ->
                if(index==0 || points[index].first-points[index-1].first>120000) moveTo(point.x,point.y)
                else lineTo(point.x,point.y)
            }
        }
        val area = Path().apply { addPath(path); lineTo(w, h); lineTo(0f, h); close() }
        drawPath(area, color.copy(alpha = 0.12f))
        drawPath(path, color, style = Stroke(5f, cap = StrokeCap.Round))
        // 端点光晕
        val last = pts.last()
        drawCircle(color.copy(alpha = 0.3f), radius = 18f, center = last)
        drawCircle(color, radius = 8f, center = last)
    }
}

/** 柱状图（月度循环增量） */
@Composable
fun BarChart(
    values: List<Int>,
    labels: List<String>,
    accentIndex: Int = values.size - 1,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.fillMaxWidth()) {
        if (values.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val maxV = max(values.maxOrNull() ?: 1, 1)
        val labelSpace = 34f
        val bw = min(w / values.size * 0.55f, 46f)
        val gap = (w - bw * values.size) / (values.size + 1)
        val paint = android.graphics.Paint().apply {
            color = 0xFF5B6472.toInt()
            textSize = 22f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        values.forEachIndexed { i, v ->
            val x = gap + i * (bw + gap)
            val bh = v / maxV.toFloat() * (h - labelSpace - 10f)
            val top = h - labelSpace - bh
            drawRoundRect(
                color = if (i == accentIndex) Color(0xFFFF8A3D) else Color(0x59FF8A3D),
                topLeft = Offset(x, top),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(12f, 12f),
            )
            drawContext.canvas.nativeCanvas.drawText(labels.getOrElse(i) { "" }, x + bw / 2, h - 8f, paint)
        }
    }
}

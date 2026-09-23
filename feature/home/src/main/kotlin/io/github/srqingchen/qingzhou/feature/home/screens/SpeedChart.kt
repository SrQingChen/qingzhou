package io.github.srqingchen.qingzhou.feature.home.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.srqingchen.qingzhou.core.designsystem.theme.QzAccent
import io.github.srqingchen.qingzhou.core.model.formatSpeed

/**
 * 速率折线图：传输过程的实时速率波动。
 * x = 时间（首尾自适应），y = 速率（0 至峰值，网格 + 渐变填充）。
 */
@Composable
fun SpeedChart(series: List<Pair<Long, Long>>, modifier: Modifier = Modifier, durationMs: Long = -1) {
    val gridColor = Color(0x2E00838F)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth()) {
        if (series.size < 2) {
            Text(
                "采样点不足（传输太快或刚启动）",
                style = MaterialTheme.typography.bodySmall,
                color = labelColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }
        val peak = series.maxOf { it.second }.coerceAtLeast(1)
        val t0 = series.first().first
        val t1 = series.last().first
        // 小文件两点重建曲线时，采样跨度为 0 —— 用任务真实历时（优先）兜底
        val span = if (durationMs > 0 && t1 - t0 <= 0) durationMs else (t1 - t0).coerceAtLeast(1)

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp),
        ) {
            val w = size.width
            val h = size.height
            val padL = 8f
            val padB = 12f
            val chartW = w - padL
            val chartH = h - padB

            fun px(t: Long) = padL + ((t - t0).toFloat() / span) * chartW
            fun py(v: Long) = chartH - (v.toFloat() / peak) * chartH

            // 水平网格（4 条）
            for (i in 1..4) {
                val y = chartH * i / 4
                drawLine(
                    gridColor,
                    Offset(padL, y),
                    Offset(w, y),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)),
                )
            }

            // 峰值参考线
            drawLine(gridColor, Offset(padL, 0f), Offset(w, 0f), 1.5f)

            // 填充
            val fill = Path().apply {
                moveTo(px(series.first().first), chartH)
                series.forEach { (t, v) -> lineTo(px(t), py(v)) }
                lineTo(px(series.last().first), chartH)
                close()
            }
            drawPath(fill, QzAccent.copy(alpha = 0.18f))

            // 折线
            val line = Path().apply {
                moveTo(px(series.first().first), py(series.first().second))
                series.forEach { (t, v) -> lineTo(px(t), py(v)) }
            }
            drawPath(line, QzAccent, style = Stroke(width = 3.5f))
        }
        Text(
            "峰值 ${formatSpeed(peak)} · 采样 ${series.size} 点 · 历时 ${span / 1000}s",
            style = MaterialTheme.typography.bodySmall,
            color = labelColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

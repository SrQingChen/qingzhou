package io.github.srqingchen.qingzhou.core.designsystem.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 琉璃质感设计组件：渐变底 + 柔光斑背景，半透明玻璃卡。
 *
 * 立体感来自四层：渐变描边（受光面亮、背光面暗）、顶部高光内衬、
 * 半透明层叠、投影。不依赖 backdrop blur —— 在低端机与悬浮场景下
 * 帧率友好，且 API 26 起行为一致。
 */
@Composable
fun QzBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    val top = if (dark) Color(0xFF0D1B20) else Color(0xFFEDF7FA)
    val bottom = if (dark) Color(0xFF060E11) else Color(0xFFDCEEF3)
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom))),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val primary = if (dark) Color(0x4000838F) else Color(0x2E00838F)
            val secondary = if (dark) Color(0x332E9AA8) else Color(0x242E9AA8)
            drawCircle(
                brush = Brush.radialGradient(listOf(primary, Color.Transparent)),
                center = Offset(size.width * 0.85f, size.height * 0.10f),
                radius = size.minDimension * 0.9f,
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(secondary, Color.Transparent)),
                center = Offset(size.width * 0.10f, size.height * 0.92f),
                radius = size.minDimension * 0.8f,
            )
        }
        content()
    }
}

/**
 * 半透明玻璃卡：渐变描边 + 顶部高光内衬 + 柔影。
 * 内容为已带 18dp 内边距与 8dp 间距的 Column。
 */
@Composable
fun QzGlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(26.dp)
    val fill = if (dark) Color(0xCC10201F) else Color(0xE6FFFFFF)
    val borderBrush = if (dark) {
        Brush.linearGradient(listOf(Color(0x2EFFFFFF), Color(0x0FFFFFFF), Color(0x2EFFFFFF)))
    } else {
        Brush.linearGradient(listOf(Color(0xB3FFFFFF), Color(0x47FFFFFF), Color(0xB3FFFFFF)))
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, borderBrush, shape),
    ) {
        // 顶部受光高光内衬（玻璃上缘反光）
        val sheen = if (dark) Color(0x14FFFFFF) else Color(0x66FFFFFF)
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(sheen, Color.Transparent)),
                    RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                )
                .padding(top = 0.dp),
        )
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

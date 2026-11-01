package com.ddw.kuangren.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ddw.kuangren.ui.theme.Holo
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ---------------- 背景 ----------------

@Composable
fun HoloBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Holo.Background,
                        Color(0xFF070B14),
                        Color(0xFF0A1120),
                        Holo.Background
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 40.dp.toPx()
            val lineColor = Holo.Primary.copy(alpha = 0.04f)
            var x = 0f
            while (x < size.width) {
                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
            drawLine(
                color = Holo.Primary.copy(alpha = 0.10f),
                start = Offset(0f, 1f),
                end = Offset(size.width, 1f),
                strokeWidth = 2f
            )
        }
        content()
    }
}

// ---------------- 卡片 ----------------

@Composable
fun HoloCard(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val borderColor = if (active) Holo.Primary.copy(alpha = 0.7f) else Holo.Primary.copy(alpha = 0.25f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Holo.Surface, CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
            .border(1.dp, borderColor, CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
            .padding(padding),
        content = content
    )
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(12.dp)
                .background(Holo.Primary)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            color = Holo.Text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (action != null && onAction != null) {
            Text(
                text = action,
                color = Holo.Primary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.clickable { onAction() }
            )
        }
    }
}

@Composable
fun MonoText(
    text: String,
    color: Color = Holo.Text,
    size: Int = 13,
    bold: Boolean = false,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = size.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
fun MutedText(text: String, size: Int = 11, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Text(
        text = text,
        color = Holo.TextMuted,
        fontSize = size.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

// ---------------- 进度条 ----------------

@Composable
fun HoloProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    color: Color = Holo.Primary,
    trackColor: Color = Holo.SurfaceAlt,
    segments: Int = 10
) {
    val p = progress.coerceIn(0f, 1f)
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val gap = 3.dp.toPx()
        val segWidth = (size.width - gap * (segments - 1)) / segments
        if (segWidth <= 0f) return@Canvas
        for (i in 0 until segments) {
            val left = i * (segWidth + gap)
            val threshold = (i + 1).toFloat() / segments
            val filled = p >= threshold - 0.0001f
            drawRect(
                color = if (filled) color else trackColor,
                topLeft = Offset(left, 0f),
                size = Size(segWidth, size.height)
            )
        }
    }
}

// ---------------- 圆形等级环 ----------------

@Composable
fun LevelRing(level: Int, progress: Float, size: Dp = 96.dp) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2f
            drawArc(
                color = Holo.Border,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Butt)
            )
            val sweep = 360f * progress.coerceIn(0f, 1f)
            if (sweep > 0.5f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Holo.Primary, Holo.Secondary, Holo.Primary)),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MonoText(text = "LV." + level, color = Holo.Primary, size = 18, bold = true)
            MonoText(text = (progress * 100).toInt().toString() + "%", color = Holo.TextMuted, size = 9)
        }
    }
}

@Composable
fun QuotaRing(remaining: Int, total: Int, locked: Boolean, size: Dp = 118.dp) {
    val fraction = if (total <= 0) 0f else (remaining.toFloat() / total).coerceIn(0f, 1f)
    val arcColor = when {
        locked -> Holo.Danger
        fraction <= 0.15f -> Holo.Warning
        else -> Holo.Primary
    }
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2f
            drawArc(
                color = Holo.Border,
                startAngle = 90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke)
            )
            val sweep = 360f * fraction
            if (sweep > 0.5f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(arcColor, Holo.Secondary, arcColor)),
                    startAngle = 90f,
                    sweepAngle = -sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MonoText(text = remaining.toString(), color = arcColor, size = 26, bold = true)
            MonoText(text = "MIN", color = Holo.TextMuted, size = 9)
            MutedText(text = "可用/总计 " + remaining + "/" + total, size = 8, maxLines = 1)
        }
    }
}

// ---------------- 五维雷达 ----------------

@Composable
fun RadarChart(values: List<Int>, labels: List<String>, modifier: Modifier = Modifier) {
    val count = 5
    val safeValues = if (values.size >= count) values else listOf(0, 0, 0, 0, 0)
    Box(modifier = modifier.fillMaxWidth().height(190.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f + 4.dp.toPx()
            val radius = min(size.width, size.height) / 2f - 30.dp.toPx()
            fun point(index: Int, factor: Float): Offset {
                val angle = (-90f + index * 72f) * Math.PI.toFloat() / 180f
                return Offset(cx + radius * factor * cos(angle), cy + radius * factor * sin(angle))
            }
            for (ring in 1..4) {
                val factor = ring / 4f
                val path = Path()
                for (i in 0 until count) {
                    val p = point(i, factor)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(
                    path = path,
                    color = Holo.Primary.copy(alpha = 0.10f),
                    style = Stroke(width = 1f)
                )
            }
            for (i in 0 until count) {
                drawLine(
                    color = Holo.Primary.copy(alpha = 0.12f),
                    start = Offset(cx, cy),
                    end = point(i, 1f),
                    strokeWidth = 1f
                )
            }
            val shape = Path()
            for (i in 0 until count) {
                val factor = (safeValues[i].coerceIn(0, 100)) / 100f
                val p = point(i, factor)
                if (i == 0) shape.moveTo(p.x, p.y) else shape.lineTo(p.x, p.y)
            }
            shape.close()
            drawPath(
                path = shape,
                brush = Brush.linearGradient(listOf(Holo.Primary.copy(alpha = 0.35f), Holo.Secondary.copy(alpha = 0.25f)))
            )
            drawPath(
                path = shape,
                color = Holo.Primary,
                style = Stroke(width = 2.dp.toPx())
            )
            for (i in 0 until count) {
                val factor = (safeValues[i].coerceIn(0, 100)) / 100f
                drawCircle(color = Holo.Primary, radius = 3.dp.toPx(), center = point(i, factor))
            }
        }
        val labelPositions = listOf(
            Alignment.TopCenter,
            Alignment.CenterEnd,
            Alignment.BottomEnd,
            Alignment.BottomStart,
            Alignment.CenterStart
        )
        labels.take(count).forEachIndexed { index, label ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = labelPositions[index]) {
                Text(
                    text = label,
                    color = Holo.TextMuted,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ---------------- 体重折线 ----------------

@Composable
fun WeightChart(
    points: List<Double>,
    labels: List<String> = emptyList(),
    targetKg: Double = 85.0,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth().height(180.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (points.isEmpty()) return@Canvas
            val left = 4.dp.toPx()
            val right = size.width - 4.dp.toPx()
            val top = 10.dp.toPx()
            val bottom = size.height - 22.dp.toPx()
            val minV = min(points.minOrNull() ?: 0.0, targetKg) - 1.0
            val maxV = (points.maxOrNull() ?: 1.0) + 1.0
            val span = (maxV - minV).coerceAtLeast(0.1)
            fun x(i: Int): Float =
                if (points.size <= 1) (left + right) / 2f else left + (right - left) * i / (points.size - 1).toFloat()
            fun y(v: Double): Float = top + (bottom - top) * (1.0 - ((v - minV) / span)).toFloat()
            // 目标线
            val ty = y(targetKg)
            drawLine(
                color = Holo.Warning.copy(alpha = 0.55f),
                start = Offset(left, ty),
                end = Offset(right, ty),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
            )
            // 渐变填充
            val fill = Path()
            fill.moveTo(x(0), bottom)
            points.forEachIndexed { i, v -> fill.lineTo(x(i), y(v)) }
            fill.lineTo(x(points.size - 1), bottom)
            fill.close()
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(
                    listOf(Holo.Primary.copy(alpha = 0.30f), Holo.Primary.copy(alpha = 0.02f)),
                    startY = top,
                    endY = bottom
                )
            )
            // 折线
            val line = Path()
            points.forEachIndexed { i, v ->
                if (i == 0) line.moveTo(x(i), y(v)) else line.lineTo(x(i), y(v))
            }
            drawPath(path = line, color = Holo.Primary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            points.forEachIndexed { i, v ->
                drawCircle(color = Holo.Primary, radius = 2.5.dp.toPx(), center = Offset(x(i), y(v)))
            }
        }
        if (labels.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                labels.forEach { MonoText(text = it, color = Holo.TextMuted, size = 8) }
            }
        }
    }
}

// ---------------- 热力图 ----------------

@Composable
fun Heatmap(cells: List<Pair<String, Int>>, modifier: Modifier = Modifier, color: Color = Holo.Primary) {
    val maxValue = cells.maxOfOrNull { it.second } ?: 0
    Box(modifier = modifier.fillMaxWidth().height(96.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (cells.isEmpty()) return@Canvas
            val gap = 2.dp.toPx()
            val cols = 13
            val cellW = (size.width - gap * (cols - 1)) / cols
            val rows = 7
            val cellH = (size.height - gap * (rows - 1)) / rows
            cells.forEachIndexed { index, cell ->
                val col = index / rows
                val row = index % rows
                if (col >= cols) return@forEachIndexed
                val alpha = if (maxValue <= 0) 0.05f else (0.08f + 0.82f * (cell.second.toFloat() / maxValue))
                drawRoundRect(
                    color = color.copy(alpha = alpha),
                    topLeft = Offset(col * (cellW + gap), row * (cellH + gap)),
                    size = Size(cellW, cellH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f, 1.5f)
                )
            }
        }
    }
}

// ---------------- 其他 ----------------

@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Holo.Border, RoundedCornerShape(4.dp))
            .padding(2.dp)
    ) {
        options.forEachIndexed { index, option ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (active) Holo.Primary.copy(alpha = 0.16f) else Color.Transparent,
                        RoundedCornerShape(3.dp)
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option,
                    color = if (active) Holo.Primary else Holo.TextMuted,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun HoloButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    color: Color = Holo.Primary,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .background(
                if (filled) color.copy(alpha = if (enabled) 0.14f else 0.05f) else Color.Transparent,
                CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
            )
            .border(
                1.dp,
                color.copy(alpha = if (enabled) 0.8f else 0.3f),
                CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) color else color.copy(alpha = 0.4f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun DifficultyBars(level: Int, height: Dp = 14.dp) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..5) {
            val active = i <= level
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((height * (0.45f + 0.1375f * i)))
                    .background(if (active) Holo.Primary else Holo.Border)
            )
        }
    }
}

@Composable
fun TagChip(text: String, selected: Boolean = false, color: Color = Holo.Primary, onClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .background(
                if (selected) color.copy(alpha = 0.18f) else Holo.SurfaceAlt,
                RoundedCornerShape(3.dp)
            )
            .border(1.dp, if (selected) color.copy(alpha = 0.8f) else Holo.Border, RoundedCornerShape(3.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = if (selected) color else Holo.TextMuted,
            fontSize = 11.sp
        )
    }
}

@Composable
fun ErrorCard(message: String, onRetry: () -> Unit) {
    HoloCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "系统没有回应", color = Holo.Warning, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                MutedText(text = message, size = 11, maxLines = 3)
            }
            HoloButton(text = "重试", onClick = onRetry, color = Holo.Warning)
        }
    }
}

@Composable
fun OfflineBanner(visible: Boolean) {
    if (visible) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Holo.Warning.copy(alpha = 0.14f))
                .border(0.5.dp, Holo.Warning.copy(alpha = 0.4f))
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            MonoText(text = "离线 · 显示缓存", color = Holo.Warning, size = 10)
        }
    }
}

@Composable
fun SkeletonCard(height: Dp = 90.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(Holo.SurfaceAlt.copy(alpha = 0.55f), CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
            .border(1.dp, Holo.Border.copy(alpha = 0.4f), CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp))
    )
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Holo.TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

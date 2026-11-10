package app.unscroll.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun DayBarChart(
    values: List<Long>,
    highlightIndex: Int?,
    barColor: Color,
    highlightColor: Color,
    averageColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(148.dp),
    ) {
        if (values.isEmpty()) return@Canvas
        val count = values.size
        val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
        val gap = (if (count > 14) 2.dp else 4.dp).toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
        val radius = CornerRadius(barWidth / 3f, barWidth / 3f)
        values.forEachIndexed { index, value ->
            val fraction = value.toFloat() / max.toFloat()
            val barHeight = if (value > 0L) {
                (size.height * fraction).coerceAtLeast(2.dp.toPx())
            } else {
                0f
            }
            val left = index * (barWidth + gap)
            drawRoundRect(
                color = if (index == highlightIndex) highlightColor else barColor,
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
        val sum = values.sum()
        if (sum > 0L) {
            val avgFraction = (sum.toFloat() / count.toFloat()) / max.toFloat()
            val y = size.height * (1f - avgFraction.coerceIn(0f, 1f))
            drawLine(
                color = averageColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }
}

@Composable
fun CategoryRing(
    fractions: List<Float>,
    colors: List<Color>,
    track: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(168.dp)) {
        val stroke = 18.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)
        val total = fractions.sum()
        if (total <= 0f) {
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
            return@Canvas
        }
        val gap = if (fractions.count { it > 0f } > 1) 1.6f else 0f
        var start = -90f
        fractions.forEachIndexed { index, fraction ->
            val full = 360f * (fraction / total)
            val sweep = (full - gap).coerceAtLeast(0f)
            if (sweep > 0f) {
                drawArc(
                    color = colors.getOrElse(index) { track },
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
            }
            start += full
        }
    }
}

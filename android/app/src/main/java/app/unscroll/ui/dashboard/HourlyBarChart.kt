package app.unscroll.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun HourlyBarChart(
    values: List<Long>,
    highlightHour: Int?,
    barColor: Color,
    highlightColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(148.dp),
    ) {
        val count = values.size.coerceAtLeast(1)
        val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
        val gap = 3.dp.toPx()
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        values.forEachIndexed { index, value ->
            val fraction = value.toFloat() / max.toFloat()
            val barHeight = if (value > 0L) {
                (size.height * fraction).coerceAtLeast(3.dp.toPx())
            } else {
                0f
            }
            val left = index * (barWidth + gap)
            drawRoundRect(
                color = if (index == highlightHour) highlightColor else barColor,
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
fun ProportionBar(
    fraction: Float,
    color: Color,
    track: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(6.dp),
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = track, size = size, cornerRadius = radius)
        val width = when {
            fraction <= 0f -> 0f
            else -> (size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(2.dp.toPx())
        }
        if (width > 0f) {
            drawRoundRect(
                color = color,
                size = Size(width.coerceAtMost(size.width), size.height),
                cornerRadius = radius,
            )
        }
    }
}

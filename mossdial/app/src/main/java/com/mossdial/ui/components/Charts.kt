package com.mossdial.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The two hand-drawn graphs the server tab plots. Both exist because "analytics" bought as a chart
 * library would drag in a dependency for two shapes this app already owns a palette for; what they
 * draw comes straight from the server's own counters, one second at a time.
 */

/**
 * A rolling area chart of the last [capacity] seconds.
 *
 * The line is smoothed with mid-point béziers so a jumpy series does not read as a seismograph, the
 * area under it is the line's own colour at an eighth of its strength, and the newest point carries
 * a filled dot at the chart's leading edge. Empty input draws only the baseline: a stopped server
 * is a flat line, which is the honest picture.
 *
 * The x domain is the full window whether or not it has filled yet, so a chart grows from the left
 * for its first minute and then scrolls — the same way every real-time feed on the platform reads.
 */
@Composable
fun MiniAreaChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    capacity: Int = 60,
    height: Dp = 112.dp
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val w = size.width
        val h = size.height
        if (values.size < 2) return@Canvas

        val peak = (values.max() * 1.2f).coerceAtLeast(0.5f)
        val topPad = 6.dp.toPx()
        val bottomPad = 4.dp.toPx()
        // A flat series must not sit ON the canvas edge: a zero line hugging the bottom, over a
        // baseline of its own, reads as a slider rather than as a chart. The floor is lifted so a
        // quiet minute is a low line with depth under it, and the area below it is the shading the
        // gradient fills.
        val floorGap = 16.dp.toPx()
        val plotH = h - topPad - bottomPad - floorGap
        val stepX = w / (capacity - 1).coerceAtLeast(1)
        fun yOf(v: Float) = topPad + plotH * (1f - (v.coerceIn(0f, peak) / peak))

        val line = Path()
        values.forEachIndexed { index, v ->
            val x = index * stepX
            val y = yOf(v)
            if (index == 0) {
                line.moveTo(x, y)
            } else {
                val previousX = (index - 1) * stepX
                line.cubicTo(
                    x1 = previousX + stepX / 2f,
                    y1 = yOf(values[index - 1]),
                    x2 = x - stepX / 2f,
                    y2 = y,
                    x3 = x,
                    y3 = y
                )
            }
        }

        val lastX = (values.size - 1) * stepX
        val lastValue = values.last()
        val lastY = yOf(lastValue)
        val fill = Path().apply {
            addPath(line)
            lineTo(lastX, h - bottomPad)
            lineTo(0f, h - bottomPad)
            close()
        }

        // The body of the line is its depth: the colour pours from under the stroke into the
        // floor and thins to nothing, so the curve sits above its own shade.
        drawPath(
            fill,
            brush = Brush.verticalGradient(
                colors = listOf(
                    lineColor.copy(alpha = 0.24f),
                    lineColor.copy(alpha = 0.02f)
                ),
                startY = topPad,
                endY = h - bottomPad
            )
        )
        drawPath(
            line,
            color = lineColor,
            style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
        )
        // The leading dot marks where the line has got to; at zero it would be a knob on an empty
        // track, so it only appears once the series has actually moved.
        if (lastValue > 0f) {
            drawCircle(color = lineColor, radius = 4.dp.toPx(), center = Offset(lastX, lastY))
        }
    }
}

/** One slice of [DonutChart]: its share of the whole and the colour it is drawn in. */
data class DonutSegment(
    val weight: Float,
    val color: Color
)

/**
 * A donut for shares of a whole, with the total set in the hole.
 *
 * The rings sweep in once when the chart first appears and from then on simply redraw as the
 * fractions move — a chart that re-performed its entrance every poll second would look like it was
 * breathing rather than measuring. Slices are separated by a three-degree gap so adjacent shares
 * of nearly equal size still read as separate answers.
 */
@Composable
fun DonutChart(
    segments: List<DonutSegment>,
    centerValue: String,
    centerLabel: String,
    modifier: Modifier = Modifier,
    thickness: Dp = 22.dp
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // The quiet ring underneath is what gives the slices their depth: it shows through in
            // the gaps and carries the circle when a segment is momentarily zero, so the chart
            // reads as a solid object rather than as floating arcs.
            drawCircle(
                color = track,
                style = Stroke(width = thickness.toPx(), cap = StrokeCap.Butt)
            )
            val total = segments.fold(0f) { acc, segment -> acc + segment.weight }.coerceAtLeast(1e-6f)
            var start = -90f
            val gap = if (segments.size > 1) 3f else 0f
            segments.forEach { segment ->
                val sweep = (segment.weight / total) * 360f * entrance.value
                drawArc(
                    color = segment.color,
                    startAngle = start + gap / 2f,
                    sweepAngle = (sweep - gap).coerceAtLeast(0f),
                    useCenter = false,
                    style = Stroke(width = thickness.toPx(), cap = StrokeCap.Butt)
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerValue,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum"
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = centerLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

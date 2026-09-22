package tw.myfsl.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextAlign

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat

data class ChartSeries(val name: String, val color: Color, val values: List<Money>)

/**
 * 現金水位折線圖（dataviz skill）：每個點是一個月的月底水位（R-FC-09），2dp 細線，虛線為安全線（旁邊有文字標示）。
 * 點圖看當月各線數值；兩條線以上一定有圖例。文字一律用主題字色，線色只畫在色塊上。
 * 線色由呼叫端從 [tw.myfsl.app.ui.theme.chartColors] 依固定順序取。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CashLineChart(
    series: List<ChartSeries>,
    labels: List<String>,
    safetyLevel: Money,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    interactive: Boolean = true,
    showLegend: Boolean = series.size > 1,
) {
    val count = series.maxOfOrNull { it.values.size } ?: 0
    if (count == 0) return
    var selected by remember(series, labels) { mutableStateOf<Int?>(null) }

    val all = series.flatMap { it.values } + safetyLevel + 0L
    val max = all.max().toDouble()
    val min = all.min().toDouble()
    val span = (max - min).takeIf { it > 0 } ?: 1.0

    val grid = MaterialTheme.colorScheme.outlineVariant
    val safety = MaterialTheme.colorScheme.error
    val cursor = MaterialTheme.colorScheme.onSurfaceVariant
    val ring = MaterialTheme.colorScheme.surface
    val lowest = series.firstOrNull()?.values?.minOrNull()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(tw.myfsl.app.ui.theme.Spacing.sm)) {
        Box(
            Modifier.fillMaxWidth().height(height)
                .semantics {
                    contentDescription = "現金水位圖，${series.joinToString("；") { s -> "${s.name} 最低 ${MoneyFormat.currency(s.values.minOrNull() ?: 0)}" }}，安全線 ${MoneyFormat.currency(safetyLevel)}"
                }
                .then(
                    if (interactive) {
                        Modifier.pointerInput(count) {
                            detectTapGestures { offset ->
                                val step = if (count > 1) size.width.toFloat() / (count - 1) else 0f
                                val index = if (step == 0f) 0 else Math.round(offset.x / step).coerceIn(0, count - 1)
                                selected = if (selected == index) null else index
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Canvas(Modifier.fillMaxWidth().height(height)) {
                val w = size.width
                val h = size.height
                fun x(i: Int) = if (count > 1) w * i / (count - 1) else w / 2
                fun y(v: Money) = (h - (v - min) / span * h).toFloat()

                // 0 線與安全線
                drawLine(grid, Offset(0f, y(0)), Offset(w, y(0)), strokeWidth = 1.dp.toPx())
                drawLine(
                    safety.copy(alpha = 0.7f), Offset(0f, y(safetyLevel)), Offset(w, y(safetyLevel)),
                    strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                )

                series.forEach { s ->
                    if (s.values.isEmpty()) return@forEach
                    val path = Path()
                    s.values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
                    drawPath(path, s.color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }

                selected?.let { i ->
                    drawLine(cursor.copy(alpha = 0.5f), Offset(x(i), 0f), Offset(x(i), h), strokeWidth = 1.dp.toPx())
                    series.forEach { s ->
                        s.values.getOrNull(i)?.let { v ->
                            drawCircle(ring, radius = 6.dp.toPx(), center = Offset(x(i), y(v)))
                            drawCircle(s.color, radius = 4.dp.toPx(), center = Offset(x(i), y(v)))
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth()) {
            Text(labels.firstOrNull().orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("安全線 ${MoneyFormat.compact(safetyLevel)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text(labels.lastOrNull().orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }

        val index = selected
        if (index != null) {
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                    .padding(horizontal = tw.myfsl.app.ui.theme.Spacing.md, vertical = tw.myfsl.app.ui.theme.Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(tw.myfsl.app.ui.theme.Spacing.xs),
            ) {
                Text("${labels.getOrNull(index).orEmpty()} 月底水位", style = MaterialTheme.typography.labelMedium)
                series.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tw.myfsl.app.ui.theme.Spacing.sm)) {
                        Box(Modifier.size(width = 16.dp, height = 4.dp).background(s.color, MaterialTheme.shapes.extraSmall))
                        Text(s.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        val v = s.values.getOrNull(index) ?: 0
                        Text(
                            MoneyFormat.currency(v),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (v < safetyLevel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        } else if (showLegend) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(tw.myfsl.app.ui.theme.Spacing.lg)) {
                series.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(tw.myfsl.app.ui.theme.Spacing.sm)) {
                        Box(Modifier.size(width = 16.dp, height = 4.dp).background(s.color, MaterialTheme.shapes.extraSmall))
                        Text(s.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else if (lowest != null && interactive) {
            Text("點圖看各月數字", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

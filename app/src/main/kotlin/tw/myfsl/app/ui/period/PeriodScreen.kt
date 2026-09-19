package tw.myfsl.app.ui.period

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.domain.LineProgress
import tw.myfsl.app.core.domain.PaceStatus
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.ui.components.CashLineChart
import tw.myfsl.app.ui.components.ChartSeries
import tw.myfsl.app.ui.plan.MethodDot
import tw.myfsl.app.ui.theme.StatusColors

/** 本期：現金與水位、本週檢查、本月可調支出、接下來到期。 */
@Composable
fun PeriodScreen(
    state: PeriodUiState,
    onStartCheckIn: () -> Unit,
    onOpenForecast: () -> Unit,
    onToggleShowAll: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val o = state.overview
    if (o == null) {
        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本期", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "設定") }
            }
            Text("還沒有資料。先到「帳戶」新增帳戶，再到「計畫」匯入或新增項目。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本期", style = MaterialTheme.typography.titleLarge)
                    Text("${o.period.fullLabel} · 時間進度 ${o.timePercent}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "設定") }
            }
        }

        item {
            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("可動用現金", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(MoneyFormat.currency(o.liquid), style = MaterialTheme.typography.headlineSmall)
                        if (o.cardReserve > 0) {
                            Text(
                                "其中 ${MoneyFormat.currency(o.cardReserve)} 要留著繳卡費，扣掉後可用 ${MoneyFormat.currency(o.liquid - o.cardReserve)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (o.liquid - o.cardReserve < 0) StatusColors.warningText else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    o.monthsUntilBelowSafety?.let { months ->
                        Text(
                            if (months <= 0) "本月低於安全線" else "約 $months 個月後低於安全線",
                            style = MaterialTheme.typography.labelMedium,
                            color = StatusColors.warningText,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                o.liquidAccounts.forEach {
                    Row {
                        Text(it.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(MoneyFormat.currency(it.balance), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (o.monthlyLows.size > 1) {
                    CashLineChart(
                        series = listOf(ChartSeries("現況", MaterialTheme.colorScheme.primary, o.monthlyLows)),
                        labels = o.monthLabels,
                        safetyLevel = o.safetyLevel,
                        height = 110.dp,
                        interactive = false,
                    )
                    Text(
                        "最低水位（半月估算）${MoneyFormat.currency(o.lowest)}" + (o.lowestLabel?.let { "（$it）" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (o.lowest < o.safetyLevel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                o.shortfall?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.warningText) }
                TextButton(onClick = onOpenForecast) { Text("看試算與情境") }
            }
        }

        o.backupReminder?.let { reminder ->
            item {
                Card(
                    onClick = onOpenSettings,
                    colors = CardDefaults.cardColors(containerColor = StatusColors.warningContainer, contentColor = StatusColors.onWarningContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(reminder, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                }
            }
        }

        item {
            Section {
                Text("本週檢查", style = MaterialTheme.typography.titleSmall)
                Text(o.checkIn.text, style = MaterialTheme.typography.bodyMedium)
                o.checkIn.missedLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.warningText) }
                Button(onClick = onStartCheckIn) { Text(if (o.checkIn.due) "開始檢查" else "再檢查一次") }
            }
        }

        item { Text("本月可調支出", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp)) }
        if (o.budget.isEmpty()) {
            item { Text("沒有可調項目。到「計畫」把項目設成「可調」才會出現在這裡。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val shown = if (state.showAllBudget) o.budget else o.budget.take(4)
        items(shown, key = { "${it.item.id}-${it.method}" }) { BudgetRow(it) }
        if (o.budget.size > 4) {
            item { TextButton(onClick = onToggleShowAll) { Text(if (state.showAllBudget) "收起" else "全部 ${o.budget.size} 項") } }
        }

        if (o.upcoming.isNotEmpty()) {
            item { Text("接下來到期", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp)) }
            items(o.upcoming) { up ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(up.period.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                    Text(up.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        (if (up.isIncome) "+" else "") + MoneyFormat.currency(up.amount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (up.isIncome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun BudgetRow(line: LineProgress) {
    val warn = line.status == PaceStatus.OVER || line.status == PaceStatus.AHEAD
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MethodDot(line.method, Modifier.padding(end = 6.dp))
                Text("${line.item.name} · ${line.method.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    line.status.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = when (line.status) {
                        PaceStatus.OVER -> MaterialTheme.colorScheme.error
                        PaceStatus.AHEAD -> StatusColors.warningText
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            LinearProgressIndicator(
                progress = { line.spentRatio.toFloat().coerceIn(0f, 1f) },
                color = if (line.status == PaceStatus.OVER) MaterialTheme.colorScheme.error else if (warn) StatusColors.warningFill else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                Text(
                    "${MoneyFormat.currency(line.actual)} / ${MoneyFormat.currency(line.planned)} · 花費 ${line.spentPercent}% · 時間 ${line.timePercent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                line.dailyAllowance?.let { Text("每日可用 ${MoneyFormat.currency(it)}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

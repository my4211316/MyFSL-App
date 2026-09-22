package tw.myfsl.app.ui.period

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.domain.LineProgress
import tw.myfsl.app.core.domain.PaceStatus
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.ui.components.Banner
import tw.myfsl.app.ui.components.CashLineChart
import tw.myfsl.app.ui.components.ChartSeries
import tw.myfsl.app.ui.components.EmptyLine
import tw.myfsl.app.ui.components.HeroCard
import tw.myfsl.app.ui.components.HintText
import tw.myfsl.app.ui.components.ItemIcons
import tw.myfsl.app.ui.components.ListCard
import tw.myfsl.app.ui.components.ListDivider
import tw.myfsl.app.ui.components.ListRow
import tw.myfsl.app.ui.components.Loading
import tw.myfsl.app.ui.components.ScreenTopBar
import tw.myfsl.app.ui.components.SectionCard
import tw.myfsl.app.ui.components.SectionHeader
import tw.myfsl.app.ui.components.StatePane
import tw.myfsl.app.ui.components.StatusBadge
import tw.myfsl.app.ui.components.Tone
import tw.myfsl.app.ui.components.ToneProgress
import tw.myfsl.app.ui.theme.Spacing
import tw.myfsl.app.ui.theme.chartColors

/** 本期（設計稿：全畫面改版 v1）：可動用現金、現金水位一句話、備份提醒、本週檢查、本月支出進度、接下來到期。 */
@Composable
fun PeriodScreen(
    state: PeriodUiState,
    onStartCheckIn: () -> Unit,
    onOpenPlan: () -> Unit,
    onToggleShowAll: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Loading(modifier)
        return
    }
    val settings: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, contentDescription = "設定") }
    }
    val o = state.overview
    if (o == null) {
        Column(modifier.fillMaxSize()) {
            ScreenTopBar("本期", actions = settings)
            StatePane(null, "還沒有資料", "先到「帳戶」新增帳戶，再到「計畫」匯入或新增項目，這裡就會出現本期的現金與預算。")
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        ScreenTopBar("本期", subtitle = "${o.period.fullLabel} · 時間進度 ${o.timePercent}%", actions = settings)
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            item(key = "hero") {
                HeroCard(
                    label = "可動用現金",
                    value = MoneyFormat.currency(o.liquid),
                    figures = if (o.cardReserve > 0) {
                        listOf("要留給卡費" to MoneyFormat.currency(o.cardReserve), "扣掉後可用" to MoneyFormat.currency(o.liquid - o.cardReserve))
                    } else {
                        emptyList()
                    },
                )
            }

            // 水位圖在計畫（R-PLS-09）；今天總覽只留一句提醒與入口。
            item(key = "cash") {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("未來現金水位", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        o.monthsUntilBelowSafety?.let { months ->
                            StatusBadge(if (months <= 0) "本月低於安全線" else "約 $months 個月後低於安全線", tone = Tone.WARNING, icon = Icons.Rounded.Warning)
                        }
                    }
                    Text(
                        "最低 ${MoneyFormat.currency(o.lowest)}" + (o.lowestLabel?.let { "（$it）" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (o.lowest < o.safetyLevel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    o.shortfall?.let { HintText(it) }
                    TextButton(onClick = onOpenPlan, modifier = Modifier.padding(start = 0.dp)) { Text("到計畫看水位曲線") }
                }
            }

            o.backupReminder?.let { reminder ->
                item(key = "backup") { Banner(reminder, Icons.Rounded.Backup, onClick = onOpenSettings) }
            }

            item(key = "checkin") {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("本週檢查", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (o.checkIn.due) StatusBadge("該檢查了", tone = Tone.WARNING)
                    }
                    Text(o.checkIn.text, style = MaterialTheme.typography.bodyMedium)
                    o.checkIn.missedLabel?.let { HintText(it) }
                    Button(onClick = onStartCheckIn) { Text(if (o.checkIn.due) "開始檢查" else "再檢查一次") }
                }
            }

            item(key = "budget") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SectionHeader(
                        "本月支出進度",
                        action = if (o.budget.size > 4) (if (state.showAllBudget) "收起" else "全部 ${o.budget.size} 項") else null,
                        onAction = onToggleShowAll,
                    )
                    ListCard {
                        if (o.budget.isEmpty()) EmptyLine("沒有可調項目。到「計畫」把項目設成「可調」才會出現在這裡。")
                        val shown = if (state.showAllBudget) o.budget else o.budget.take(4)
                        shown.forEachIndexed { index, line ->
                            if (index > 0) ListDivider()
                            BudgetRow(line)
                        }
                    }
                }
            }

            if (o.upcoming.isNotEmpty()) {
                item(key = "upcoming") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SectionHeader("接下來到期")
                        ListCard {
                            o.upcoming.forEachIndexed { index, up ->
                                if (index > 0) ListDivider()
                                ListRow(
                                    title = up.label,
                                    lead = { Text(up.period.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    trailing = (if (up.isIncome) "+" else "") + MoneyFormat.currency(up.amount),
                                    trailingColor = if (up.isIncome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(Spacing.xl)) }
        }
    }
}

/** 一個可調支出列：狀態用「字＋顏色」一起標（超支紅、花太快黃、其他中性）。 */
@Composable
private fun BudgetRow(line: LineProgress) {
    val tone = when (line.status) {
        PaceStatus.OVER -> Tone.ERROR
        PaceStatus.AHEAD -> Tone.WARNING
        else -> Tone.NEUTRAL
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .clearAndSetSemantics {
                contentDescription = "${line.item.name}，${line.status.label}，" +
                    "已花 ${MoneyFormat.currency(line.actual)}，還剩 ${MoneyFormat.currency(line.remaining)}，計畫 ${MoneyFormat.currency(line.planned)}" +
                    (line.dailyAllowance?.let { "，每日可用 ${MoneyFormat.currency(it)}" } ?: "") +
                    (line.methodBreakdown?.let { "，這個月 $it" } ?: "")
            },
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(ItemIcons.of(line.item.name, line.item.type), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(20.dp))
            Text(line.item.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            StatusBadge(line.status.label, tone = tone)
        }
        ToneProgress(line.spentRatio.toFloat(), tone)
        // 同一種進度條（R-BUD-10）：用了多少、還剩多少；可調項目才有時間進度與每日可用（R-BUD-05）。
        Row {
            Text(
                "用了 ${MoneyFormat.currency(line.actual)} / ${MoneyFormat.currency(line.planned)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text("還剩 ${MoneyFormat.currency(line.remaining)}", style = MaterialTheme.typography.bodySmall)
        }
        if (line.flexible) {
            Row {
                Text(
                    "時間 ${line.timePercent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                line.dailyAllowance?.let {
                    Text(
                        "每日可用 ${MoneyFormat.currency(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // 預算不分支付方式，所以這裡補上這個月實際怎麼付的（R-MIX-04）。
        line.methodBreakdown?.let {
            Text("這個月：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package tw.myfsl.app.ui.records

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import tw.myfsl.app.ui.components.ChoiceChips
import tw.myfsl.app.ui.components.EmptyLine
import tw.myfsl.app.ui.components.ItemIcons
import tw.myfsl.app.ui.components.ListCard
import tw.myfsl.app.ui.components.ListDivider
import tw.myfsl.app.ui.components.ListRow
import tw.myfsl.app.ui.components.Loading
import tw.myfsl.app.ui.components.NavAction
import tw.myfsl.app.ui.components.ScreenTopBar
import tw.myfsl.app.ui.components.StatusBadge
import tw.myfsl.app.ui.components.SummaryTile
import tw.myfsl.app.ui.components.Tone
import tw.myfsl.app.ui.components.WarningText
import tw.myfsl.app.ui.theme.Spacing

/** 紀錄（設計稿：全畫面改版 v1）：按月看每一筆，點一筆修改；刪除在修改畫面裡。 */
@Composable
fun RecordsScreen(
    state: RecordsUiState,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onFilter: (RecordFilter) -> Unit,
    onEdit: (Long) -> Unit,
    editorContent: @Composable (RecordEditor) -> Unit,
    modifier: Modifier = Modifier,
) {
    state.editor?.let {
        Box(modifier.fillMaxSize()) { editorContent(it) }
        return
    }
    Column(modifier.fillMaxSize()) {
        ScreenTopBar("紀錄", navigation = NavAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回記帳", onBack))
        if (state.loading) {
            Loading()
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            item(key = "month") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious) { Icon(Icons.Rounded.ChevronLeft, contentDescription = "上個月") }
                    Text(
                        state.monthLabel,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    IconButton(onClick = onNext, enabled = state.canGoNext) { Icon(Icons.Rounded.ChevronRight, contentDescription = "下個月") }
                }
            }
            item(key = "totals") {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SummaryTile("本月至今支出", state.expenseTotal, Modifier.weight(1f))
                    SummaryTile("本月至今收入", state.incomeTotal, Modifier.weight(1f))
                }
            }
            state.missedLabel?.let { label -> item(key = "missed") { WarningText(label) } }
            item(key = "filters") {
                ChoiceChips(RecordFilter.entries, { state.filter == it }, { it.label }, onFilter)
            }
            if (state.empty) {
                item(key = "empty") { ListCard { EmptyLine("這個月還沒有紀錄") } }
            }
            state.days.forEach { day ->
                item(key = "day-${day.date}") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text(day.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
                            Text(day.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ListCard {
                            day.rows.forEachIndexed { index, row ->
                                if (index > 0) ListDivider()
                                RecordItem(row, onClick = { onEdit(row.id) })
                            }
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(Spacing.xl)) }
        }
    }
}

@Composable
private fun RecordItem(row: RecordRow, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        ListRow(
            title = row.title,
            detail = row.subtitle,
            lead = { Icon(ItemIcons.of(row.itemName, row.type), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            badge = row.sourceLabel?.let { label -> { StatusBadge(label, tone = if (label.contains("漏記") || label.contains("多記")) Tone.WARNING else Tone.NEUTRAL) } },
            trailing = row.amountText,
            trailingColor = if (row.income) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 刪除前的說明：刪掉之後會連動什麼（R-REC-EDIT-05/07）。 */
fun deleteExplanation(sourceLabel: String?): String = when {
    sourceLabel == "分期入帳" || sourceLabel == "分期手續費" -> "這一期的本金與手續費一起刪掉，回到「本月到期」（記帳頁右上角的今天總覽）。"
    sourceLabel?.startsWith("分") == true -> "分期消費會連同已入帳與未入帳的各期一起刪除。"
    sourceLabel == "貸款月繳" -> "本金與利息一起刪掉，剩餘期數加回一期，回到「本月到期」（記帳頁右上角的今天總覽）。"
    sourceLabel in setOf("每月固定", "循環利息", "繳卡費") -> "刪掉後回到「本月到期」（記帳頁右上角的今天總覽），可以重新記下。"
    sourceLabel == "到期確認" -> "刪掉後這個項目本月會回到「待確認」。"
    sourceLabel == "延期款" -> "刪掉後這筆延期款會回到「未付」。"
    else -> "刪除後無法復原。"
}

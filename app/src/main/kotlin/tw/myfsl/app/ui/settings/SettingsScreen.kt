package tw.myfsl.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import tw.myfsl.app.BuildConfig
import tw.myfsl.app.core.domain.SettingsDraft
import tw.myfsl.app.core.domain.SettingsForm
import tw.myfsl.app.core.domain.SettingsForm.Field
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.ui.components.BottomActions
import tw.myfsl.app.ui.components.ChoiceChips
import tw.myfsl.app.ui.components.DangerButton
import tw.myfsl.app.ui.components.ErrorText
import tw.myfsl.app.ui.components.FieldLabel
import tw.myfsl.app.ui.components.GroupLabel
import tw.myfsl.app.ui.components.HintText
import tw.myfsl.app.ui.components.ListCard
import tw.myfsl.app.ui.components.ListDivider
import tw.myfsl.app.ui.components.Loading
import tw.myfsl.app.ui.components.MoneyField
import tw.myfsl.app.ui.components.NavAction
import tw.myfsl.app.ui.components.NavigationRow
import tw.myfsl.app.ui.components.ScreenTopBar
import tw.myfsl.app.ui.components.SegmentedChoice
import tw.myfsl.app.core.model.ThemeMode
import tw.myfsl.app.ui.components.SelectionSheet
import tw.myfsl.app.ui.components.SwitchRow
import tw.myfsl.app.ui.components.TextInput
import tw.myfsl.app.ui.theme.Spacing
import java.time.DayOfWeek

private enum class Confirm { SAMPLE, CLEAR }

/** 選哪個帳戶的欄位（點進去是底部選單）。 */
private enum class Pick(val title: String) { CASH("付現金時扣哪個帳戶"), TRANSFER("轉帳時扣哪個帳戶"), CARD("沒指定卡片的刷卡算在哪張卡") }

/**
 * 設定（設計稿：全畫面改版 v1）：分組、一行一件事。帳戶選擇點進去是底部選單。
 * 設定欄位按底部「儲存設定」才生效；資料動作（備份、還原、清除）按了就做，危險的會先確認。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onChange: ((SettingsDraft) -> SettingsDraft) -> Unit,
    onSave: () -> Unit,
    onLoadSample: () -> Unit,
    onClearAll: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelRestore: () -> Unit,
    onClearCrashLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    if (state.loading || draft == null) {
        Loading(modifier)
        return
    }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var picking by remember { mutableStateOf<Pick?>(null) }
    val errors = state.errors

    Column(modifier.fillMaxSize().imePadding()) {
        ScreenTopBar("設定", navigation = NavAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            GroupLabel("現金水位")
            MoneyField(
                "安全線", draft.safetyLevel, { v -> onChange { it.copy(safetyLevel = v) } },
                error = errors[Field.SAFETY], supporting = "現金水位低於這個金額就提醒",
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FieldLabel("本期與試算預設看幾個月")
                SegmentedChoice(SettingsForm.HORIZONS, draft.horizonMonths, { "$it 個月" }, { m -> onChange { it.copy(horizonMonths = m) } })
            }

            GroupLabel("本週檢查")
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FieldLabel("每週檢查日")
                ChoiceChips(DayOfWeek.entries, { draft.checkInDay == it }, { SettingsForm.weekdayLabel(it) }, { d -> onChange { it.copy(checkInDay = d) } })
            }
            TextInput(
                "刷卡入帳延遲（天）", draft.cardPostingDays, { v -> onChange { it.copy(cardPostingDays = v) } },
                error = errors[Field.POSTING_DAYS], supporting = "對帳時，最近這幾天的刷卡可能還沒出現在銀行 App", number = true,
            )
            TextInput(
                "到期前幾天提醒", draft.reminderDays, { v -> onChange { it.copy(reminderDays = v) } },
                error = errors[Field.REMINDER_DAYS], supporting = "用逗號分開，例如「7, 3」；空白為不提醒。卡費、貸款與每月固定的付款都會提醒",
            )

            GroupLabel("試算")
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FieldLabel("未來的支出怎麼付")
                SegmentedChoice(
                    listOf(false, true),
                    draft.forecastUsesCard,
                    { if (it) "依比例刷卡" else "全部當現金付" },
                    { uses -> onChange { it.copy(forecastUsesCard = uses) } },
                )
                if (draft.forecastUsesCard) {
                    TextInput(
                        "刷卡比例（%）", draft.forecastCardPercent, { v -> onChange { it.copy(forecastCardPercent = v) } },
                        error = errors[Field.CARD_PERCENT], number = true,
                    )
                }
                HintText(
                    if (draft.forecastUsesCard) {
                        "試算假設計畫的支出有這個比例會刷卡，之後靠繳卡費扣款；其餘當月從帳戶扣。"
                    } else {
                        "試算假設計畫的支出在消費當月就從帳戶扣。刷卡遞延、分期、卡循是調度現金的手段，" +
                            "想看那樣的結果請到試算開一個情境比較。"
                    },
                )
                HintText("已經欠的卡債、分期與每期要繳的卡費不受影響，一律照合約算。")
            }

            GroupLabel("外觀")
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FieldLabel("深色或淺色")
                SegmentedChoice(ThemeMode.entries, draft.themeMode, { it.label }, { m -> onChange { it.copy(themeMode = m) } })
                HintText("「跟隨系統」會照手機的深色模式自動切換。")
            }

            GroupLabel("記帳")
            ListCard {
                Column(Modifier.padding(horizontal = Spacing.lg)) {
                    SwitchRow(
                        "記帳時指定哪一張信用卡", draft.pickCard, { v -> onChange { it.copy(pickCard = v) } },
                        detail = "關掉時刷卡只記「信用卡」，對帳時看全部卡片合計",
                    )
                }
                ListDivider()
                NavigationRow(Pick.CASH.title, accountLabel(draft.cashAccountId, state.liquidAccounts, state.autoCash), onClick = { picking = Pick.CASH })
                ListDivider()
                NavigationRow(Pick.TRANSFER.title, accountLabel(draft.transferAccountId, state.liquidAccounts, state.autoTransfer), onClick = { picking = Pick.TRANSFER })
                if (state.cards.isNotEmpty()) {
                    ListDivider()
                    NavigationRow(Pick.CARD.title, accountLabel(draft.defaultCardId, state.cards, state.cards.firstOrNull()?.name), onClick = { picking = Pick.CARD })
                }
            }
            listOfNotNull(errors[Field.CASH_ACCOUNT], errors[Field.TRANSFER_ACCOUNT], errors[Field.DEFAULT_CARD]).forEach { ErrorText(it) }
            if (state.cards.isNotEmpty()) {
                HintText("記帳沒選卡片、計畫裡的刷卡、沒指定卡片的分期，試算時都算在「沒指定卡片的刷卡」那張卡（利息與繳款也照它的條件）。")
            }

            GroupLabel("資料")
            ListCard {
                NavigationRow("匯出完整備份", state.lastBackup, onClick = { if (!state.busy) onExportBackup() }, lead = { Icon(Icons.Rounded.Upload, null) })
                ListDivider()
                NavigationRow("從備份還原", "用備份檔取代目前的資料", onClick = { if (!state.busy) onImportBackup() }, lead = { Icon(Icons.Rounded.Restore, null) })
                ListDivider()
                NavigationRow("載入示意資料", "虛構的家庭帳，會先清掉目前資料", onClick = { if (!state.busy) confirm = Confirm.SAMPLE }, lead = { Icon(Icons.Rounded.Science, null) })
            }
            HintText("完整備份包含帳戶、餘額校正、計畫、記帳、分期、情境與設定。換手機或重裝 App 前先匯出。檔案裡是你的財務資料，請自己保管好。")
            DangerButton("清除所有資料", onClick = { if (!state.busy) confirm = Confirm.CLEAR }, icon = Icons.Rounded.DeleteForever)

            GroupLabel("關於")
            Text("MyFSL ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", style = MaterialTheme.typography.bodyMedium)
            HintText(
                "App 本身不連網、不上傳任何資料。若手機開啟了 Google 備份，Android 系統會把 App 資料（加密）備份到你的 Google 帳號，換手機時可以帶過去。" +
                    "試算與反推只是依你輸入的計畫和假設推算，不是財務建議。",
            )
            state.crashLog?.let { log ->
                val clipboard = LocalClipboardManager.current
                Text("上次當機紀錄", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                HintText(log.lines().take(8).joinToString("\n"))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(log)) }, modifier = Modifier.weight(1f)) { Text("複製紀錄") }
                    TextButton(onClick = onClearCrashLog, modifier = Modifier.weight(1f)) { Text("清除") }
                }
            }
        }
        BottomActions(primary = if (state.saved) "已儲存" else "儲存設定", onPrimary = onSave)
    }

    picking?.let { pick ->
        val (options, auto, selected) = when (pick) {
            Pick.CASH -> Triple(state.liquidAccounts, state.autoCash, draft.cashAccountId)
            Pick.TRANSFER -> Triple(state.liquidAccounts, state.autoTransfer, draft.transferAccountId)
            Pick.CARD -> Triple(state.cards, state.cards.firstOrNull()?.name, draft.defaultCardId)
        }
        val choices: List<Account?> = listOf<Account?>(null) + options
        SelectionSheet(
            title = pick.title,
            options = choices,
            isSelected = { (it?.id) == selected || (it == null && selected == null) },
            label = { it?.name ?: ("自動" + (auto?.let { a -> "（$a）" } ?: "")) },
            onSelect = { account ->
                when (pick) {
                    Pick.CASH -> onChange { it.copy(cashAccountId = account?.id) }
                    Pick.TRANSFER -> onChange { it.copy(transferAccountId = account?.id) }
                    Pick.CARD -> onChange { it.copy(defaultCardId = account?.id) }
                }
            },
            onClose = { picking = null },
        )
    }

    state.pendingRestore?.let { summary ->
        val time = java.time.Instant.ofEpochMilli(summary.exportedAtMillis).atZone(java.time.ZoneId.systemDefault())
        AlertDialog(
            onDismissRequest = onCancelRestore,
            title = { Text(if (summary.needsRescue) "救援還原：有些資料對不上" else "用這份備份取代目前資料？") },
            text = {
                Text(
                    buildString {
                        append("備份時間：${time.year}/${time.monthValue}/${time.dayOfMonth} ${"%02d:%02d".format(time.hour, time.minute)}\n")
                        append(summary.text)
                        append("\n\n目前 App 裡的所有資料會被這份備份取代。")
                        if (summary.needsRescue) {
                            append("\n\n這份備份裡有資料彼此對不上，只能用救援方式還原，下面這些會被略過或對不上：")
                            summary.warnings.forEach { append("\n· $it") }
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmRestore) {
                    Text(if (summary.needsRescue) "仍要救援還原" else "還原", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onCancelRestore) { Text("取消") } },
        )
    }

    confirm?.let { which ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (which == Confirm.SAMPLE) "載入示意資料？" else "清除所有資料？") },
            text = {
                Text(
                    if (which == Confirm.SAMPLE) {
                        "會先清掉現在所有的帳戶、計畫、記帳與情境，再放入虛構的示意家庭資料。"
                    } else {
                        "帳戶、計畫、記帳、分期與情境都會刪除，無法復原。建議先匯出完整備份。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (which == Confirm.SAMPLE) onLoadSample() else onClearAll()
                    confirm = null
                }) { Text(if (which == Confirm.SAMPLE) "載入" else "清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}

private fun accountLabel(selected: Long?, accounts: List<Account>, auto: String?): String =
    accounts.firstOrNull { it.id == selected }?.name ?: ("自動" + (auto?.let { "（$it）" } ?: ""))

package tw.myfsl.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import tw.myfsl.app.BuildConfig
import tw.myfsl.app.core.domain.SettingsDraft
import tw.myfsl.app.core.domain.SettingsForm
import tw.myfsl.app.core.domain.SettingsForm.Field
import tw.myfsl.app.core.model.Account
import java.time.DayOfWeek

private enum class Confirm { SAMPLE, CLEAR }

@OptIn(ExperimentalLayoutApi::class)
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
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val errors = state.errors

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text("設定", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("現金水位")
            OutlinedTextField(
                value = draft.safetyLevel,
                onValueChange = { v -> onChange { it.copy(safetyLevel = v) } },
                label = { Text("安全線（元）") },
                supportingText = { Text(errors[Field.SAFETY] ?: "現金水位低於這個金額就提醒") },
                isError = errors[Field.SAFETY] != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Label("本期與試算預設看幾個月")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsForm.HORIZONS.forEach { m ->
                    FilterChip(selected = draft.horizonMonths == m, onClick = { onChange { it.copy(horizonMonths = m) } }, label = { Text("$m 個月") })
                }
            }

            HorizontalDivider()
            Section("本週檢查")
            Label("每週檢查日")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(selected = draft.checkInDay == day, onClick = { onChange { it.copy(checkInDay = day) } }, label = { Text(SettingsForm.weekdayLabel(day)) })
                }
            }
            OutlinedTextField(
                value = draft.cardPostingDays,
                onValueChange = { v -> onChange { it.copy(cardPostingDays = v) } },
                label = { Text("刷卡入帳延遲（天）") },
                supportingText = { Text(errors[Field.POSTING_DAYS] ?: "對帳時，最近這幾天的刷卡可能還沒出現在銀行 App") },
                isError = errors[Field.POSTING_DAYS] != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            Section("記帳")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("記帳時指定哪一張信用卡", style = MaterialTheme.typography.bodyLarge)
                    Text("關掉時刷卡只記「信用卡」，對帳時看全部卡片合計", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = draft.pickCard, onCheckedChange = { v -> onChange { it.copy(pickCard = v) } })
            }
            AccountPicker("付現金時扣哪個帳戶", draft.cashAccountId, state.liquidAccounts, state.autoCash, errors[Field.CASH_ACCOUNT]) { id ->
                onChange { it.copy(cashAccountId = id) }
            }
            AccountPicker("轉帳時扣哪個帳戶", draft.transferAccountId, state.liquidAccounts, state.autoTransfer, errors[Field.TRANSFER_ACCOUNT]) { id ->
                onChange { it.copy(transferAccountId = id) }
            }
            if (state.cards.isNotEmpty()) {
                AccountPicker(
                    "沒指定卡片的刷卡算在哪張卡",
                    draft.defaultCardId,
                    state.cards,
                    state.cards.firstOrNull()?.name,
                    errors[Field.DEFAULT_CARD],
                ) { id -> onChange { it.copy(defaultCardId = id) } }
                Text(
                    "記帳沒選卡片、計畫裡的刷卡、沒指定卡片的分期，試算時都算在這張卡（利息與繳款也照這張卡的條件）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text(if (state.saved) "已儲存" else "儲存設定") }

            HorizontalDivider()
            Section("資料")
            Text(
                "完整備份包含帳戶、餘額校正、計畫、記帳、分期、情境與設定。換手機或重裝 App 前先匯出。檔案裡是你的財務資料，請自己保管好。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(state.lastBackup, style = MaterialTheme.typography.labelMedium)
            Button(onClick = onExportBackup, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("匯出完整備份") }
            OutlinedButton(onClick = onImportBackup, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("從備份還原") }
            OutlinedButton(onClick = { confirm = Confirm.SAMPLE }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("載入示意資料") }
            OutlinedButton(onClick = { confirm = Confirm.CLEAR }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text("清除所有資料", color = MaterialTheme.colorScheme.error)
            }

            HorizontalDivider()
            Section("關於")
            Text("MyFSL ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", style = MaterialTheme.typography.bodyMedium)
            Text(
                "App 本身不連網、不上傳任何資料。若手機開啟了 Google 備份，Android 系統會把 App 資料（加密）備份到你的 Google 帳號，換手機時可以帶過去。試算與反推只是依你輸入的計畫和假設推算，不是財務建議。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.crashLog?.let { log ->
                val clipboard = LocalClipboardManager.current
                Text("上次當機紀錄", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                Text(
                    log.lines().take(8).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(log)) }, modifier = Modifier.weight(1f)) { Text("複製紀錄") }
                    TextButton(onClick = onClearCrashLog, modifier = Modifier.weight(1f)) { Text("清除") }
                }
            }
        }
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
                        "帳戶、計畫、記帳、分期與情境都會刪除，無法復原。建議先到「計畫」匯出 CSV 備份。"
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountPicker(label: String, selected: Long?, accounts: List<Account>, auto: String?, error: String?, onSelect: (Long?) -> Unit) {
    Label(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("自動" + (auto?.let { "（$it）" } ?: "")) })
        accounts.forEach { a -> FilterChip(selected = selected == a.id, onClick = { onSelect(a.id) }, label = { Text(a.name) }) }
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun Section(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

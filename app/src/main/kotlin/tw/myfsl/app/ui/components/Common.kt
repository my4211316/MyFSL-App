package tw.myfsl.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.ui.theme.Spacing
import tw.myfsl.app.ui.theme.warningColors

/*
 * 全 App 共用的畫面元件（設計稿：記帳首頁 v4、全畫面改版 v1）。
 * 字級只用四級：displayMedium 45、titleLarge 22、14（titleSmall／bodyMedium／labelLarge）、12（bodySmall／labelMedium）。
 * 顏色只用主題角色；間距只用 [Spacing]。
 */

// ---------------------------------------------------------------- 標題列

/** 標題列左邊的返回或關閉按鈕。 */
data class NavAction(val icon: ImageVector, val description: String, val onClick: () -> Unit)

/** 每一頁最上面的標題列：64 高，左邊（返回／關閉）＋標題與副標，右邊放動作按鈕。 */
@Composable
fun ScreenTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: NavAction? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().height(64.dp).padding(start = if (navigation != null) Spacing.xs else Spacing.lg, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigation?.let { nav ->
            IconButton(onClick = nav.onClick) { Icon(nav.icon, contentDescription = nav.description) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

// ---------------------------------------------------------------- 區塊

/** 每頁最重要的一個數字：主色大卡片，下面列幾個相關數字。 */
@Composable
fun HeroCard(label: String, value: String, figures: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.extraLarge, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.displayMedium, maxLines = 1)
            if (figures.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    figures.forEach { (l, v) -> SummaryFigure(l, v) }
                }
            }
        }
    }
}

@Composable
fun SummaryFigure(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

/** 淺色區塊卡片：一件事一張。 */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md), content = content)
    }
}

/** 小數字方塊（兩個一排）。 */
@Composable
fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor, maxLines = 1)
        }
    }
}

/** 區段標題（報讀可以依標題跳），右邊可以放一個文字按鈕。 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().padding(top = Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** 群組小標（例如計畫的群組名稱、設定的分組）。 */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = modifier.semantics { heading() })
}

/** 白底清單，列與列之間用分隔線。 */
@Composable
fun ListCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
fun ListDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

/** 清單的一列：左圖示、中間標題與說明、右邊金額或其他。 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String = "",
    trailing: String? = null,
    trailingColor: Color = Color.Unspecified,
    lead: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    end: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        lead?.let { Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) { it() } }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                badge?.invoke()
            }
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = trailingColor, textAlign = TextAlign.End) }
        end?.invoke()
    }
}

/** 點下去進入下一層的一列（右邊箭頭）。 */
@Composable
fun NavigationRow(title: String, detail: String, onClick: () -> Unit, lead: (@Composable () -> Unit)? = null) {
    Surface(onClick = onClick, color = Color.Transparent) {
        ListRow(title = title, detail = detail, lead = lead, end = {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        })
    }
}

@Composable
fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
    )
}

/** 說明文字。 */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
fun WarningText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.warningColors.warning, modifier = modifier)
}

// ---------------------------------------------------------------- 狀態標示

enum class Tone { NEUTRAL, WARNING, ERROR }

/** 小標籤：狀態用「字＋顏色」一起表達，不靠顏色單獨表達。 */
@Composable
fun StatusBadge(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.NEUTRAL, icon: ImageVector? = null) {
    val colors = MaterialTheme.colorScheme
    val warn = MaterialTheme.warningColors
    val (bg, fg) = when (tone) {
        Tone.NEUTRAL -> colors.secondaryContainer to colors.onSecondaryContainer
        Tone.WARNING -> warn.warningContainer to warn.onWarningContainer
        Tone.ERROR -> colors.errorContainer to colors.onErrorContainer
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(Modifier.height(24.dp).padding(horizontal = Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** 整條提示（例如該備份了、計畫檢查）。 */
@Composable
fun Banner(text: String, icon: ImageVector, modifier: Modifier = Modifier, detail: String? = null, onClick: (() -> Unit)? = null) {
    val warn = MaterialTheme.warningColors
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(icon, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.titleSmall)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, color = warn.warningContainer, contentColor = warn.onWarningContainer, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) { content() }
    } else {
        Surface(color = warn.warningContainer, contentColor = warn.onWarningContainer, shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) { content() }
    }
}

/** 進度條：正常主色、提醒黃、超出紅。 */
@Composable
fun ToneProgress(fraction: Float, tone: Tone, modifier: Modifier = Modifier) {
    val color = when (tone) {
        Tone.NEUTRAL -> MaterialTheme.colorScheme.primary
        Tone.WARNING -> MaterialTheme.warningColors.warning
        Tone.ERROR -> MaterialTheme.colorScheme.error
    }
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth().clearAndSetSemantics { },
    )
}

// ---------------------------------------------------------------- 支付方式

fun methodIcon(method: PaymentMethod): ImageVector = when (method) {
    PaymentMethod.CASH -> Icons.Rounded.Payments
    PaymentMethod.CREDIT_CARD -> Icons.Rounded.CreditCard
    PaymentMethod.TRANSFER -> Icons.Rounded.AccountBalance
}

/** 支付方式圖示（取代舊的三色點；顏色不單獨承載意思，旁邊都有文字）。 */
@Composable
fun MethodIcon(method: PaymentMethod?, modifier: Modifier = Modifier) {
    if (method == null) return
    Icon(methodIcon(method), contentDescription = method.label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.size(20.dp))
}

// ---------------------------------------------------------------- 選擇

/** 單選或複選的晶片列；選中的前面打勾。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    isSelected: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        options.forEach { option ->
            val selected = isSelected(option)
            FilterChip(
                selected = selected,
                onClick = { onSelect(option) },
                enabled = enabled(option),
                label = { Text(label(option)) },
                leadingIcon = if (selected) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
    }
}

/** 二到四選一：M3 分段按鈕。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector)? = null,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            val on = option == selected
            if (icon != null) {
                SegmentedButton(
                    selected = on,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    icon = {
                        SegmentedButtonDefaults.Icon(active = on) {
                            Icon(icon(option), contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    },
                    label = { Text(label(option), maxLines = 1) },
                )
            } else {
                // 沒有圖示時用預設的勾勾（沒選時不佔位置，文字置中）。
                SegmentedButton(
                    selected = on,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(label(option), maxLines = 1) },
                )
            }
        }
    }
}

/** 整列都可以切換的開關（角色是開關，報讀讀出開／關）。 */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, detail: String? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

// ---------------------------------------------------------------- 輸入

/** 金額輸入：22 級大字、前面有 $、只跳數字鍵盤。 */
@Composable
fun MoneyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    supporting: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Number,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        prefix = { Text("$", style = MaterialTheme.typography.titleLarge) },
        placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.titleLarge) } },
        isError = error != null,
        supportingText = (error ?: supporting)?.takeIf { it.isNotEmpty() }?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
    )
}

/** 一般文字或數字欄位。 */
@Composable
fun TextInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    supporting: String? = null,
    number: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        isError = error != null,
        supportingText = (error ?: supporting)?.takeIf { it.isNotEmpty() }?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------- 表單與面板

/**
 * 編輯畫面：左上關閉、中間可以捲動、底部固定「取消／儲存」（拇指範圍）。
 * 刪除、封存這類危險動作用 [DangerButton] 放在內容最後。
 */
@Composable
fun FormScaffold(
    title: String,
    onClose: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    saveLabel: String = "儲存",
    saveEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().imePadding()) {
        ScreenTopBar(title, subtitle = subtitle, navigation = NavAction(Icons.Rounded.Close, "關閉", onClose))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            content = content,
        )
        BottomActions(
            primary = saveLabel,
            onPrimary = onSave,
            primaryEnabled = saveEnabled,
            secondary = "取消",
            onSecondary = onClose,
        )
    }
}

/** 底部固定的按鈕列：左次要（外框）、右主要（實心）；只有主要時佔滿。 */
@Composable
fun BottomActions(
    primary: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        ListDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            secondary?.let {
                OutlinedButton(onClick = onSecondary, modifier = Modifier.weight(1f).height(56.dp)) { Text(it) }
            }
            Button(onClick = onPrimary, enabled = primaryEnabled, modifier = Modifier.weight(1f).height(56.dp)) { Text(primary) }
        }
    }
}

/** 紅字的危險動作（刪除、封存、清除），放在內容最後、置中。 */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = onClick) {
            icon?.let {
                Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(text, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 底部面板：從下方拉出，內容可以捲動，鍵盤出現時跟著往上。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            content = content,
        )
    }
}

/** 從清單中選一個（例如設定裡選扣款帳戶）：底部面板＋單選列。 */
@Composable
fun <T> SelectionSheet(title: String, options: List<T>, isSelected: (T) -> Boolean, label: (T) -> String, onSelect: (T) -> Unit, onClose: () -> Unit) {
    Sheet(onClose) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        ListCard {
            options.forEachIndexed { index, option ->
                if (index > 0) ListDivider()
                val selected = isSelected(option)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(option); onClose() })
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Text(label(option), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 系統狀態

/** 置中的狀態畫面：圖示、標題、一段說明、需要時一個按鈕（檢查資料、還原中、還原失敗…）。 */
@Composable
fun StatePane(
    icon: ImageVector?,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.NEUTRAL,
    action: String? = null,
    onAction: () -> Unit = {},
    busy: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = if (tone == Tone.NEUTRAL) colors.primaryContainer to colors.onPrimaryContainer else MaterialTheme.warningColors.let { it.warningContainer to it.onWarningContainer }
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
        ) {
            if (busy) {
                androidx.compose.material3.CircularProgressIndicator()
            } else if (icon != null) {
                Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.large, modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp)) }
                }
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        if (action != null) {
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth().padding(Spacing.lg).height(56.dp)) { Text(action) }
        }
    }
}

/** 讀取中。 */
@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(Modifier.semantics { contentDescription = "讀取中" })
    }
}

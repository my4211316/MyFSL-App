package tw.myfsl.app.ui.entry

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.InstallmentFee
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.ui.components.ItemIcons
import tw.myfsl.app.ui.components.EmptyLine
import tw.myfsl.app.ui.components.FieldLabel
import tw.myfsl.app.ui.components.ListCard
import tw.myfsl.app.ui.components.ListRow
import tw.myfsl.app.ui.components.SectionHeader
import tw.myfsl.app.ui.components.Sheet
import tw.myfsl.app.ui.components.SummaryFigure
import tw.myfsl.app.ui.components.SwitchRow
import tw.myfsl.app.ui.components.methodIcon
import tw.myfsl.app.ui.theme.Motion
import tw.myfsl.app.ui.theme.Spacing
import tw.myfsl.app.ui.theme.warningColors

/*
 * 記帳首頁（設計稿 v4）。字級只用四級：displayMedium 45、titleLarge 22、14（titleSmall／bodyMedium／labelLarge）、
 * 12（bodySmall／labelMedium）；粗細 400／500。顏色只用主題角色，間距只用 [Spacing]。
 *
 * 版面：上方種類與今天總覽；中間可捲動（快到期提示、金額、項目）；下方固定（付款方式、數字鍵盤、記下）。
 */

@Composable
fun EntryScreen(
    state: EntryUiState,
    onSelectType: (FlowType) -> Unit,
    onSelectItem: (Long) -> Unit,
    onSelectMethod: (PaymentMethod) -> Unit,
    onSelectCard: (Long?) -> Unit,
    onToggleNote: (String) -> Unit,
    onPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onSave: () -> Unit,
    onToggleShowAll: () -> Unit,
    onToggleInstallment: () -> Unit,
    onInstallmentMonths: (Int) -> Unit,
    onInstallmentFee: (InstallmentFee) -> Unit,
    onInstallmentFeeValue: (String) -> Unit,
    onOpenRecords: () -> Unit,
    onLoadSample: () -> Unit,
    onToggleRefund: () -> Unit,
    onAnswerMissed: (Boolean) -> Unit,
    onCancelMissed: () -> Unit,
    onOpenDue: (String) -> Unit,
    onCloseDue: () -> Unit,
    onDueAmount: (String) -> Unit,
    onDueMethod: (PaymentMethod) -> Unit,
    onDueCard: (Long?) -> Unit,
    onDueAccount: (Long) -> Unit,
    onDuePayMode: (CardPayMode) -> Unit,
    onDueUseDueDate: (Boolean) -> Unit,
    onRecordDue: () -> Unit,
    onSkipDue: () -> Unit,
    modifier: Modifier = Modifier,
    /** 下方固定區塊的高度：提示條要顯示在它上面，不蓋住鍵盤與記下。 */
    onDockHeight: (Dp) -> Unit = {},
) {
    var showOverview by rememberSaveable { mutableStateOf(false) }
    var showSoon by rememberSaveable { mutableStateOf(false) }
    var showMore by rememberSaveable { mutableStateOf(false) }

    state.dueDialog?.let { dialog ->
        DueSheet(dialog, onCloseDue, onDueAmount, onDueMethod, onDueCard, onDueAccount, onDuePayMode, onDueUseDueDate, onRecordDue, onSkipDue)
    }
    state.missedPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = onCancelMissed,
            title = { Text("是之前漏記的那筆嗎？") },
            text = { Text(prompt + "\n\n選「是」會用這筆明細取代漏記差額，項目花費和帳戶餘額都不會重複算。") },
            confirmButton = { TextButton(onClick = { onAnswerMissed(true) }) { Text("是，換成這筆") } },
            dismissButton = { TextButton(onClick = { onAnswerMissed(false) }) { Text("不是，另外一筆") } },
        )
    }
    if (state.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (state.empty) {
        EmptyState(onLoadSample, modifier)
        return
    }
    if (showOverview) {
        OverviewSheet(
            overview = state.overview,
            dues = state.dues,
            onClose = { showOverview = false },
            onOpenRecords = { showOverview = false; onOpenRecords() },
            onOpenDue = { showOverview = false; onOpenDue(it) },
        )
    }
    if (showSoon) {
        SoonSheet(state.soonDues, onClose = { showSoon = false }, onOpenDue = { showSoon = false; onOpenDue(it) })
    }
    if (showMore) {
        MoreSheet(state, { showMore = false }, onToggleNote, onToggleRefund, onToggleInstallment, onInstallmentMonths, onInstallmentFee, onInstallmentFeeValue)
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(start = Spacing.lg, end = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            TypeSelector(state.type, onSelectType, Modifier.weight(1f))
            FilledTonalIconButton(onClick = { showOverview = true }) {
                Icon(Icons.Rounded.SpaceDashboard, contentDescription = "今天總覽")
            }
        }

        val scroll = rememberScrollState()
        Box(Modifier.weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                val soon = state.soonDues
                if (soon.isNotEmpty()) {
                    SoonPill(soon, onClick = { if (soon.size == 1) onOpenDue(soon.first().key) else showSoon = true })
                }
                AmountHero(state)
                ItemGrid(state, onSelectItem, onToggleShowAll)
                extrasSummary(state)?.let { summary ->
                    Surface(onClick = { showMore = true }, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(Spacing.sm))
                            Text(summary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
            }
            // 下面還有項目時，下緣淡出提示可以往上滑。
            if (scroll.canScrollForward) {
                val surface = MaterialTheme.colorScheme.surface
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(Spacing.xl)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, surface))),
                )
            }
        }

        val density = LocalDensity.current
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            // 和底部面板同一個形狀（extraLarge 上方圓角）
            shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
            modifier = Modifier.onSizeChanged { onDockHeight(with(density) { it.height.toDp() }) },
        ) {
            Column(
                Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (state.showMethods) {
                    MethodSelector(state, onSelectMethod, onSelectCard)
                }
                Keypad(showMore = state.hasMoreOptions, onMore = { showMore = true }, onPress = onPress, onBackspace = onBackspace)
                SaveButton(state, onSave)
            }
        }
    }
}

/** 有分期、備註或退款可以設定時，鍵盤左下是「更多選項」，否則是「00」。 */
private val EntryUiState.hasMoreOptions: Boolean
    get() = showRefund || showInstallment || noteSuggestions.isNotEmpty()

/** 更多選項裡設定了東西時顯示一行摘要，免得看不到。 */
private fun extrasSummary(state: EntryUiState): String? = listOfNotNull(
    "退款".takeIf { state.refund },
    "分期 ${state.installmentMonths} 期".takeIf { state.installmentOn && state.showInstallment },
    state.note.takeIf { it.isNotBlank() }?.let { "備註：$it" },
).takeIf { it.isNotEmpty() }?.joinToString(" · ")

private val INSTALLMENT_MONTHS = listOf(3, 6, 12, 18, 24, 30)

// ---------------------------------------------------------------- 上方與可捲動區

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSelector(type: FlowType, onSelect: (FlowType) -> Unit, modifier: Modifier = Modifier) {
    val types = listOf(FlowType.EXPENSE, FlowType.INCOME, FlowType.TRANSFER)
    SingleChoiceSegmentedButtonRow(modifier) {
        types.forEachIndexed { index, t ->
            SegmentedButton(
                selected = type == t,
                onClick = { onSelect(t) },
                shape = SegmentedButtonDefaults.itemShape(index, types.size),
                label = { Text(t.label) },
            )
        }
    }
}

/** 3 天內到期或已過期還沒記下（R-DUE-07）；沒有就不顯示。 */
@Composable
private fun SoonPill(soon: List<DueRow>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val warn = MaterialTheme.warningColors
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = warn.warningContainer,
        contentColor = warn.onWarningContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = Spacing.lg, end = Spacing.md, top = Spacing.sm, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(Icons.Rounded.Event, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text("3 天內到期 ${soon.size} 筆", style = MaterialTheme.typography.titleSmall)
                Text(
                    soon.joinToString(" · ") { "${it.title} ${it.dateLabel}" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** 金額與預算提示。點了項目才有「本月還剩多少」（R-ENT-06）；收入、轉帳顯示帳戶。記下後金額淡出歸零。 */
@Composable
private fun AmountHero(state: EntryUiState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = state.amountText to state.savedTick,
            contentKey = { it.second },
            transitionSpec = {
                fadeIn(tween(Motion.MEDIUM2, easing = Motion.emphasizedDecelerate)) togetherWith
                    fadeOut(tween(Motion.SHORT4, easing = Motion.emphasizedAccelerate))
            },
            label = "amount",
        ) { (amountText, _) ->
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "金額 $amountText 元" },
            ) {
                Text(
                    "$",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(end = Spacing.xs, bottom = Spacing.sm),
                )
                Text(
                    amountText,
                    style = MaterialTheme.typography.displayMedium,
                    color = if (amountText != "0") colors.onSurface else colors.outline,
                )
            }
        }
        val line = if (state.showMethods) state.hint else listOf(state.selectedLine, state.hint).filter { it.isNotEmpty() }.joinToString(" · ")
        if (line.isNotEmpty()) {
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.hintWarning) MaterialTheme.warningColors.warning else colors.primary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 項目方塊，一列四個；最後一格是「更多／收起」。方塊上不顯示金額。 */
@Composable
private fun ItemGrid(state: EntryUiState, onSelect: (Long) -> Unit, onToggleShowAll: () -> Unit, modifier: Modifier = Modifier) {
    val cells: List<PlanItem?> = state.items + if (state.moreCount > 0 || state.showAll) listOf(null) else emptyList()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        cells.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.forEach { item ->
                    if (item == null) {
                        ItemTile(
                            label = if (state.showAll) "收起" else "更多 ${state.moreCount}",
                            icon = if (state.showAll) Icons.Rounded.ExpandLess else Icons.Rounded.MoreHoriz,
                            selected = false,
                            onClick = onToggleShowAll,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        ItemTile(
                            label = item.name,
                            icon = ItemIcons.of(item.name, item.type),
                            selected = item.id == state.selectedItemId,
                            onClick = { onSelect(item.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ItemTile(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        modifier = modifier.height(72.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterVertically),
        ) {
            Box(
                Modifier.size(32.dp).background(if (selected) colors.primary else colors.surfaceContainerHigh, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (selected) colors.onPrimary else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Spacing.xs))
        }
    }
}

// ---------------------------------------------------------------- 下方固定區

/**
 * 付款方式：M3 分段按鈕。本月沒規劃的方式字色較淡（R-ENT-06）。
 * 選了信用卡、而且設定要指定卡片時，那一格顯示卡名；再點一次跳出選卡選單（R-ACC-02）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MethodSelector(
    state: EntryUiState,
    onSelectMethod: (PaymentMethod) -> Unit,
    onSelectCard: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var cardMenu by remember { mutableStateOf(false) }
    val methods = PaymentMethod.entries
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        methods.forEachIndexed { index, method ->
            val selected = state.method == method
            val picksCard = method == PaymentMethod.CREDIT_CARD && selected && state.showCards
            val faint = method !in state.plannedMethods && !selected
            SegmentedButton(
                selected = selected,
                onClick = { if (picksCard) cardMenu = true else onSelectMethod(method) },
                shape = SegmentedButtonDefaults.itemShape(index, methods.size),
                colors = if (faint) {
                    SegmentedButtonDefaults.colors(inactiveContentColor = MaterialTheme.colorScheme.outline)
                } else {
                    SegmentedButtonDefaults.colors()
                },
                // 顯示卡名時空間不夠放勾勾，改用下拉箭頭表示「可以換」。
                icon = {
                    if (!picksCard) {
                        SegmentedButtonDefaults.Icon(active = selected) {
                            Icon(methodIcon(method), contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                modifier = Modifier.height(48.dp),
                label = {
                    if (picksCard) {
                        Box {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(state.cardLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "換卡片", modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = cardMenu, onDismissRequest = { cardMenu = false }) {
                                CardMenuItem("不指定", state.cardId == null) { cardMenu = false; onSelectCard(null) }
                                state.cards.forEach { card ->
                                    CardMenuItem(card.name, state.cardId == card.id) { cardMenu = false; onSelectCard(card.id) }
                                }
                            }
                        }
                    } else {
                        Text(method.label, maxLines = 1)
                    }
                },
            )
        }
    }
}

@Composable
private fun CardMenuItem(name: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(name) },
        onClick = onClick,
        leadingIcon = {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
            )
        },
        modifier = Modifier.semantics { stateDescription = if (selected) "已選" else "" },
    )
}


@Composable
private fun Keypad(showMore: Boolean, onMore: () -> Unit, onPress: (String) -> Unit, onBackspace: () -> Unit, modifier: Modifier = Modifier) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", if (showMore) KEY_MORE else "00", "0", KEY_BACK)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                row.forEach { key ->
                    Surface(
                        onClick = {
                            when (key) {
                                KEY_BACK -> onBackspace()
                                KEY_MORE -> onMore()
                                else -> onPress(key)
                            }
                        },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            when (key) {
                                KEY_BACK -> Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "刪除一位", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                KEY_MORE -> Icon(Icons.Rounded.Tune, contentDescription = "更多選項：分期、備註、退款", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                else -> Text(key, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val KEY_BACK = "back"
private const val KEY_MORE = "more"

/** 記下：按下後變成「✓ 已記下」約 1 秒並輕震一下（mobile-app-ui-design 的完成回饋）。 */
@Composable
private fun SaveButton(state: EntryUiState, onSave: () -> Unit, modifier: Modifier = Modifier) {
    var done by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state.savedTick) {
        if (state.savedTick > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            done = true
            delay(Motion.FEEDBACK_HOLD_MS)
            done = false
        }
    }
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (done) colors.primaryContainer else colors.primary,
        animationSpec = tween(Motion.MEDIUM2, easing = Motion.standard),
        label = "save-container",
    )
    Button(
        onClick = onSave,
        enabled = state.saveEnabled || done,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = if (done) colors.onPrimaryContainer else colors.onPrimary,
        ),
        modifier = modifier.fillMaxWidth().height(56.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        AnimatedContent(
            targetState = done,
            transitionSpec = {
                fadeIn(tween(Motion.MEDIUM2, easing = Motion.emphasizedDecelerate)) togetherWith
                    fadeOut(tween(Motion.SHORT4, easing = Motion.emphasizedAccelerate))
            },
            label = "save-label",
        ) { showDone ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    showDone -> {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text("已記下")
                    }
                    state.saveEnabled -> {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(state.saveLabel)
                    }
                    else -> Text(state.saveLabel)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 底部面板

/** 今天總覽（R-ENT-12）。 */
@Composable
private fun OverviewSheet(
    overview: EntryOverview,
    dues: List<DueRow>,
    onClose: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenDue: (String) -> Unit,
) {
    Sheet(onClose) {
        Text(overview.dateTitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("可以自由用的現金", style = MaterialTheme.typography.bodySmall)
                Text(overview.freeCash, style = MaterialTheme.typography.displayMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    SummaryFigure("帳戶現金", overview.liquid)
                    SummaryFigure("要留給卡費", overview.cardReserve)
                    SummaryFigure("今天已花", overview.todaySpent)
                }
            }
        }

        SectionHeader("今天記的", action = "全部明細", onAction = onOpenRecords)
        ListCard {
            if (overview.today.isEmpty()) EmptyLine("今天還沒記")
            overview.today.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow(
                    lead = { Icon(ItemIcons.of(row.itemName, row.type), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = row.itemName,
                    detail = row.detail,
                    trailing = row.amountText,
                )
            }
        }

        SectionHeader("本月到期")
        ListCard {
            if (dues.isEmpty()) EmptyLine("這個月的到期款項都記下了")
            dues.forEachIndexed { index, due ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DueListRow(due, onClick = { onOpenDue(due.key) })
            }
        }

        if (overview.remaining.isNotEmpty()) {
            SectionHeader("這個月還剩")
            ListCard {
                Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    overview.remaining.forEach { row -> RemainingBar(row) }
                }
            }
        }
    }
}

@Composable
private fun RemainingBar(row: RemainingRow) {
    val tone = if (row.low) MaterialTheme.warningColors.warning else MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "${row.name} 還剩 ${row.remainingText}" + if (row.low) "，快用完了" else ""
        },
    ) {
        Text(row.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(72.dp))
        LinearProgressIndicator(
            progress = { row.fraction },
            color = tone,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.md),
        )
        Text(row.remainingText, style = MaterialTheme.typography.titleSmall, color = if (row.low) tone else Color.Unspecified)
    }
}

/** 到期項目一列：左邊日期（已過的用錯誤色），右邊金額；點一下開記下面板。 */
@Composable
private fun DueListRow(due: DueRow, onClick: () -> Unit) {
    val dateColor = if (due.past) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(onClick = onClick, color = Color.Transparent) {
        ListRow(
            lead = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(due.day, style = MaterialTheme.typography.titleLarge, color = dateColor)
                    Text(due.dayNote, style = MaterialTheme.typography.bodySmall, color = if (due.past) dateColor else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            title = due.title,
            detail = due.dateLabel,
            trailing = due.amountText,
        )
    }
}

/** 3 天內到期的清單：點一筆開記下面板。 */
@Composable
private fun SoonSheet(soon: List<DueRow>, onClose: () -> Unit, onOpenDue: (String) -> Unit) {
    Sheet(onClose) {
        Text("3 天內到期", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        ListCard {
            soon.forEachIndexed { index, due ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DueListRow(due, onClick = { onOpenDue(due.key) })
            }
        }
    }
}

/** 更多選項：退款、分期（刷卡的支出）、備註。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreSheet(
    state: EntryUiState,
    onClose: () -> Unit,
    onToggleNote: (String) -> Unit,
    onToggleRefund: () -> Unit,
    onToggleInstallment: () -> Unit,
    onInstallmentMonths: (Int) -> Unit,
    onInstallmentFee: (InstallmentFee) -> Unit,
    onInstallmentFeeValue: (String) -> Unit,
) {
    Sheet(onClose) {
        Text("更多選項", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (state.showRefund) {
            SwitchRow("退款", state.refund, { onToggleRefund() }, detail = "退回原付款帳戶或卡片，減少這個項目本月花費")
        }
        if (state.showInstallment) {
            SwitchRow("分期", state.installmentOn, { onToggleInstallment() }, detail = if (state.installmentOn) "選期數與手續費" else "一次付清")
            if (state.installmentOn) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    INSTALLMENT_MONTHS.forEach { months ->
                        FilterChip(selected = state.installmentMonths == months, onClick = { onInstallmentMonths(months) }, label = { Text("$months 期") })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    InstallmentFee.entries.forEach { fee ->
                        FilterChip(selected = state.installmentFee == fee, onClick = { onInstallmentFee(fee) }, label = { Text(fee.label) })
                    }
                }
                if (state.installmentFee != InstallmentFee.NONE) {
                    OutlinedTextField(
                        value = state.installmentFeeValue,
                        onValueChange = onInstallmentFeeValue,
                        label = { Text(if (state.installmentFee == InstallmentFee.PER_PERIOD) "每期手續費（元）" else "費率（%）", style = MaterialTheme.typography.bodyMedium) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.installmentDescription?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.noteSuggestions.isNotEmpty()) {
            Text("備註", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                state.noteSuggestions.forEach { note ->
                    FilterChip(selected = state.note == note, onClick = { onToggleNote(note) }, label = { Text(note) })
                }
            }
        }
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("完成") }
    }
}

/**
 * 記下到期項目（R-DUE-03）：金額帶好，可以改；支出選支付方式（刷卡再選卡），轉帳、貸款、繳卡費選扣款帳戶，收入選入帳帳戶。
 * 繳卡費先選全額／最低／自己填（R-CARD-21），不預選；還沒選時「記下」停用。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DueSheet(
    dialog: DueDialog,
    onClose: () -> Unit,
    onAmount: (String) -> Unit,
    onMethod: (PaymentMethod) -> Unit,
    onCard: (Long?) -> Unit,
    onAccount: (Long) -> Unit,
    onPayMode: (CardPayMode) -> Unit,
    onUseDueDate: (Boolean) -> Unit,
    onRecord: () -> Unit,
    onSkip: () -> Unit,
) {
    val needsMode = dialog.payModes.isNotEmpty() && dialog.payMode == null
    Sheet(onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(dialog.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(dialog.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (dialog.payModes.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                dialog.payModes.forEach { (mode, amount) ->
                    PayModeCard(
                        label = if (mode == CardPayMode.FREE) "自己填" else mode.label,
                        value = when {
                            amount != null -> MoneyFormat.currency(amount)
                            mode == CardPayMode.MINIMUM -> "照帳單填"
                            else -> "輸入金額"
                        },
                        isAmount = amount != null,
                        selected = dialog.payMode == mode,
                        onClick = { onPayMode(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (dialog.amountEditable) {
            if (!needsMode) {
                OutlinedTextField(
                    value = dialog.amountText,
                    onValueChange = onAmount,
                    label = { Text(if (dialog.payModes.isNotEmpty()) "這次繳" else "金額", style = MaterialTheme.typography.bodyMedium) },
                    prefix = { Text("$", style = MaterialTheme.typography.titleLarge) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Text("金額 ${dialog.amountText}", style = MaterialTheme.typography.titleLarge)
        }
        if (dialog.choosesMethod) {
            FieldLabel("支付方式")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PaymentMethod.entries.forEach { method ->
                    FilterChip(selected = dialog.method == method, onClick = { onMethod(method) }, label = { Text(method.label) })
                }
            }
            val planned = dialog.plannedMethod
            if (planned != null && dialog.method != null && dialog.method != planned) {
                Text(
                    "計畫是${planned.label}，這次用${dialog.method.label}；一樣算在這個項目",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (dialog.showCards) {
            FieldLabel("卡片")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilterChip(selected = dialog.cardId == null, onClick = { onCard(null) }, label = { Text("不指定") })
                dialog.cards.forEach { card ->
                    FilterChip(selected = dialog.cardId == card.id, onClick = { onCard(card.id) }, label = { Text(card.name) })
                }
            }
        }
        if (dialog.choosesAccount) {
            FieldLabel(dialog.accountLabel)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                dialog.accounts.forEach { account ->
                    FilterChip(selected = dialog.accountId == account.id, onClick = { onAccount(account.id) }, label = { Text(account.name) })
                }
            }
        }
        dialog.dueDateLabel?.let { dueLabel ->
            FieldLabel("付款日")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilterChip(selected = !dialog.useDueDate, onClick = { onUseDueDate(false) }, label = { Text("今天") })
                FilterChip(selected = dialog.useDueDate, onClick = { onUseDueDate(true) }, label = { Text(dueLabel) })
            }
        }
        dialog.warning?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.warningColors.warning) }
        dialog.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        Button(onClick = onRecord, enabled = !needsMode, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            if (!needsMode) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(if (needsMode) "先選這一期怎麼繳" else dialog.recordLabel)
        }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("這個月沒有") }
    }
}

/** 繳款方式卡片（全額／最低／自己填），單選。 */
@Composable
private fun PayModeCard(label: String, value: String, isAmount: Boolean, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerLowest,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = modifier,
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                value,
                style = if (isAmount) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                color = if (isAmount || selected) Color.Unspecified else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- 沒有資料

@Composable
private fun EmptyState(onLoadSample: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
        Card {
            Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("還沒有帳戶與項目", style = MaterialTheme.typography.titleLarge)
                Text(
                    "記帳需要先有帳戶（錢包、銀行、信用卡）和預算項目。\n" +
                        "可以到「帳戶」頁新增，或在「計畫」頁匯入年度計畫 CSV。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onLoadSample, modifier = Modifier.fillMaxWidth()) {
                    Text("載入示意資料試用")
                }
                Text(
                    "示意資料是虛構的家庭帳，方便先試操作；之後可以在設定清空。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

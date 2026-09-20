package tw.myfsl.app.ui.entry

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import tw.myfsl.app.core.data.FinanceRepository
import tw.myfsl.app.core.domain.AccountSummaryCalculator
import tw.myfsl.app.core.domain.AmountInput
import tw.myfsl.app.core.domain.DueChoice
import tw.myfsl.app.core.domain.DueItem
import tw.myfsl.app.core.domain.DueItems
import tw.myfsl.app.core.domain.DueKind
import tw.myfsl.app.core.domain.EntryRules
import tw.myfsl.app.core.domain.InstallmentRules
import tw.myfsl.app.core.domain.RecordRules
import tw.myfsl.app.core.model.CardInstallment
import tw.myfsl.app.core.model.InstallmentFee
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.CardPayMode
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import tw.myfsl.app.ui.WriteGuard

/** 本月到期清單的一列（R-DUE）。 */
@Immutable
data class DueRow(
    val key: String,
    val title: String,
    val dateLabel: String,
    val amountText: String,
    /** 已經到期（今天或之前）。 */
    val reached: Boolean,
    /** 上個月以前就到期、還沒記下。 */
    val overdue: Boolean,
    val income: Boolean,
    /** 3 天內到期或已過期（R-DUE-07）：記帳畫面上方只提示這些。 */
    val soon: Boolean = false,
    /** 總覽清單左邊的日期：日、與「已過／今天／週一」。 */
    val day: String = "",
    val dayNote: String = "",
    /** 到期日已經過了（總覽用錯誤色標日期）。 */
    val past: Boolean = false,
)

/** 今天總覽（R-ENT-12）：右上角按鈕打開。 */
@Immutable
data class EntryOverview(
    val dateTitle: String = "",
    val freeCash: String = "",
    val liquid: String = "",
    val cardReserve: String = "",
    val todaySpent: String = "",
    val today: List<TodayRow> = emptyList(),
    val remaining: List<RemainingRow> = emptyList(),
)

@Immutable
data class TodayRow(val itemName: String, val type: FlowType, val detail: String, val amountText: String)

@Immutable
data class RemainingRow(
    val name: String,
    val remainingText: String,
    /** 還剩的比例 0–1，畫進度條用。 */
    val fraction: Float,
    /** 剩不到兩成：變橘色提醒。 */
    val low: Boolean,
)

/** 點到期項目後的記下視窗。 */
@Immutable
data class DueDialog(
    val key: String,
    val title: String,
    val subtitle: String,
    val amountText: String,
    val amountEditable: Boolean,
    val choosesMethod: Boolean,
    val method: PaymentMethod?,
    /** 計畫的支付方式；選別的時提示。 */
    val plannedMethod: PaymentMethod?,
    val showCards: Boolean,
    val cards: List<Account>,
    val cardId: Long?,
    val choosesAccount: Boolean,
    val accountLabel: String,
    val accounts: List<Account>,
    val accountId: Long?,
    val error: String?,
    val recordLabel: String,
    /** 到期日在今天以前時可以選付款日：今天，或到期日當天已經付過（F02）。 */
    val dueDateLabel: String?,
    val useDueDate: Boolean,
    /** 繳卡費：三種繳款方式與各自的金額（R-CARD-21；null = 自己輸入）；其他項目為空。不預選。 */
    val payModes: List<Pair<CardPayMode, Money?>> = emptyList(),
    val payMode: CardPayMode? = null,
    /** 不擋存檔的提醒，例如繳得比帳單上的最低應繳少（R-CARD-24）。 */
    val warning: String? = null,
)

/** 記帳畫面的狀態；文字與預設值都來自 core.domain 的規則。 */
@Immutable
data class EntryUiState(
    val loading: Boolean = true,
    val empty: Boolean = false,
    val type: FlowType = FlowType.EXPENSE,
    /** 常用項目（最近用過的、本月有計畫的）。 */
    val items: List<PlanItem> = emptyList(),
    /** 還有幾個項目收在「更多」裡。 */
    val moreCount: Int = 0,
    val showAll: Boolean = false,
    val selectedItemId: Long? = null,
    val method: PaymentMethod? = null,
    val showMethods: Boolean = false,
    /** 本月有計畫金額的支付方式（用來標示沒規劃的）。 */
    val plannedMethods: Set<PaymentMethod> = emptySet(),
    val showCards: Boolean = false,
    val cards: List<Account> = emptyList(),
    val cardId: Long? = null,
    val amountInput: String = "",
    val note: String = "",
    val noteSuggestions: List<String> = emptyList(),
    val selectedLine: String = "",
    val hint: String = "",
    val hintWarning: Boolean = false,
    val message: String? = null,
    val canUndo: Boolean = false,
    // ---- 分期（付款為信用卡時） ----
    val showInstallment: Boolean = false,
    val installmentOn: Boolean = false,
    val installmentMonths: Int = 12,
    val installmentFee: InstallmentFee = InstallmentFee.NONE,
    val installmentFeeValue: String = "",
    val installmentDescription: String? = null,
    /** 退款（R-ENT-13）：存成負數，退回原付款帳戶或卡片。 */
    val refund: Boolean = false,
    val showRefund: Boolean = false,
    /** 補登時可能就是之前對帳補的漏記差額（R-REC-03），要問使用者。 */
    val missedPrompt: String? = null,
    /** 本月到期、還沒記下的項目（R-DUE）。 */
    val dues: List<DueRow> = emptyList(),
    val dueDialog: DueDialog? = null,
    val overview: EntryOverview = EntryOverview(),
    /** 自己記下一筆就加一：畫面用來播放「已記下」回饋。 */
    val savedTick: Int = 0,
) {
    /** 付款方式「信用卡」那一格顯示的卡名。 */
    val cardLabel: String get() = cards.firstOrNull { it.id == cardId }?.name ?: "不指定"
    /** 3 天內到期或已過期、還沒記下的（R-DUE-07）。 */
    val soonDues: List<DueRow> get() = dues.filter { it.soon }
    val amountText: String get() = AmountInput.display(amountInput)
    val amount: Money get() = AmountInput.value(amountInput)
    val saveEnabled: Boolean get() = amount > 0
    val saveLabel: String
        get() = when {
            amount <= 0 -> "輸入金額後記下"
            refund -> "記下退款 ${MoneyFormat.currency(amount)}"
            else -> "記下 ${MoneyFormat.currency(amount)}"
        }
}

@HiltViewModel
class EntryViewModel @Inject constructor(
    private val repository: FinanceRepository,
) : ViewModel() {

    private data class Selection(
        val type: FlowType = FlowType.EXPENSE,
        val itemId: Long? = null,
        val method: PaymentMethod? = null,
        val methodTouched: Boolean = false,
        val cardId: Long? = null,
        val cardTouched: Boolean = false,
        val amount: String = "",
        val note: String = "",
        val showAll: Boolean = false,
        val message: String? = null,
        val lastSavedId: Long? = null,
        val lastInstallmentId: Long? = null,
        val installmentOn: Boolean = false,
        val installmentMonths: Int = 12,
        val installmentFee: InstallmentFee = InstallmentFee.NONE,
        val installmentFeeValue: String = "",
        val refund: Boolean = false,
        /** 等使用者回答的補登：(漏記差額, 要記的這筆, 提示文字)。 */
        val pendingMissed: Triple<LedgerEntry, LedgerEntry, String>? = null,
        /** 打開中的到期項目；欄位為 null 時用預設值。 */
        val due: DueSelection? = null,
        /** 使用者選項目、付款方式、卡片時畫面顯示的資料世代；還沒選過為 NO_GENERATION（F11）。 */
        val generation: Long = FinanceSnapshot.NO_GENERATION,
        /** 上一筆記下時的資料世代（復原、補登提示用）。 */
        val lastGeneration: Long = FinanceSnapshot.NO_GENERATION,
        /** 自己記下的次數（「已記下」回饋）。 */
        val savedCount: Int = 0,
    )

    private data class DueSelection(
        val key: String,
        /** 打開這個到期項目時畫面的資料世代。 */
        val generation: Long,
        val amount: String? = null,
        val method: PaymentMethod? = null,
        val cardId: Long? = null,
        val cardTouched: Boolean = false,
        val accountId: Long? = null,
        val useDueDate: Boolean = false,
        /** 繳卡費這一期臨時換的繳款方式；null 為卡片預設。 */
        val payMode: CardPayMode? = null,
        val error: String? = null,
    )

    private val selection = MutableStateFlow(Selection())

    /** 畫面正在顯示的資料世代（F11）：選擇時記下，寫入時帶著，資料換過就會被拒絕。 */
    @Volatile private var shownGeneration = FinanceSnapshot.NO_GENERATION

    /** 寫入要帶的世代：使用者選過東西就用選的時候的世代，否則用這次讀到的快照。 */
    private fun generationFor(sel: Selection, snapshot: FinanceSnapshot) =
        if (sel.generation != FinanceSnapshot.NO_GENERATION) sel.generation else snapshot.generation

    val state: StateFlow<EntryUiState> = combine(repository.snapshot, selection, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntryUiState())

    private fun build(snapshot: FinanceSnapshot, sel: Selection): EntryUiState {
        shownGeneration = snapshot.generation
        val all = snapshot.activeItems.filter { it.type == sel.type }.sortedBy { it.sortOrder }
        if (snapshot.activeItems.isEmpty()) {
            return EntryUiState(loading = false, empty = true, message = sel.message)
        }
        val dues = DueItems.list(snapshot)
        val common = EntryRules.commonItems(snapshot, sel.type)
        val item = EntryRules.currentItem(snapshot, sel.type, sel.itemId)
            ?: return EntryUiState(loading = false, empty = true, message = sel.message)
        val shown = if (sel.showAll) all else (common + item).distinct()
        val items = all.filter { it in shown }

        val method = if (sel.methodTouched) sel.method else EntryRules.defaultMethod(snapshot, item)
        val cards = snapshot.activeAccounts.filter { it.kind == AccountKind.CREDIT_CARD }
        val pickCard = snapshot.settings.pickCard
        val cardId = if (sel.cardTouched) sel.cardId else EntryRules.defaultCard(snapshot, item)
        val showCards = item.type == FlowType.EXPENSE && method == PaymentMethod.CREDIT_CARD && pickCard
        val hint = EntryRules.budgetHint(snapshot, item, method, AmountInput.value(sel.amount))

        val cardName = cards.firstOrNull { it.id == cardId }?.name ?: "不指定"
        val selectedLine = when (item.type) {
            FlowType.EXPENSE -> buildString {
                append(item.name)
                method?.let { append(" · ${it.label}") }
                if (showCards) append(" · $cardName")
            }

            FlowType.INCOME -> "${item.name} · 入帳：${snapshot.account(item.accountId)?.name ?: "未設定"}"
            FlowType.TRANSFER -> "${item.name} · ${snapshot.account(item.accountId)?.name ?: "未設定"} → " +
                (snapshot.account(item.toAccountId)?.name ?: "未設定")
        }

        return EntryUiState(
            loading = false,
            empty = false,
            type = sel.type,
            items = items,
            moreCount = (all.size - items.size).coerceAtLeast(0),
            showAll = sel.showAll,
            selectedItemId = item.id,
            method = method,
            showMethods = item.type == FlowType.EXPENSE,
            plannedMethods = setOfNotNull(item.method),
            showCards = showCards,
            cards = cards,
            cardId = cardId,
            amountInput = sel.amount,
            note = sel.note,
            noteSuggestions = suggestions(snapshot, item),
            selectedLine = selectedLine,
            hint = EntryRules.hintText(hint),
            hintWarning = EntryRules.isWarning(hint),
            message = sel.message,
            canUndo = sel.lastSavedId != null || sel.lastInstallmentId != null,
            showInstallment = item.type == FlowType.EXPENSE && method == PaymentMethod.CREDIT_CARD,
            installmentOn = sel.installmentOn,
            installmentMonths = sel.installmentMonths,
            installmentFee = sel.installmentFee,
            installmentFeeValue = sel.installmentFeeValue,
            installmentDescription = if (sel.installmentOn && AmountInput.value(sel.amount) > 0) {
                InstallmentRules.description(draftInstallment(snapshot, item, cardId, sel))
            } else {
                null
            },
            refund = sel.refund && item.type == FlowType.EXPENSE,
            showRefund = item.type == FlowType.EXPENSE,
            missedPrompt = sel.pendingMissed?.third,
            dues = dues.map { dueRow(snapshot, it) },
            dueDialog = sel.due?.let { due -> dues.firstOrNull { it.key == due.key }?.let { dueDialog(snapshot, it, due) } },
            overview = overviewFor(snapshot),
            savedTick = sel.savedCount,
        )
    }

    // ---- 今天總覽（R-ENT-12） ----

    /** 總覽只跟資料有關：同一份快照重用，按鍵盤時不用重算帳戶與預算。 */
    private var overviewCache: Pair<FinanceSnapshot, EntryOverview>? = null

    private fun overviewFor(snapshot: FinanceSnapshot): EntryOverview =
        overviewCache?.takeIf { it.first === snapshot }?.second
            ?: overview(snapshot).also { overviewCache = snapshot to it }

    private fun overview(snapshot: FinanceSnapshot): EntryOverview {
        val today = snapshot.today
        val accounts = AccountSummaryCalculator.overview(snapshot)
        return EntryOverview(
            dateTitle = "${today.monthValue}月${today.dayOfMonth}日 ${weekday(today)}",
            freeCash = MoneyFormat.currency(accounts.freeCash),
            liquid = MoneyFormat.currency(accounts.liquid),
            cardReserve = MoneyFormat.currency(accounts.cardReserve),
            todaySpent = MoneyFormat.currency(EntryRules.todaySpent(snapshot)),
            today = EntryRules.todayEntries(snapshot).map { entry ->
                val name = snapshot.items.firstOrNull { it.id == entry.itemId }?.name ?: entry.type.label
                val method = entry.method
                val via = when {
                    method == PaymentMethod.CREDIT_CARD -> listOfNotNull(method.label, snapshot.account(entry.accountId)?.name)
                    method != null -> listOf(method.label)
                    else -> listOfNotNull(snapshot.account(entry.accountId)?.name)
                }
                TodayRow(
                    itemName = name,
                    type = entry.type,
                    detail = (via + listOf(entry.note.trim()).filter { it.isNotEmpty() }).joinToString(" · "),
                    amountText = (if (entry.type == FlowType.INCOME) "+" else "") + MoneyFormat.currency(entry.amount),
                )
            },
            remaining = EntryRules.monthRemaining(snapshot).map { budget ->
                val ratio = (budget.remaining.toDouble() / budget.planned).coerceIn(0.0, 1.0)
                RemainingRow(
                    name = budget.item.name,
                    remainingText = MoneyFormat.currency(budget.remaining),
                    fraction = ratio.toFloat(),
                    low = ratio < 0.2,
                )
            },
        )
    }

    private fun weekday(date: java.time.LocalDate) = "週" + "一二三四五六日"[date.dayOfWeek.value - 1]

    // ---- 本月到期（R-DUE） ----

    private fun dueRow(snapshot: FinanceSnapshot, due: DueItem): DueRow {
        val today = snapshot.today
        val date = "${due.date.monthValue}/${due.date.dayOfMonth}"
        return DueRow(
            key = due.key,
            title = due.title,
            dateLabel = when {
                due.isOverdue(today) -> "$date 逾期"
                due.isDue(today) -> "$date 到期"
                else -> date
            },
            amountText = (if (due.isIncome) "+" else "") + MoneyFormat.currency(due.amount),
            reached = due.isDue(today),
            overdue = due.isOverdue(today),
            income = due.isIncome,
            soon = due.isSoon(today),
            day = due.date.dayOfMonth.toString(),
            past = due.date.isBefore(today),
            dayNote = when {
                due.date.isBefore(today) -> "已過"
                due.date == today -> "今天"
                else -> weekday(due.date)
            },
        )
    }

    private fun choiceFor(snapshot: FinanceSnapshot, due: DueItem, sel: DueSelection): DueChoice {
        val default = DueItems.defaultChoice(snapshot, due)
        val method = sel.method ?: default.method
        val cardId = when {
            method != PaymentMethod.CREDIT_CARD || !snapshot.settings.pickCard -> null
            sel.cardTouched -> sel.cardId
            default.method == PaymentMethod.CREDIT_CARD -> default.cardId
            else -> snapshot.defaultCardId
        }
        val payMode = sel.payMode
        val options = due.payOptions
        val amount = when {
            !due.amountEditable -> due.amount
            sel.amount != null -> MoneyFormat.parse(sel.amount) ?: 0L
            // 繳卡費：不預選；選了全額或最低才帶入金額，自由與還沒輸入的最低由使用者填（R-CARD-21）
            options != null -> when (payMode) {
                CardPayMode.FULL -> options.full
                CardPayMode.MINIMUM -> options.minimum ?: 0L
                CardPayMode.FREE, null -> 0L
            }
            else -> default.amount
        }
        val date = if (sel.useDueDate && due.date.isBefore(snapshot.today)) due.date else null
        return DueChoice(amount = amount, method = method, accountId = sel.accountId ?: default.accountId, cardId = cardId, date = date, payMode = payMode)
    }

    private fun dueDialog(snapshot: FinanceSnapshot, due: DueItem, sel: DueSelection): DueDialog {
        val choice = choiceFor(snapshot, due, sel)
        val date = "${due.date.monthValue}/${due.date.dayOfMonth}"
        val detail = when (due.kind) {
            DueKind.LOAN -> {
                val interest = due.entries.firstOrNull { it.type == FlowType.EXPENSE }?.amount ?: 0L
                "本金 ${MoneyFormat.currency(due.amount - interest)} ＋ 利息 ${MoneyFormat.currency(interest)}；改金額時利息不變"
            }
            DueKind.CARD_INTEREST -> "上一期帳單沒繳清的部分 × 循環年利率 ÷ 12 估算；輸入帳單後以帳單為準"
            DueKind.CARD_PAYMENT -> {
                val statement = due.statementDate?.let { "${it.monthValue}/${it.dayOfMonth} 結帳" }.orEmpty()
                val minimum = due.payOptions?.minimum?.let { "，帳單最低 ${MoneyFormat.currency(it)}" } ?: "；還沒輸入帳單的最低應繳"
                "本期帳單還要繳 ${MoneyFormat.currency(due.payOptions?.full ?: due.amount)}（$statement）$minimum\n請選這一期怎麼繳，金額可以改"
            }
            DueKind.INSTALLMENT -> {
                val fee = due.entries.firstOrNull { it.postingKey?.startsWith("instfee:") == true }?.amount ?: 0L
                "本金 ${MoneyFormat.currency(due.amount - fee)}" + if (fee > 0) " ＋ 手續費 ${MoneyFormat.currency(fee)}" else ""
            }
            DueKind.PLAN -> due.item?.let { item ->
                val planned = snapshot.planAmount(tw.myfsl.app.core.model.PlanLine(item.id), due.date.year, due.date.monthValue)
                "本月計畫 ${MoneyFormat.currency(planned)}"
            }.orEmpty()
        }
        val whenText = when {
            due.date.isBefore(snapshot.today) -> "$date 到期；付款日預設今天，若到期日當天就付了請選「到期日」"
            due.isDue(snapshot.today) -> "$date 到期"
            else -> "$date 到期；提早記下時日期用今天"
        }
        return DueDialog(
            key = due.key,
            title = due.title,
            subtitle = listOf(whenText, detail).filter { it.isNotEmpty() }.joinToString("\n"),
            amountText = when {
                !due.amountEditable -> MoneyFormat.currency(due.amount)
                sel.amount != null -> sel.amount
                due.kind == DueKind.CARD_PAYMENT -> choice.amount.takeIf { it > 0 }?.toString().orEmpty()
                else -> choice.amount.toString()
            },
            amountEditable = due.amountEditable,
            choosesMethod = due.choosesMethod,
            method = choice.method,
            plannedMethod = due.method,
            showCards = due.choosesMethod && choice.method == PaymentMethod.CREDIT_CARD && snapshot.settings.pickCard,
            cards = snapshot.activeCards,
            cardId = choice.cardId,
            choosesAccount = due.choosesAccount,
            accountLabel = if (due.isIncome) "入帳帳戶" else "扣款帳戶",
            accounts = snapshot.activeAccounts.filter { it.kind.isLiquid },
            accountId = choice.accountId,
            error = sel.error,
            recordLabel = if (choice.amount > 0) "記下 ${MoneyFormat.currency(choice.amount)}" else "記下",
            dueDateLabel = if (due.date.isBefore(snapshot.today)) "到期日 $date" else null,
            useDueDate = choice.date != null,
            payModes = due.payOptions?.let { options ->
                listOf(CardPayMode.FULL to options.full, CardPayMode.MINIMUM to options.minimum, CardPayMode.FREE to null)
            }.orEmpty(),
            payMode = choice.payMode,
            warning = DueItems.warning(snapshot, due, choice),
        )
    }

    fun openDue(key: String) = selection.update { it.copy(due = DueSelection(key, shownGeneration)) }

    fun closeDue() = selection.update { it.copy(due = null) }

    fun setDueAmount(text: String) = selection.update { s -> s.copy(due = s.due?.copy(amount = text.filter(Char::isDigit).take(9), error = null)) }

    fun setDueMethod(method: PaymentMethod) = selection.update { s -> s.copy(due = s.due?.copy(method = method, cardTouched = false, error = null)) }

    fun setDueCard(id: Long?) = selection.update { s -> s.copy(due = s.due?.copy(cardId = id, cardTouched = true, error = null)) }

    fun setDueUseDueDate(use: Boolean) = selection.update { s -> s.copy(due = s.due?.copy(useDueDate = use, error = null)) }

    fun setDueAccount(id: Long) = selection.update { s -> s.copy(due = s.due?.copy(accountId = id, error = null)) }

    /** 繳卡費這一期臨時換繳款方式：金額改成那個方式的建議金額（R-CARD-21）。 */
    fun setDuePayMode(mode: CardPayMode) = selection.update { s -> s.copy(due = s.due?.copy(payMode = mode, amount = null, error = null)) }

    /** 記下打開中的到期項目。 */
    fun recordDue() {
        val sel = selection.value.due ?: return
        viewModelScope.launch(WriteGuard) {
            val snapshot = repository.snapshot.first()
            val due = DueItems.list(snapshot).firstOrNull { it.key == sel.key }
            if (due == null) {
                selection.update { it.copy(due = null, message = "這筆已經記過了") }
                return@launch
            }
            val choice = choiceFor(snapshot, due, sel)
            val error = DueItems.validate(snapshot, due, choice)
            if (error != null) {
                selection.update { s -> s.copy(due = s.due?.copy(error = error)) }
                return@launch
            }
            val id = repository.recordDue(DueItems.record(snapshot, due, choice), sel.generation)
            selection.update {
                it.copy(
                    due = null,
                    lastGeneration = sel.generation,
                    message = if (id != null) "已記下 ${due.title} ${MoneyFormat.currency(choice.amount)}" else "這筆已經記過了",
                    lastSavedId = id,
                    lastInstallmentId = null,
                )
            }
        }
    }

    /** 這個月沒有這筆：不記帳，之後不再列出。 */
    fun skipDue() {
        val sel = selection.value.due ?: return
        viewModelScope.launch(WriteGuard) {
            repository.skipDue(sel.key, sel.generation)
            selection.update { it.copy(due = null, message = "已略過，這個月不再列出", lastSavedId = null, lastInstallmentId = null) }
        }
    }

    private fun draftInstallment(snapshot: FinanceSnapshot, item: PlanItem, cardId: Long?, sel: Selection): CardInstallment {
        val card = snapshot.account(cardId)
        val payDay = card?.paymentDueDay
        return CardInstallment(
            cardAccountId = if (snapshot.settings.pickCard) cardId else null,
            itemId = item.id,
            purchaseDate = snapshot.today,
            amount = AmountInput.value(sel.amount),
            months = sel.installmentMonths,
            fee = sel.installmentFee,
            feeValue = sel.installmentFeeValue.trim().removeSuffix("%").replace(",", "").toDoubleOrNull() ?: 0.0,
            firstPeriodIndex = InstallmentRules.firstPeriodIndex(snapshot.today, payDay),
            note = sel.note.trim(),
        )
    }

    fun toggleInstallment() = selection.update { it.copy(installmentOn = !it.installmentOn, refund = false) }

    fun toggleRefund() = selection.update { it.copy(refund = !it.refund, installmentOn = false) }

    fun setInstallmentMonths(months: Int) = selection.update { it.copy(installmentMonths = months) }

    fun setInstallmentFee(fee: InstallmentFee) = selection.update { it.copy(installmentFee = fee) }

    fun setInstallmentFeeValue(value: String) = selection.update { it.copy(installmentFeeValue = value) }

    /** 備註建議：這個項目最近用過的備註。 */
    private fun suggestions(snapshot: FinanceSnapshot, item: PlanItem): List<String> =
        snapshot.ledger
            .filter { it.itemId == item.id && it.note.isNotBlank() }
            .sortedWith(compareByDescending<tw.myfsl.app.core.model.LedgerEntry> { it.date }.thenByDescending { it.id })
            .map { it.note }
            .distinct()
            .take(4)

    fun toggleShowAll() = selection.update { it.copy(showAll = !it.showAll) }

    fun selectType(type: FlowType) = selection.update {
        it.copy(
            type = type, itemId = null, method = null, methodTouched = false,
            cardId = null, cardTouched = false, note = "", showAll = false, generation = shownGeneration,
        )
    }

    fun selectItem(id: Long) = selection.update {
        it.copy(itemId = id, method = null, methodTouched = false, cardId = null, cardTouched = false, note = "", generation = shownGeneration)
    }

    fun selectMethod(method: PaymentMethod) = selection.update {
        if (it.methodTouched && it.method == method) {
            it
        } else {
            it.copy(method = method, methodTouched = true, cardId = null, cardTouched = false, generation = shownGeneration)
        }
    }

    fun selectCard(id: Long?) = selection.update { it.copy(cardId = id, cardTouched = true, generation = shownGeneration) }

    fun press(key: String) = selection.update { it.copy(amount = AmountInput.press(it.amount, key)) }

    fun backspace() = selection.update { it.copy(amount = AmountInput.backspace(it.amount)) }

    fun toggleNote(note: String) = selection.update { it.copy(note = if (it.note == note) "" else note) }

    fun save() {
        val sel = selection.value
        viewModelScope.launch(WriteGuard) {
            val snapshot = repository.snapshot.first()
            val generation = generationFor(sel, snapshot)
            // 和畫面顯示的是同一個項目（R-ENT-02）
            val item = EntryRules.currentItem(snapshot, sel.type, sel.itemId) ?: return@launch
            val method = if (sel.methodTouched) sel.method else EntryRules.defaultMethod(snapshot, item)
            val cardId = if (sel.cardTouched) sel.cardId else EntryRules.defaultCard(snapshot, item)
            val refund = sel.refund && item.type == FlowType.EXPENSE
            val entry = EntryRules.buildEntry(snapshot, item, method, cardId, sel.amount, sel.note, refund = refund) ?: return@launch
            val message = if (refund) {
                "已記下退款 ${sel.note.trim().ifEmpty { item.name }} ${MoneyFormat.currency(-entry.amount)}"
            } else {
                EntryRules.savedMessage(snapshot, item, method, entry.amount, sel.note)
            }
            // 補登的單據可能就是之前對帳補的漏記差額：先問，避免同一筆算兩次（R-REC-03）。
            if (!refund && !sel.installmentOn && item.type == FlowType.EXPENSE) {
                RecordRules.missedMatch(snapshot, item.id, entry.method, snapshot.today)?.let { missed ->
                    val prompt = "${missed.date.monthValue}/${missed.date.dayOfMonth} 對帳時補過「${item.name}・${entry.method?.label.orEmpty()}」" +
                        "漏記 ${MoneyFormat.currency(missed.amount)}。這筆是不是那時漏記的？"
                    selection.update { it.copy(pendingMissed = Triple(missed, entry, prompt), lastGeneration = generation) }
                    return@launch
                }
            }
            if (sel.installmentOn && method == PaymentMethod.CREDIT_CARD && item.type == FlowType.EXPENSE) {
                val installment = draftInstallment(snapshot, item, cardId, sel)
                val installmentId = repository.addInstallmentPurchase(entry, installment, generation)
                selection.update {
                    it.copy(
                        lastGeneration = generation,
                        amount = "", note = "", installmentOn = false,
                        message = message + InstallmentRules.savedSuffix(installment),
                        lastSavedId = null, lastInstallmentId = installmentId, savedCount = it.savedCount + 1, itemId = item.id,
                    )
                }
                return@launch
            }
            val id = repository.addLedgerEntry(entry, generation)
            selection.update {
                it.copy(
                    amount = "", note = "", refund = false, message = message, lastSavedId = id, lastInstallmentId = null,
                    lastGeneration = generation, savedCount = it.savedCount + 1, itemId = item.id,
                )
            }
        }
    }

    /** 回答補登提示：是 → 用這筆明細取代漏記差額；不是 → 當成新的一筆。 */
    fun answerMissed(replace: Boolean) {
        val (missed, entry, _) = selection.value.pendingMissed ?: return
        val generation = selection.value.lastGeneration
        viewModelScope.launch(WriteGuard) {
            if (replace) {
                repository.replaceMissed(missed.id, entry, generation)
                selection.update {
                    it.copy(amount = "", note = "", pendingMissed = null, lastSavedId = null, lastInstallmentId = null,
                        message = "已用這筆明細取代漏記差額，餘額不會重複扣", savedCount = it.savedCount + 1)
                }
            } else {
                val id = repository.addLedgerEntry(entry, generation)
                selection.update {
                    it.copy(amount = "", note = "", pendingMissed = null, lastSavedId = id, lastInstallmentId = null,
                        message = "已記下 ${MoneyFormat.currency(entry.amount)}（另外一筆，不影響之前的漏記差額）", savedCount = it.savedCount + 1)
                }
            }
        }
    }

    fun cancelMissed() = selection.update { it.copy(pendingMissed = null) }

    fun undo() {
        val sel = selection.value
        viewModelScope.launch(WriteGuard) {
            sel.lastSavedId?.let { repository.deleteLedgerEntry(it, sel.lastGeneration) }
            sel.lastInstallmentId?.let { repository.deleteInstallment(it, sel.lastGeneration) }
            selection.update { it.copy(message = null, lastSavedId = null, lastInstallmentId = null) }
        }
    }

    fun dismissMessage() = selection.update { it.copy(message = null, lastSavedId = null, lastInstallmentId = null) }

    /** 還沒有任何資料時，載入示意資料試用。 */
    fun loadSample() {
        viewModelScope.launch(WriteGuard) {
            repository.installSample()
            selection.value = Selection(message = "已載入示意資料")
        }
    }
}

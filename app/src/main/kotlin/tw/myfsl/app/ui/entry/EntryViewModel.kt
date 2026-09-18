package tw.myfsl.app.ui.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import tw.myfsl.app.core.data.FinanceRepository
import tw.myfsl.app.core.domain.AmountInput
import tw.myfsl.app.core.domain.BudgetProgressCalculator
import tw.myfsl.app.core.domain.EntryRules
import tw.myfsl.app.core.domain.InstallmentRules
import tw.myfsl.app.core.model.CardInstallment
import tw.myfsl.app.core.model.InstallmentFee
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.LedgerEntry
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

/** 記帳畫面的狀態；文字與預設值都來自 core.domain 的規則。 */
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
    val todayStrip: String = "",
    val groupAllowance: String? = null,
    val message: String? = null,
    val canUndo: Boolean = false,
    // ---- 分期（付款為信用卡時） ----
    val showInstallment: Boolean = false,
    val installmentOn: Boolean = false,
    val installmentMonths: Int = 12,
    val installmentFee: InstallmentFee = InstallmentFee.NONE,
    val installmentFeeValue: String = "",
    val installmentDescription: String? = null,
) {
    val amountText: String get() = AmountInput.display(amountInput)
    val amount: Money get() = AmountInput.value(amountInput)
    val saveEnabled: Boolean get() = amount > 0
    val saveLabel: String get() = if (amount > 0) "記下 ${MoneyFormat.currency(amount)}" else "輸入金額後記下"
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
    )

    private val selection = MutableStateFlow(Selection())

    val state: StateFlow<EntryUiState> = combine(repository.snapshot, selection, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntryUiState())

    private fun build(snapshot: FinanceSnapshot, sel: Selection): EntryUiState {
        val all = snapshot.activeItems.filter { it.type == sel.type }.sortedBy { it.sortOrder }
        if (snapshot.activeItems.isEmpty()) {
            return EntryUiState(loading = false, empty = true, message = sel.message)
        }
        val common = commonItems(snapshot, all)
        val item = all.firstOrNull { it.id == sel.itemId } ?: common.firstOrNull() ?: all.firstOrNull()
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

        val progress = BudgetProgressCalculator.forMonth(snapshot)
        val groupName = snapshot.group(item.groupId)?.name
        val allowance = progress.filter { it.groupName == groupName }.sumOf { it.dailyAllowance ?: 0L }

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
            plannedMethods = snapshot.plannedMethods(item.id, snapshot.today.year, snapshot.today.monthValue).toSet(),
            showCards = showCards,
            cards = cards,
            cardId = cardId,
            amountInput = sel.amount,
            note = sel.note,
            noteSuggestions = suggestions(snapshot, item),
            selectedLine = selectedLine,
            hint = EntryRules.hintText(hint),
            hintWarning = EntryRules.isWarning(hint),
            todayStrip = EntryRules.todayStrip(snapshot),
            groupAllowance = if (allowance > 0 && groupName != null) {
                "$groupName 每日可用 ${MoneyFormat.currency(allowance)}"
            } else {
                null
            },
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
        )
    }

    private fun draftInstallment(snapshot: FinanceSnapshot, item: PlanItem, cardId: Long?, sel: Selection): CardInstallment {
        val card = snapshot.account(cardId)
        val payDay = card?.card?.payDay ?: card?.paymentDueDay
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

    fun toggleInstallment() = selection.update { it.copy(installmentOn = !it.installmentOn) }

    fun setInstallmentMonths(months: Int) = selection.update { it.copy(installmentMonths = months) }

    fun setInstallmentFee(fee: InstallmentFee) = selection.update { it.copy(installmentFee = fee) }

    fun setInstallmentFeeValue(value: String) = selection.update { it.copy(installmentFeeValue = value) }

    /**
     * 常用項目：最近自己記過的（最多 5 個），加上本月有計畫金額的項目，最多 8 個。
     * 其餘收在「更多」裡，避免一打開就擠滿畫面。
     */
    private fun commonItems(snapshot: FinanceSnapshot, all: List<PlanItem>): List<PlanItem> {
        val recent = snapshot.ledger
            .asSequence()
            .filter { it.source == EntrySource.MANUAL && it.itemId != null }
            .sortedWith(compareByDescending<LedgerEntry> { it.date }.thenByDescending { it.id })
            .mapNotNull { entry -> all.firstOrNull { it.id == entry.itemId } }
            .distinct()
            .take(5)
            .toList()
        val planned = all.filter { item ->
            snapshot.plannedMethods(item.id, snapshot.today.year, snapshot.today.monthValue).isNotEmpty()
        }
        return (recent + planned).distinct().take(8)
    }

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
            cardId = null, cardTouched = false, note = "", showAll = false,
        )
    }

    fun selectItem(id: Long) = selection.update {
        it.copy(itemId = id, method = null, methodTouched = false, cardId = null, cardTouched = false, note = "")
    }

    fun selectMethod(method: PaymentMethod) = selection.update {
        if (it.methodTouched && it.method == method) it else it.copy(method = method, methodTouched = true, cardId = null, cardTouched = false)
    }

    fun selectCard(id: Long?) = selection.update { it.copy(cardId = id, cardTouched = true) }

    fun press(key: String) = selection.update { it.copy(amount = AmountInput.press(it.amount, key)) }

    fun backspace() = selection.update { it.copy(amount = AmountInput.backspace(it.amount)) }

    fun toggleNote(note: String) = selection.update { it.copy(note = if (it.note == note) "" else note) }

    fun save() {
        val sel = selection.value
        viewModelScope.launch {
            val snapshot = repository.snapshot.first()
            val items = snapshot.activeItems.filter { it.type == sel.type }.sortedBy { it.sortOrder }
            val item = items.firstOrNull { it.id == sel.itemId } ?: items.firstOrNull() ?: return@launch
            val method = if (sel.methodTouched) sel.method else EntryRules.defaultMethod(snapshot, item)
            val cardId = if (sel.cardTouched) sel.cardId else EntryRules.defaultCard(snapshot, item)
            val entry = EntryRules.buildEntry(snapshot, item, method, cardId, sel.amount, sel.note) ?: return@launch
            val message = EntryRules.savedMessage(snapshot, item, method, entry.amount, sel.note)
            if (sel.installmentOn && method == PaymentMethod.CREDIT_CARD && item.type == FlowType.EXPENSE) {
                val installment = draftInstallment(snapshot, item, cardId, sel)
                val installmentId = repository.addInstallmentPurchase(entry, installment)
                selection.update {
                    it.copy(
                        amount = "", note = "", installmentOn = false,
                        message = message + InstallmentRules.savedSuffix(installment),
                        lastSavedId = null, lastInstallmentId = installmentId,
                    )
                }
                return@launch
            }
            val id = repository.addLedgerEntry(entry)
            selection.update { it.copy(amount = "", note = "", message = message, lastSavedId = id, lastInstallmentId = null) }
        }
    }

    fun undo() {
        val sel = selection.value
        viewModelScope.launch {
            sel.lastSavedId?.let { repository.deleteLedgerEntry(it) }
            sel.lastInstallmentId?.let { repository.deleteInstallment(it) }
            selection.update { it.copy(message = null, lastSavedId = null, lastInstallmentId = null) }
        }
    }

    fun dismissMessage() = selection.update { it.copy(message = null, lastSavedId = null, lastInstallmentId = null) }

    /** 還沒有任何資料時，載入示意資料試用。 */
    fun loadSample() {
        viewModelScope.launch {
            repository.installSample()
            selection.value = Selection(message = "已載入示意資料")
        }
    }
}

package tw.myfsl.app.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import tw.myfsl.app.core.data.FinanceRepository
import tw.myfsl.app.core.domain.RecordDraft
import tw.myfsl.app.core.domain.RecordEditForm
import tw.myfsl.app.core.domain.RecordRules
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import kotlinx.coroutines.flow.first
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PaymentMethod
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

enum class RecordFilter(val label: String) {
    ALL("全部"),
    FLEXIBLE("可調項目"),
    CARD("信用卡"),
    CASH("現金"),
    UNPLANNED("計畫外"),
}

/** 一筆紀錄在畫面上的樣子。 */
data class RecordRow(
    val id: Long,
    val title: String,
    val subtitle: String,
    val amountText: String,
    val income: Boolean,
    /** 本週檢查產生的標示：漏記差額／多記差額／到期確認。 */
    val sourceLabel: String?,
)

data class RecordDay(
    val date: LocalDate,
    val label: String,
    val summary: String,
    val rows: List<RecordRow>,
)

data class RecordsUiState(
    val loading: Boolean = true,
    val monthLabel: String = "",
    val canGoNext: Boolean = false,
    val expenseTotal: String = "",
    val incomeTotal: String = "",
    val missedLabel: String? = null,
    val filter: RecordFilter = RecordFilter.ALL,
    val days: List<RecordDay> = emptyList(),
    val empty: Boolean = false,
    val editor: RecordEditor? = null,
    val message: String? = null,
    val items: List<PlanItem> = emptyList(),
    val cards: List<Account> = emptyList(),
    val pickCard: Boolean = true,
    val today: LocalDate = LocalDate.now(),
)

data class RecordEditor(
    val draft: RecordDraft,
    val errors: Map<String, String> = emptyMap(),
    val warnings: List<String> = emptyList(),
)

@HiltViewModel
class RecordsViewModel @Inject constructor(
    private val repository: FinanceRepository,
) : ViewModel() {

    private data class View(val month: YearMonth? = null, val filter: RecordFilter = RecordFilter.ALL)

    private val view = MutableStateFlow(View())
    private val editor = MutableStateFlow<RecordEditor?>(null)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<RecordsUiState> = combine(repository.snapshot, view, editor, message) { snapshot, v, e, m ->
        build(snapshot, v).copy(
            editor = e,
            message = m,
            items = snapshot.activeItems,
            cards = snapshot.activeAccounts.filter { it.kind == AccountKind.CREDIT_CARD },
            pickCard = snapshot.settings.pickCard,
            today = snapshot.today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordsUiState())

    private fun build(snapshot: FinanceSnapshot, view: View): RecordsUiState {
        val current = YearMonth.from(snapshot.today)
        val month = view.month ?: current
        val totals = RecordRules.monthTotals(snapshot, month.year, month.monthValue)
        val entries = snapshot.ledger
            .filter { YearMonth.from(it.date) == month }
            .filter { keep(it, snapshot, view.filter) }
            .sortedWith(compareByDescending<LedgerEntry> { it.date }.thenByDescending { it.id })

        val days = entries.groupBy { it.date }.map { (date, rows) ->
            val expense = rows.filter { it.type == FlowType.EXPENSE }.sumOf { it.amount }
            val income = rows.filter { it.type == FlowType.INCOME }.sumOf { it.amount }
            val parts = buildList {
                if (expense != 0L) add("支出 ${MoneyFormat.currency(expense)}")
                if (income != 0L) add("收入 ${MoneyFormat.currency(income)}")
            }
            val checkIn = if (snapshot.lastCheckIn?.date == date) " · 本週檢查" else ""
            RecordDay(
                date = date,
                label = "${date.monthValue}/${date.dayOfMonth}（${weekday(date)}）$checkIn",
                summary = parts.joinToString(" · "),
                rows = rows.map { row(it, snapshot) },
            )
        }

        return RecordsUiState(
            loading = false,
            monthLabel = "${month.year} 年 ${month.monthValue} 月",
            canGoNext = month < current,
            expenseTotal = MoneyFormat.currency(totals.expense),
            incomeTotal = MoneyFormat.currency(totals.income),
            missedLabel = if (month == current) RecordRules.missedLabel(RecordRules.missedSummary(snapshot)) else null,
            filter = view.filter,
            days = days,
            empty = days.isEmpty(),
        )
    }

    private fun keep(entry: LedgerEntry, snapshot: FinanceSnapshot, filter: RecordFilter): Boolean = when (filter) {
        RecordFilter.ALL -> true
        RecordFilter.FLEXIBLE -> snapshot.item(entry.itemId)?.flexibility == Flexibility.FLEXIBLE
        RecordFilter.CARD -> entry.method == PaymentMethod.CREDIT_CARD
        RecordFilter.CASH -> entry.method == PaymentMethod.CASH
        RecordFilter.UNPLANNED -> {
            val itemId = entry.itemId
            val method = entry.method
            itemId != null && method != null &&
                snapshot.planAmount(PlanLine(itemId, method), entry.date.year, entry.date.monthValue) == 0L
        }
    }

    private fun row(entry: LedgerEntry, snapshot: FinanceSnapshot): RecordRow {
        val item = snapshot.item(entry.itemId)
        val card = snapshot.account(entry.accountId)
        val subtitle = when (entry.type) {
            FlowType.EXPENSE -> buildList {
                item?.let { add(it.name) }
                entry.method?.let { method ->
                    add(
                        when {
                            method != PaymentMethod.CREDIT_CARD -> method.label
                            card != null -> card.name
                            else -> "信用卡（未指定卡片）"
                        },
                    )
                }
            }.joinToString(" · ")

            FlowType.INCOME -> listOfNotNull(item?.name, card?.name).joinToString(" · ")
            FlowType.TRANSFER -> listOfNotNull(
                item?.name,
                card?.name?.let { from -> "$from → ${snapshot.account(entry.toAccountId)?.name ?: "?"}" },
            ).joinToString(" · ")
        }
        val income = entry.type == FlowType.INCOME
        return RecordRow(
            id = entry.id,
            title = entry.note.ifBlank { item?.name ?: entry.type.label },
            subtitle = subtitle,
            amountText = signed(entry.amount, income),
            income = income,
            sourceLabel = RecordRules.sourceLabel(entry)
                ?: entry.installmentId?.let { id -> snapshot.installments.firstOrNull { it.id == id }?.let { "分 ${it.months} 期" } ?: "分期" },
        )
    }

    private fun signed(amount: Money, income: Boolean): String =
        (if (income) "+" else "−") + MoneyFormat.currency(amount)

    private fun weekday(date: LocalDate): String =
        listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.value - 1]

    private fun currentMonth(): YearMonth = YearMonth.from(repository.today())

    fun previousMonth() = view.update { it.copy(month = (it.month ?: currentMonth()).minusMonths(1)) }

    fun nextMonth() = view.update { it.copy(month = (it.month ?: currentMonth()).plusMonths(1)) }

    fun setFilter(filter: RecordFilter) = view.update { it.copy(filter = filter) }

    /** 刪除；分期消費連同分期一起刪。 */
    fun delete(id: Long) {
        viewModelScope.launch {
            val entry = repository.snapshot.first().ledger.firstOrNull { it.id == id } ?: return@launch
            val installmentId = entry.installmentId
            if (installmentId != null) repository.deleteInstallment(installmentId) else repository.deleteLedgerEntry(id)
        }
    }

    // ---- 修改 ----

    fun edit(id: Long) {
        viewModelScope.launch {
            val entry = repository.snapshot.first().ledger.firstOrNull { it.id == id } ?: return@launch
            editor.value = RecordEditor(RecordDraft(entry))
        }
    }

    fun change(transform: (RecordDraft) -> RecordDraft) = editor.update { it?.copy(draft = transform(it.draft)) }

    fun cancelEdit() {
        editor.value = null
    }

    fun saveEdit() {
        val current = editor.value ?: return
        viewModelScope.launch {
            val result = RecordEditForm.validate(current.draft, repository.snapshot.first())
            if (!result.ok) {
                editor.value = current.copy(errors = result.errors, warnings = result.warnings)
                return@launch
            }
            if (result.entry != current.draft.original) repository.updateLedgerEntry(result.entry!!)
            editor.value = null
            message.value = result.warnings.firstOrNull { it != "沒有修改任何東西" }?.let { "已修改。$it" } ?: "已修改"
        }
    }

    fun dismissMessage() {
        message.value = null
    }
}

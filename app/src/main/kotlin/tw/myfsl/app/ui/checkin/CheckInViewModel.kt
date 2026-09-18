package tw.myfsl.app.ui.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import tw.myfsl.app.core.data.FinanceRepository
import tw.myfsl.app.core.domain.CheckInInput
import tw.myfsl.app.core.domain.CheckInResult
import tw.myfsl.app.core.domain.CheckInRules
import tw.myfsl.app.core.domain.ConfirmChoice
import tw.myfsl.app.core.domain.ConfirmDecision
import tw.myfsl.app.core.domain.ConfirmLine
import tw.myfsl.app.core.domain.DiffKind
import tw.myfsl.app.core.domain.Reconcile
import tw.myfsl.app.core.domain.ReconcileDecision
import tw.myfsl.app.core.domain.ReportLine
import tw.myfsl.app.core.domain.Resolution
import tw.myfsl.app.core.model.ActualStatus
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CheckInStep(val title: String) {
    CONFIRM("到期確認"),
    RECONCILE("帳戶對帳"),
    REVIEW("會寫入什麼"),
}

data class ConfirmRow(
    val line: ConfirmLine,
    val choice: ConfirmChoice?,
    val amountText: String,
    val amountError: Boolean,
) {
    val title: String get() = line.item.name + (line.method?.let { " · ${it.label}" } ?: "")
}

data class ReportRow(val line: ReportLine, val text: String) {
    val title: String get() = line.item.name + (line.method?.let { " · ${it.label}" } ?: "")
}

data class ReconcileRow(
    val row: Reconcile,
    /** 帳戶 id → 輸入文字。 */
    val inputs: Map<Long, String>,
    /** 全部帳戶都填了才有值。 */
    val actual: Money?,
    val kind: DiffKind?,
    val message: String?,
    val resolution: Resolution?,
    val item: PlanItem?,
)

data class CheckInUiState(
    val loading: Boolean = true,
    val step: CheckInStep = CheckInStep.CONFIRM,
    val steps: List<CheckInStep> = CheckInStep.entries,
    val confirms: List<ConfirmRow> = emptyList(),
    val reports: List<ReportRow> = emptyList(),
    val reconciles: List<ReconcileRow> = emptyList(),
    val itemChoices: List<PlanItem> = emptyList(),
    val result: CheckInResult? = null,
    val summaryLines: List<String> = emptyList(),
    val saving: Boolean = false,
    val done: Boolean = false,
    val doneLines: List<String> = emptyList(),
)

@HiltViewModel
class CheckInViewModel @Inject constructor(
    private val repository: FinanceRepository,
) : ViewModel() {

    private data class Local(
        val step: CheckInStep = CheckInStep.CONFIRM,
        val confirmChoice: Map<PlanLine, ConfirmChoice> = emptyMap(),
        val confirmAmount: Map<PlanLine, String> = emptyMap(),
        val reports: Map<PlanLine, String> = emptyMap(),
        val balances: Map<Long, String> = emptyMap(),
        val resolutions: Map<Long, Resolution> = emptyMap(),
        val items: Map<Long, Long> = emptyMap(),
        val saving: Boolean = false,
        val doneLines: List<String>? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<CheckInUiState> = combine(repository.snapshot, local, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckInUiState())

    private fun parse(text: String?): Money? = text?.let { MoneyFormat.parse(it) }

    private fun input(snapshot: FinanceSnapshot, local: Local): CheckInInput {
        val confirms = local.confirmChoice.mapNotNull { (line, choice) ->
            if (choice == ConfirmChoice.DIFFERENT_AMOUNT) {
                val amount = parse(local.confirmAmount[line]) ?: return@mapNotNull null
                line to ConfirmDecision(choice, amount)
            } else {
                line to ConfirmDecision(choice)
            }
        }.toMap()
        val reports = local.reports.mapNotNull { (line, text) -> parse(text)?.let { line to it } }.toMap()
        val reconciles = CheckInRules.reconciles(snapshot).associate { row ->
            val balances = row.accounts.mapNotNull { account -> parse(local.balances[account.id])?.let { account.id to it } }.toMap()
            row.id to ReconcileDecision(balances, local.resolutions[row.id], local.items[row.id])
        }
        return CheckInInput(reports, confirms, reconciles)
    }

    private fun build(snapshot: FinanceSnapshot, local: Local): CheckInUiState {
        val confirmLines = CheckInRules.confirmLines(snapshot)
        val reportLines = CheckInRules.reportLines(snapshot)
        val fullInput = input(snapshot, local)

        // 對帳的推算要包含前面步驟產生的補記。
        val beforeReconcile = CheckInRules.build(snapshot, fullInput.copy(reconciles = emptyMap()))
        val reconcileRows = CheckInRules.reconciles(snapshot, beforeReconcile.entries).map { row ->
            val inputs = row.accounts.associate { it.id to (local.balances[it.id] ?: "") }
            val values = row.accounts.map { parse(local.balances[it.id]) }
            val actual = if (values.all { it != null }) values.sumOf { it!! } else null
            val kind = actual?.let { row.kind(it) }
            ReconcileRow(
                row = row,
                inputs = inputs,
                actual = actual,
                kind = kind,
                message = if (actual != null && kind != null) {
                    CheckInRules.message(kind, row.missed(actual), row.isCard, snapshot.settings.cardPostingDays)
                } else {
                    null
                },
                resolution = kind?.let { local.resolutions[row.id] ?: CheckInRules.defaultResolution(it) },
                item = snapshot.item(local.items[row.id]) ?: row.defaultItem,
            )
        }
        val result = CheckInRules.build(snapshot, fullInput)

        val steps = if (confirmLines.isEmpty() && reportLines.isEmpty()) {
            listOf(CheckInStep.RECONCILE, CheckInStep.REVIEW)
        } else {
            CheckInStep.entries
        }
        val step = if (local.step in steps) local.step else steps.first()

        return CheckInUiState(
            loading = false,
            step = step,
            steps = steps,
            confirms = confirmLines.map { line ->
                val choice = local.confirmChoice[line.line]
                ConfirmRow(
                    line = line,
                    choice = choice,
                    amountText = local.confirmAmount[line.line] ?: "",
                    amountError = choice == ConfirmChoice.DIFFERENT_AMOUNT && parse(local.confirmAmount[line.line]) == null,
                )
            },
            reports = reportLines.map { ReportRow(it, local.reports[it.line] ?: "") },
            reconciles = reconcileRows,
            itemChoices = snapshot.activeItems.filter { it.type == FlowType.EXPENSE },
            result = result,
            summaryLines = summary(snapshot, result),
            saving = local.saving,
            done = local.doneLines != null,
            doneLines = local.doneLines.orEmpty(),
        )
    }

    private fun summary(snapshot: FinanceSnapshot, result: CheckInResult): List<String> = buildList {
        val missed = result.entries.filter { it.source == EntrySource.MISSED }
        if (missed.isNotEmpty()) {
            add("漏記差額 ${missed.size} 筆，合計 ${MoneyFormat.signed(missed.sumOf { it.amount })}")
            missed.forEach { add("　· ${snapshot.item(it.itemId)?.name.orEmpty()} ${it.method?.label.orEmpty()} ${MoneyFormat.signed(it.amount)}") }
        }
        val confirmed = result.entries.filter { it.source == EntrySource.CONFIRMED }
        val done = result.actuals.count { it.status == ActualStatus.DONE }
        val postponed = result.actuals.count { it.status == ActualStatus.POSTPONED }
        if (done > 0) add("到期確認完成 $done 項" + if (confirmed.isNotEmpty()) "，補記 ${MoneyFormat.currency(confirmed.sumOf { it.amount })}" else "")
        if (postponed > 0) add("延到下月 $postponed 項")
        if (result.balances.isNotEmpty()) {
            add("校正餘額 ${result.balances.size} 個帳戶")
            result.balances.forEach { (id, value) -> add("　· ${snapshot.account(id)?.name.orEmpty()} ${MoneyFormat.currency(value)}") }
        }
    }

    // ---- 操作 ----

    fun goTo(step: CheckInStep) = local.update { it.copy(step = step) }

    fun next() {
        val steps = state.value.steps
        val index = steps.indexOf(state.value.step)
        if (index < steps.lastIndex) goTo(steps[index + 1])
    }

    fun back(): Boolean {
        val steps = state.value.steps
        val index = steps.indexOf(state.value.step)
        if (index <= 0) return false
        goTo(steps[index - 1])
        return true
    }

    fun choose(line: PlanLine, choice: ConfirmChoice) = local.update {
        val current = it.confirmChoice[line]
        it.copy(confirmChoice = if (current == choice) it.confirmChoice - line else it.confirmChoice + (line to choice))
    }

    fun setConfirmAmount(line: PlanLine, text: String) = local.update { it.copy(confirmAmount = it.confirmAmount + (line to digits(text))) }

    fun setReport(line: PlanLine, text: String) = local.update { it.copy(reports = it.reports + (line to digits(text))) }

    fun setBalance(accountId: Long, text: String) = local.update { it.copy(balances = it.balances + (accountId to digits(text))) }

    /** 「相符」：填入系統推算。信用卡合計把差額放在第一張卡，其他卡填各自的欠款。 */
    fun matchComputed(rowId: Long) {
        val row = state.value.reconciles.firstOrNull { it.row.id == rowId }?.row ?: return
        local.update { current ->
            val others = row.accounts.drop(1)
            val first = row.accounts.first()
            val fills = others.associate { it.id to it.balance.toString() } +
                (first.id to (row.computed - others.sumOf { it.balance }).toString())
            current.copy(balances = current.balances + fills)
        }
    }

    fun setResolution(rowId: Long, resolution: Resolution) = local.update { it.copy(resolutions = it.resolutions + (rowId to resolution)) }

    fun setItem(rowId: Long, itemId: Long) = local.update { it.copy(items = it.items + (rowId to itemId)) }

    fun finish() {
        val result = state.value.result ?: return
        if (result.isEmpty || local.value.saving) return
        val lines = state.value.summaryLines
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            repository.recordCheckIn(result)
            local.update { Local(doneLines = lines) }
        }
    }

    fun again() = local.update { Local() }

    private fun digits(text: String): String = text.filter { it.isDigit() || it == '-' }.take(10)
}

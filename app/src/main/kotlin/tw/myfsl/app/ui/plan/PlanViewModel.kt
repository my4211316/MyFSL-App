package tw.myfsl.app.ui.plan

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import tw.myfsl.app.core.data.FinanceRepository
import tw.myfsl.app.core.domain.CashOutlook
import tw.myfsl.app.core.domain.CashOutlookCalculator
import tw.myfsl.app.core.domain.TextDecoding
import tw.myfsl.app.core.domain.ImportMode
import tw.myfsl.app.core.domain.ImportPreview
import tw.myfsl.app.core.domain.PlanEditRules
import tw.myfsl.app.core.domain.displayPercent
import tw.myfsl.app.core.domain.PlanImport
import tw.myfsl.app.core.domain.PlanIssue
import tw.myfsl.app.core.domain.PlanItemDraft
import tw.myfsl.app.core.domain.PlanItemForm
import tw.myfsl.app.core.domain.PlanSummary
import tw.myfsl.app.core.domain.PlanSummaryCalculator
import tw.myfsl.app.core.domain.PlanTable
import tw.myfsl.app.core.domain.PlanTableView
import tw.myfsl.app.core.domain.PlanTableBuilder
import tw.myfsl.app.core.domain.PlanValidator
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
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

/** 計畫表的一列（一個項目一列，R-MIX-01）。 */
@Immutable
data class PlanRow(
    val itemId: Long,
    val groupName: String,
    val itemName: String,
    val detail: String,
    val total: Money,
)

@Immutable
data class ImportState(
    val fileName: String,
    val encoding: String,
    val preview: ImportPreview,
    /** 讀檔（對應帳戶與群組）時的資料世代（F11）。 */
    val generation: Long = FinanceSnapshot.NO_GENERATION,
    val mode: ImportMode = ImportMode.REPLACE_YEAR,
    val importing: Boolean = false,
)

/** 項目編輯中的狀態。 */
@Immutable
data class ItemEditor(
    val draft: PlanItemDraft,
    /** 展開中的計畫列（項目 × 支付方式）；null 表示全部收起。 */
    val expandedLine: Int? = 0,
    val errors: Map<String, String> = emptyMap(),
    val warnings: List<String> = emptyList(),
    /** 已經有記帳，類型不能改（R-EDT-11）。 */
    val typeLocked: Boolean = false,
    /** 打開編輯時的資料世代（F11）。 */
    val generation: Long = FinanceSnapshot.NO_GENERATION,
) {
    val isNew: Boolean get() = draft.id == 0L
}

@Immutable
data class PlanUiState(
    val loading: Boolean = true,
    val year: Int = 0,
    val summary: PlanSummary? = null,
    val issues: List<PlanIssue> = emptyList(),
    val rows: List<PlanRow> = emptyList(),
    val import: ImportState? = null,
    val editor: ItemEditor? = null,
    val groups: List<PlanGroup> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val message: String? = null,
    val showTable: Boolean = false,
    /** 年表要回答哪個問題（R-PLS-10）：什麼時候花／什麼時候付。 */
    val tableView: PlanTableView = PlanTableView.SPEND,
    val table: PlanTable? = null,
    /** 未來現金水位（R-PLS-09）：照這份計畫走下去，每個月底還剩多少。 */
    val outlook: CashOutlook? = null,
)

@HiltViewModel
class PlanViewModel @Inject constructor(
    private val repository: FinanceRepository,
) : ViewModel() {

    private data class Local(
        val year: Int? = null,
        val import: ImportState? = null,
        val editor: ItemEditor? = null,
        val message: String? = null,
        val showTable: Boolean = false,
        val tableView: PlanTableView = PlanTableView.SPEND,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<PlanUiState> = combine(repository.snapshot, local, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanUiState())

    private fun build(snapshot: FinanceSnapshot, local: Local): PlanUiState {
        val year = local.year ?: snapshot.today.year
        val amounts = snapshot.planForYear(year)
        val groups = snapshot.groups.associateBy { it.id }
        val rows = snapshot.activeItems
            .sortedWith(compareBy({ groups[it.groupId]?.sortOrder ?: Int.MAX_VALUE }, { it.sortOrder }))
            .map { item ->
                val lines = snapshot.planLines(item.id, year)
                PlanRow(
                    itemId = item.id,
                    groupName = groups[item.groupId]?.name.orEmpty(),
                    itemName = item.name,
                    detail = detailOf(item, lines),
                    total = lines.sumOf { line -> amounts[line]?.sum() ?: 0L },
                )
            }
        return PlanUiState(
            loading = false,
            year = year,
            summary = if (snapshot.activeItems.isEmpty()) null else PlanSummaryCalculator.summarize(snapshot, year),
            issues = if (snapshot.activeItems.isEmpty()) emptyList() else PlanValidator.validate(snapshot, year),
            rows = rows,
            import = local.import,
            editor = local.editor,
            groups = snapshot.groups.sortedBy { it.sortOrder },
            accounts = snapshot.activeAccounts,
            message = local.message,
            showTable = local.showTable,
            tableView = local.tableView,
            table = if (local.showTable && snapshot.activeItems.isNotEmpty()) {
                PlanTableBuilder.build(snapshot, year, local.tableView)
            } else {
                null
            },
            outlook = if (snapshot.activeAccounts.isEmpty() && snapshot.activeItems.isEmpty()) null else CashOutlookCalculator.build(snapshot),
        )
    }

    fun setShowTable(show: Boolean) = local.update { it.copy(showTable = show) }

    fun setTableView(view: PlanTableView) = local.update { it.copy(tableView = view) }

    /** 列的說明文字。支出把計畫列用到的支付方式列出來（R-MIX-01），例如「現金＋信用卡」。 */
    private fun detailOf(item: PlanItem, lines: List<PlanLine>): String {
        val methods = lines.mapNotNull { it.method }.distinct().joinToString("＋") { it.label }.takeIf { it.isNotEmpty() }
        return listOfNotNull(
            item.type.label,
            methods,
            item.dueDay?.let { "每月 $it 號" },
            item.flexibility.label,
            item.tracking.label,
        ).joinToString(" · ")
    }

    fun previousYear() = local.update { it.copy(year = (it.year ?: state.value.year) - 1) }

    fun nextYear() = local.update { it.copy(year = (it.year ?: state.value.year) + 1) }

    // ---- 項目編輯 ----

    fun startNew() {
        viewModelScope.launch {
            val snapshot = repository.snapshot.first()
            val draft = PlanItemForm.newDraft(FlowType.EXPENSE, snapshot.groups.minByOrNull { it.sortOrder }?.id, snapshot)
            local.update { it.copy(editor = ItemEditor(draft, generation = snapshot.generation)) }
        }
    }

    fun edit(itemId: Long) {
        viewModelScope.launch {
            val snapshot = repository.snapshot.first()
            val item = snapshot.item(itemId) ?: return@launch
            val draft = PlanItemForm.fromItem(item, snapshot, state.value.year)
            local.update {
                it.copy(
                    editor = ItemEditor(
                        draft,
                        typeLocked = PlanItemForm.typeLocked(draft, snapshot),
                        generation = snapshot.generation,
                    ),
                )
            }
        }
    }

    fun change(transform: (PlanItemDraft) -> PlanItemDraft) = local.update { current ->
        val editor = current.editor ?: return@update current
        current.copy(editor = editor.copy(draft = transform(editor.draft)))
    }

    fun setType(type: FlowType) = change { PlanItemForm.changeType(it, type) }

    /** 加一條支付方式列（R-EDT-06），例如生活費除了現金再加一條刷卡。 */
    fun addLine(method: PaymentMethod) = local.update { current ->
        val editor = current.editor ?: return@update current
        val draft = PlanItemForm.addLine(editor.draft, method)
        current.copy(editor = editor.copy(draft = draft, expandedLine = draft.lines.lastIndex))
    }

    fun removeLine(index: Int) = local.update { current ->
        val editor = current.editor ?: return@update current
        current.copy(editor = editor.copy(draft = PlanItemForm.removeLine(editor.draft, index), expandedLine = 0))
    }

    fun setMethod(index: Int, method: PaymentMethod) = change { PlanItemForm.setMethod(it, index, method) }

    fun setMonth(index: Int, month: Int, value: String) = change { PlanItemForm.setMonth(it, index, month, value) }

    fun toggleLine(index: Int) = local.update { current ->
        val editor = current.editor ?: return@update current
        current.copy(editor = editor.copy(expandedLine = if (editor.expandedLine == index) null else index))
    }

    /** 金額快捷：12 個月同額／年金額平分／清空（R-EDT-01）。 */
    fun quickFill(index: Int, kind: QuickFill, amountText: String) = change { draft ->
        val amount = amountText.replace(",", "").trim().toLongOrNull() ?: 0L
        val months = when (kind) {
            QuickFill.EVERY_MONTH -> PlanEditRules.everyMonth(amount)
            QuickFill.SPREAD_YEAR -> PlanEditRules.spreadYear(amount)
            QuickFill.CLEAR -> PlanEditRules.clear()
        }
        PlanItemForm.fill(draft, index, months)
    }

    fun cancelEdit() = local.update { it.copy(editor = null) }

    fun saveItem() {
        val editor = local.value.editor ?: return
        viewModelScope.launch(WriteGuard) {
            val snapshot = repository.snapshot.first()
            val result = PlanItemForm.validate(editor.draft, snapshot)
            if (!result.ok) {
                local.update { it.copy(editor = editor.copy(errors = result.errors, warnings = result.warnings)) }
                return@launch
            }
            var item = result.item!!
            result.newGroupName?.let { name ->
                val groupId = repository.saveGroup(PlanGroup(name = name, sortOrder = (snapshot.groups.maxOfOrNull { it.sortOrder } ?: 0) + 1), editor.generation)
                item = item.copy(groupId = groupId)
            }
            if (editor.isNew) {
                item = item.copy(sortOrder = (snapshot.items.filter { it.groupId == item.groupId }.maxOfOrNull { it.sortOrder } ?: 0) + 1)
            }
            val year = state.value.year
            repository.saveItem(item, mapOf(year to result.amounts), editor.generation)
            val suffix = result.warnings.firstOrNull()?.let { "（$it）" }.orEmpty()
            local.update {
                it.copy(editor = null, message = (if (editor.isNew) "已新增「${item.name}」" else "已更新「${item.name}」") + " 到 $year 年$suffix")
            }
        }
    }

    fun archiveItem() {
        val editor = local.value.editor ?: return
        if (editor.isNew) return
        viewModelScope.launch(WriteGuard) {
            repository.setItemArchived(editor.draft.id, true, editor.generation)
            local.update { it.copy(editor = null, message = "已封存「${editor.draft.name}」") }
        }
    }

    // ---- 匯入與匯出 ----

    /** 使用者選好 CSV 檔案後呼叫。 */
    fun onFileLoaded(fileName: String, bytes: ByteArray) {
        viewModelScope.launch {
            val snapshot = repository.snapshot.first()
            val decoded = TextDecoding.decode(bytes)
            val year = state.value.year.takeIf { it > 0 } ?: snapshot.today.year
            val preview = PlanImport.parse(decoded.text, year, snapshot.accounts, snapshot.groups)
            local.update { it.copy(import = ImportState(fileName, decoded.encoding.label, preview, generation = snapshot.generation)) }
        }
    }

    fun setImportMode(mode: ImportMode) = local.update { current ->
        current.copy(import = current.import?.copy(mode = mode))
    }

    fun cancelImport() = local.update { it.copy(import = null) }

    fun confirmImport() {
        val import = local.value.import ?: return
        if (!import.preview.canImport) return
        local.update { it.copy(import = import.copy(importing = true)) }
        viewModelScope.launch(WriteGuard) {
            val written = try {
                repository.importPlan(import.preview, import.generation, import.mode)
            } finally {
                local.update { current -> current.copy(import = current.import?.copy(importing = false)) }
            }
            local.update { it.copy(import = null, year = import.preview.year, message = "已匯入 $written 個項目到 ${import.preview.year} 年") }
        }
    }

    fun readError(message: String) = local.update { it.copy(message = message) }

    /** 匯出目前年度計畫的內容（UTF-8 加 BOM）。 */
    suspend fun exportBytes(): ByteArray {
        val snapshot = repository.snapshot.first()
        val year = state.value.year.takeIf { it > 0 } ?: snapshot.today.year
        val text = PlanImport.export(year, snapshot.groups, snapshot.items, snapshot.planForYear(year), snapshot.accounts)
        return TextDecoding.encodeForExcel(text)
    }

    fun templateBytes(): ByteArray = TextDecoding.encodeForExcel(PlanImport.template())

    fun exported(fileName: String) = local.update { it.copy(message = "已存成 $fileName") }

    fun dismissMessage() = local.update { it.copy(message = null) }
}

enum class QuickFill(val label: String) {
    EVERY_MONTH("每月同額"),
    SPREAD_YEAR("年金額平分"),
    CLEAR("清空"),
}

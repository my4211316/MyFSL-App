package tw.myfsl.app.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import tw.myfsl.app.core.data.FinanceRepository
import tw.myfsl.app.core.domain.TextDecoding
import tw.myfsl.app.core.domain.ImportMode
import tw.myfsl.app.core.domain.ImportPreview
import tw.myfsl.app.core.domain.PlanEditRules
import tw.myfsl.app.core.domain.PlanImport
import tw.myfsl.app.core.domain.PlanIssue
import tw.myfsl.app.core.domain.PlanItemDraft
import tw.myfsl.app.core.domain.PlanItemForm
import tw.myfsl.app.core.domain.PlanSummary
import tw.myfsl.app.core.domain.PlanSummaryCalculator
import tw.myfsl.app.core.domain.PlanTable
import tw.myfsl.app.core.domain.PlanTableBuilder
import tw.myfsl.app.core.domain.PlanValidator
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanGroup
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

/** 計畫表的一列（項目 × 支付方式）。 */
data class PlanRow(
    val itemId: Long,
    val groupName: String,
    val itemName: String,
    val method: PaymentMethod?,
    val detail: String,
    val total: Money,
)

data class ImportState(
    val fileName: String,
    val encoding: String,
    val preview: ImportPreview,
    val mode: ImportMode = ImportMode.REPLACE_YEAR,
    val importing: Boolean = false,
)

/** 項目編輯中的狀態。 */
data class ItemEditor(
    val draft: PlanItemDraft,
    val errors: Map<String, String> = emptyMap(),
    val warnings: List<String> = emptyList(),
    /** 正在展開 12 個月的計畫列索引。 */
    val expandedLine: Int? = 0,
    /** 已經有記帳，類型不能改（R-EDT-11）。 */
    val typeLocked: Boolean = false,
) {
    val isNew: Boolean get() = draft.id == 0L
}

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
    val table: PlanTable? = null,
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
            .flatMap { item ->
                val lines = amounts.filterKeys { it.itemId == item.id }
                if (lines.isEmpty()) {
                    listOf(PlanRow(item.id, groups[item.groupId]?.name.orEmpty(), item.name, null, detailOf(item, null), 0))
                } else {
                    lines.entries.sortedBy { it.key.method?.ordinal ?: -1 }.map { (line, months) ->
                        PlanRow(item.id, groups[item.groupId]?.name.orEmpty(), item.name, line.method, detailOf(item, line.method), months.sum())
                    }
                }
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
            table = if (local.showTable && snapshot.activeItems.isNotEmpty()) PlanTableBuilder.build(snapshot, year) else null,
        )
    }

    fun setShowTable(show: Boolean) = local.update { it.copy(showTable = show) }

    private fun detailOf(item: PlanItem, method: PaymentMethod?): String =
        listOfNotNull(method?.label ?: item.type.label, item.timing.label, item.flexibility.label, item.tracking.label).joinToString(" · ")

    fun previousYear() = local.update { it.copy(year = (it.year ?: state.value.year) - 1) }

    fun nextYear() = local.update { it.copy(year = (it.year ?: state.value.year) + 1) }

    // ---- 項目編輯 ----

    fun startNew() {
        viewModelScope.launch {
            val snapshot = repository.snapshot.first()
            val draft = PlanItemForm.newDraft(FlowType.EXPENSE, snapshot.groups.minByOrNull { it.sortOrder }?.id, snapshot)
            local.update { it.copy(editor = ItemEditor(draft)) }
        }
    }

    fun edit(itemId: Long) {
        viewModelScope.launch {
            val snapshot = repository.snapshot.first()
            val item = snapshot.item(itemId) ?: return@launch
            val draft = PlanItemForm.fromItem(item, snapshot, state.value.year)
            local.update { it.copy(editor = ItemEditor(draft, typeLocked = PlanItemForm.typeLocked(draft, snapshot))) }
        }
    }

    fun change(transform: (PlanItemDraft) -> PlanItemDraft) = local.update { current ->
        val editor = current.editor ?: return@update current
        current.copy(editor = editor.copy(draft = transform(editor.draft)))
    }

    fun setType(type: FlowType) = change { PlanItemForm.changeType(it, type) }

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
        viewModelScope.launch {
            val snapshot = repository.snapshot.first()
            val result = PlanItemForm.validate(editor.draft, snapshot)
            if (!result.ok) {
                local.update { it.copy(editor = editor.copy(errors = result.errors, warnings = result.warnings)) }
                return@launch
            }
            var item = result.item!!
            result.newGroupName?.let { name ->
                val groupId = repository.saveGroup(PlanGroup(name = name, sortOrder = (snapshot.groups.maxOfOrNull { it.sortOrder } ?: 0) + 1))
                item = item.copy(groupId = groupId)
            }
            if (editor.isNew) {
                item = item.copy(sortOrder = (snapshot.items.filter { it.groupId == item.groupId }.maxOfOrNull { it.sortOrder } ?: 0) + 1)
            }
            val year = state.value.year
            repository.saveItem(item, mapOf(year to result.amounts))
            val suffix = result.warnings.firstOrNull()?.let { "（$it）" }.orEmpty()
            local.update {
                it.copy(editor = null, message = (if (editor.isNew) "已新增「${item.name}」" else "已更新「${item.name}」") + " 到 $year 年$suffix")
            }
        }
    }

    fun archiveItem() {
        val editor = local.value.editor ?: return
        if (editor.isNew) return
        viewModelScope.launch {
            repository.setItemArchived(editor.draft.id, true)
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
            local.update { it.copy(import = ImportState(fileName, decoded.encoding.label, preview)) }
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
        viewModelScope.launch {
            val written = repository.importPlan(import.preview, import.mode)
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

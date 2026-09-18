package tw.myfsl.app.ui.forecast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import tw.myfsl.app.core.data.FinanceRepository
import tw.myfsl.app.core.domain.ChangeDraft
import tw.myfsl.app.core.domain.ChangeKind
import tw.myfsl.app.core.domain.Comparison
import tw.myfsl.app.core.domain.ForecastComparisonCalculator
import tw.myfsl.app.core.domain.GoalTarget
import tw.myfsl.app.core.domain.ScenarioDraft
import tw.myfsl.app.core.domain.ScenarioForm
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.Scenario
import tw.myfsl.app.core.model.ScenarioChange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import tw.myfsl.app.ui.WriteGuard

data class ScenarioEditor(
    val draft: ScenarioDraft,
    val errors: Map<String, String> = emptyMap(),
    /** 打開編輯時的資料世代（F11）：情境裡有帳戶與項目的 id。 */
    val generation: Long = FinanceSnapshot.NO_GENERATION,
) {
    val isNew: Boolean get() = draft.id == 0L
}

enum class SeekTarget(val label: String) {
    SAFETY("不低於安全線"),
    NO_GAP("每年收支打平"),
}

data class SeekState(
    val target: SeekTarget = SeekTarget.SAFETY,
    val itemIds: Set<Long> = emptySet(),
    val running: Boolean = false,
    val cutPercent: Double? = null,
    val achievable: Boolean = true,
    val cutsPerYear: List<Pair<String, Money>> = emptyList(),
    val lowestAfter: Money? = null,
    val lowBeforeStart: Boolean = false,
    val nothingToCut: Boolean = false,
)

data class ForecastUiState(
    val loading: Boolean = true,
    val empty: Boolean = false,
    val months: Int = 24,
    val comparison: Comparison? = null,
    val hidden: Set<Long> = emptySet(),
    /** 情境 id → 變動摘要。 */
    val descriptions: Map<Long, List<String>> = emptyMap(),
    val editor: ScenarioEditor? = null,
    val seek: SeekState = SeekState(),
    val seekOpen: Boolean = false,
    val accounts: List<Account> = emptyList(),
    val expenseItems: List<PlanItem> = emptyList(),
    val flexibleItems: List<PlanItem> = emptyList(),
    val message: String? = null,
    val today: LocalDate = LocalDate.now(),
)

@HiltViewModel
class ForecastViewModel @Inject constructor(
    private val repository: FinanceRepository,
) : ViewModel() {

    private data class Local(
        /** null = 用設定的預設期間。 */
        val months: Int? = null,
        val hidden: Set<Long> = emptySet(),
        val editor: ScenarioEditor? = null,
        val seek: SeekState? = null,
        val seekOpen: Boolean = false,
        val message: String? = null,
    )

    private val local = MutableStateFlow(Local())

    /** 畫面正在顯示的資料世代（F11）：寫入時帶著，資料換過就會被拒絕。 */
    @Volatile private var shownGeneration = FinanceSnapshot.NO_GENERATION

    private data class Computed(val snapshot: FinanceSnapshot, val scenarios: List<Scenario>, val months: Int, val comparison: Comparison?)

    // 只有資料、情境或期間改變才重算，編輯表單打字時不重算。
    private val computed = combine(repository.snapshot, repository.scenarios, local.map { it.months }.distinctUntilChanged()) { snapshot, scenarios, chosen ->
        shownGeneration = snapshot.generation
        val months = chosen ?: snapshot.settings.horizonMonths
        val empty = snapshot.activeAccounts.isEmpty() && snapshot.activeItems.isEmpty()
        Computed(snapshot, scenarios, months, if (empty) null else ForecastComparisonCalculator.compare(snapshot, scenarios, months))
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<ForecastUiState> = combine(computed, local) { c, l ->
        val flexible = c.snapshot.activeItems.filter { it.type == FlowType.EXPENSE && it.flexibility == Flexibility.FLEXIBLE }
        ForecastUiState(
            loading = false,
            empty = c.comparison == null,
            months = c.months,
            comparison = c.comparison,
            hidden = l.hidden,
            descriptions = c.scenarios.associate { s -> s.id to s.changes.map { ScenarioForm.describe(it, c.snapshot) } },
            editor = l.editor,
            seek = l.seek ?: SeekState(itemIds = flexible.map { it.id }.toSet()),
            seekOpen = l.seekOpen,
            accounts = c.snapshot.activeAccounts,
            expenseItems = c.snapshot.activeItems.filter { it.type == FlowType.EXPENSE },
            flexibleItems = flexible,
            message = l.message,
            today = c.snapshot.today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ForecastUiState())

    fun setMonths(months: Int) = local.update { it.copy(months = months) }

    fun toggleVisible(id: Long) = local.update { it.copy(hidden = if (id in it.hidden) it.hidden - id else it.hidden + id) }

    // ---- 情境編輯 ----

    fun startNew() = local.update { it.copy(editor = ScenarioEditor(ScenarioDraft(), generation = shownGeneration)) }

    fun edit(scenario: Scenario) {
        viewModelScope.launch {
            val snapshot = repository.snapshot.first()
            local.update { it.copy(editor = ScenarioEditor(ScenarioForm.fromScenario(scenario, snapshot.today), generation = snapshot.generation)) }
        }
    }

    fun changeDraft(transform: (ScenarioDraft) -> ScenarioDraft) = local.update { current ->
        val editor = current.editor ?: return@update current
        current.copy(editor = editor.copy(draft = transform(editor.draft)))
    }

    fun addChange(kind: ChangeKind) {
        viewModelScope.launch {
            val snapshot = repository.snapshot.first()
            changeDraft { it.copy(changes = it.changes + ScenarioForm.newChange(kind, snapshot)) }
        }
    }

    fun updateChange(index: Int, transform: (ChangeDraft) -> ChangeDraft) = changeDraft { draft ->
        draft.copy(changes = draft.changes.mapIndexed { i, c -> if (i == index) transform(c) else c })
    }

    fun removeChange(index: Int) = changeDraft { draft -> draft.copy(changes = draft.changes.filterIndexed { i, _ -> i != index }) }

    fun cancelEdit() = local.update { it.copy(editor = null) }

    fun saveScenario() {
        val editor = local.value.editor ?: return
        viewModelScope.launch(WriteGuard) {
            val snapshot = repository.snapshot.first()
            val result = ScenarioForm.validate(editor.draft, snapshot)
            if (!result.ok) {
                local.update { it.copy(editor = editor.copy(errors = result.errors)) }
                return@launch
            }
            val scenario = result.scenario!!
            repository.saveScenario(scenario, editor.generation)
            local.update { it.copy(editor = null, message = "已儲存情境「${scenario.name}」") }
        }
    }

    fun deleteScenario() {
        val editor = local.value.editor ?: return
        if (editor.isNew) return
        viewModelScope.launch(WriteGuard) {
            repository.deleteScenario(editor.draft.id, editor.generation)
            local.update { it.copy(editor = null, message = "已刪除情境「${editor.draft.name}」") }
        }
    }

    // ---- 反推 ----

    fun toggleSeek() = local.update { it.copy(seekOpen = !it.seekOpen) }

    private fun updateSeek(transform: (SeekState) -> SeekState) = local.update { current ->
        current.copy(seek = transform(current.seek ?: state.value.seek))
    }

    fun setSeekTarget(target: SeekTarget) = updateSeek { it.copy(target = target, cutPercent = null) }

    fun toggleSeekItem(id: Long) = updateSeek { s -> s.copy(itemIds = if (id in s.itemIds) s.itemIds - id else s.itemIds + id, cutPercent = null) }

    fun runSeek() {
        val seek = state.value.seek
        updateSeek { it.copy(running = true) }
        viewModelScope.launch {
            val snapshot = repository.snapshot.first()
            val months = state.value.months
            val target = when (seek.target) {
                SeekTarget.SAFETY -> GoalTarget.MinLiquid(snapshot.settings.safetyLevel)
                SeekTarget.NO_GAP -> GoalTarget.NoStructuralGap
            }
            val result = withContext(Dispatchers.Default) {
                ForecastComparisonCalculator.seek(snapshot, months, seek.itemIds, target)
            }
            updateSeek {
                it.copy(
                    running = false,
                    cutPercent = result.cutPercent,
                    achievable = result.achievable,
                    cutsPerYear = result.cutsPerYear.entries.sortedByDescending { e -> e.value }
                        .map { e -> (snapshot.item(e.key)?.name ?: "") to e.value },
                    lowestAfter = result.result.lowestLiquid,
                    lowBeforeStart = result.lowBeforeStart,
                    nothingToCut = result.nothingToCut,
                )
            }
        }
    }

    /** 把反推結果存成一個「調整支出」情境。 */
    fun saveSeekAsScenario() {
        val seek = state.value.seek
        val percent = seek.cutPercent ?: return
        val generation = shownGeneration
        viewModelScope.launch(WriteGuard) {
            val snapshot = repository.snapshot.first()
            val from = ScenarioForm.indexFor(snapshot.today, 1)
            val scenario = Scenario(
                name = "可調支出減 ${trim(percent)}%",
                createdOn = snapshot.today,
                changes = listOf(ScenarioChange.AdjustItems(seek.itemIds.sorted(), -percent, from)),
                note = "由反推產生（${seek.target.label}）",
            )
            repository.saveScenario(scenario, generation)
            local.update { it.copy(message = "已存成情境「${scenario.name}」") }
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    private fun trim(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(java.util.Locale.US, "%.1f", value)
}

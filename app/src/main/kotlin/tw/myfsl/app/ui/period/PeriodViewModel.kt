package tw.myfsl.app.ui.period

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import tw.myfsl.app.core.data.FinanceRepository
import tw.myfsl.app.core.domain.PeriodOverview
import tw.myfsl.app.core.domain.PeriodOverviewCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class PeriodUiState(
    val loading: Boolean = true,
    val empty: Boolean = false,
    val overview: PeriodOverview? = null,
    val showAllBudget: Boolean = false,
)

@HiltViewModel
class PeriodViewModel @Inject constructor(
    repository: FinanceRepository,
) : ViewModel() {

    private val showAll = MutableStateFlow(false)

    private val overview = repository.snapshot
        .map { snapshot -> if (snapshot.activeAccounts.isEmpty() && snapshot.activeItems.isEmpty()) null else PeriodOverviewCalculator.build(snapshot) }
        .flowOn(Dispatchers.Default)

    val state: StateFlow<PeriodUiState> = combine(overview, showAll) { overview, all ->
        PeriodUiState(loading = false, empty = overview == null, overview = overview, showAllBudget = all)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeriodUiState())

    fun toggleShowAll() = showAll.update { !it }
}

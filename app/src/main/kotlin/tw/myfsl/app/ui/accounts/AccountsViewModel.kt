package tw.myfsl.app.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import tw.myfsl.app.core.data.FinanceRepository
import tw.myfsl.app.core.domain.AccountDraft
import tw.myfsl.app.core.domain.AccountForm
import tw.myfsl.app.core.domain.AccountSummaryCalculator
import tw.myfsl.app.core.domain.AccountsOverview
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.FinanceSnapshot
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

data class AccountEditor(
    val draft: AccountDraft,
    val errors: Map<String, String> = emptyMap(),
    val showAdvanced: Boolean = false,
) {
    val isNew: Boolean get() = draft.id == 0L
}

data class AccountsUiState(
    val loading: Boolean = true,
    val overview: AccountsOverview? = null,
    /** 可以當扣款帳戶的流動帳戶。 */
    val payAccounts: List<Account> = emptyList(),
    val editor: AccountEditor? = null,
    val message: String? = null,
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repository: FinanceRepository,
) : ViewModel() {

    private data class Local(val editor: AccountEditor? = null, val message: String? = null)

    private val local = MutableStateFlow(Local())

    val state: StateFlow<AccountsUiState> = combine(repository.snapshot, local, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    private fun build(snapshot: FinanceSnapshot, local: Local) = AccountsUiState(
        loading = false,
        overview = AccountSummaryCalculator.overview(snapshot),
        payAccounts = snapshot.activeAccounts.filter { it.kind.isLiquid },
        editor = local.editor,
        message = local.message,
    )

    fun startNew(kind: AccountKind = AccountKind.CASH) = local.update {
        it.copy(editor = AccountEditor(AccountDraft(kind = kind)))
    }

    fun edit(account: Account) = local.update {
        val draft = AccountForm.fromAccount(account)
        it.copy(editor = AccountEditor(draft, showAdvanced = draft.revolvingEnabled || draft.loanEnabled || draft.creditLimit.isNotBlank()))
    }

    fun update(transform: (AccountDraft) -> AccountDraft) = local.update { current ->
        val editor = current.editor ?: return@update current
        current.copy(editor = editor.copy(draft = transform(editor.draft)))
    }

    fun toggleAdvanced() = local.update { current ->
        val editor = current.editor ?: return@update current
        current.copy(editor = editor.copy(showAdvanced = !editor.showAdvanced))
    }

    fun cancel() = local.update { it.copy(editor = null) }

    fun save() {
        val editor = local.value.editor ?: return
        viewModelScope.launch {
            val accounts = repository.snapshot.first().accounts
            val result = AccountForm.validate(editor.draft, accounts)
            if (!result.ok) {
                local.update { it.copy(editor = editor.copy(errors = result.errors, showAdvanced = editor.showAdvanced || advancedError(result.errors))) }
                return@launch
            }
            val account = result.account!!
            val sortOrder = if (editor.isNew) (accounts.maxOfOrNull { it.sortOrder } ?: 0) + 1 else account.sortOrder
            repository.saveAccount(account.copy(sortOrder = sortOrder), result.balance)
            local.update { it.copy(editor = null, message = if (editor.isNew) "已新增「${account.name}」" else "已更新「${account.name}」") }
        }
    }

    fun archive() {
        val editor = local.value.editor ?: return
        if (editor.isNew) return
        viewModelScope.launch {
            repository.setAccountArchived(editor.draft.id, true)
            local.update { it.copy(editor = null, message = "已封存「${editor.draft.name}」") }
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    private fun advancedError(errors: Map<String, String>): Boolean =
        errors.keys.any { it != AccountForm.Field.NAME && it != AccountForm.Field.BALANCE }
}

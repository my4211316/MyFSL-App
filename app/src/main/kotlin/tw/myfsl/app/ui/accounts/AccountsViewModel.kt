package tw.myfsl.app.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import tw.myfsl.app.core.data.FinanceRepository
import tw.myfsl.app.core.domain.AccountDraft
import tw.myfsl.app.core.domain.AccountForm
import tw.myfsl.app.core.domain.AccountSummaryCalculator
import tw.myfsl.app.core.domain.AccountsOverview
import tw.myfsl.app.core.domain.BillCorrection
import tw.myfsl.app.core.domain.CardRules
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
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
import tw.myfsl.app.ui.WriteGuard

data class AccountEditor(
    val draft: AccountDraft,
    val errors: Map<String, String> = emptyMap(),
    val showAdvanced: Boolean = false,
    /** 打開編輯時畫面的資料世代（F11）。 */
    val generation: Long = FinanceSnapshot.NO_GENERATION,
) {
    val isNew: Boolean get() = draft.id == 0L
}

/** 帳單校正（R-CARD-23）的輸入視窗。 */
data class BillEditor(
    val cardId: Long,
    val cardName: String,
    val cycle: CardRules.Cycle,
    /** App 對這一期帳單的估計。 */
    val estimate: Money,
    val amount: String,
    val minimum: String,
    /** 這一期已經輸入過帳單（可以刪除校正）。 */
    val existing: Boolean,
    val error: String? = null,
    /** 打開時畫面的資料世代（F11）。 */
    val generation: Long = FinanceSnapshot.NO_GENERATION,
)

data class AccountsUiState(
    val loading: Boolean = true,
    val overview: AccountsOverview? = null,
    /** 可以當扣款帳戶的流動帳戶。 */
    val payAccounts: List<Account> = emptyList(),
    val editor: AccountEditor? = null,
    val bill: BillEditor? = null,
    val message: String? = null,
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repository: FinanceRepository,
) : ViewModel() {

    private data class Local(val editor: AccountEditor? = null, val bill: BillEditor? = null, val message: String? = null)

    private val local = MutableStateFlow(Local())

    /** 畫面正在顯示的資料（F11）：寫入時帶著它的世代，資料換過就會被拒絕。 */
    @Volatile private var shown: FinanceSnapshot? = null

    private val shownGeneration get() = shown?.generation ?: FinanceSnapshot.NO_GENERATION

    val state: StateFlow<AccountsUiState> = combine(repository.snapshot, local, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    private fun build(snapshot: FinanceSnapshot, local: Local) = AccountsUiState(
        loading = false,
        overview = AccountSummaryCalculator.overview(snapshot),
        payAccounts = snapshot.activeAccounts.filter { it.kind.isLiquid },
        editor = local.editor,
        bill = local.bill,
        message = local.message,
    ).also { shown = snapshot }

    fun startNew(kind: AccountKind = AccountKind.CASH) = local.update {
        it.copy(editor = AccountEditor(AccountDraft(kind = kind), generation = shownGeneration))
    }

    fun edit(account: Account) = local.update {
        val draft = AccountForm.fromAccount(account)
        it.copy(
            editor = AccountEditor(
                draft,
                showAdvanced = draft.scheduleEnabled || draft.loanEnabled || draft.creditLimit.isNotBlank() || draft.statementDay.isNotBlank(),
                generation = shownGeneration,
            ),
        )
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
        viewModelScope.launch(WriteGuard) {
            val accounts = repository.snapshot.first().accounts
            val result = AccountForm.validate(editor.draft, accounts)
            if (!result.ok) {
                local.update { it.copy(editor = editor.copy(errors = result.errors, showAdvanced = editor.showAdvanced || advancedError(result.errors))) }
                return@launch
            }
            val account = result.account!!
            val sortOrder = if (editor.isNew) (accounts.maxOfOrNull { it.sortOrder } ?: 0) + 1 else account.sortOrder
            repository.saveAccount(account.copy(sortOrder = sortOrder), result.balance, editor.generation)
            local.update { it.copy(editor = null, message = if (editor.isNew) "已新增「${account.name}」" else "已更新「${account.name}」") }
        }
    }

    fun archive() {
        val editor = local.value.editor ?: return
        if (editor.isNew) return
        viewModelScope.launch(WriteGuard) {
            repository.setAccountArchived(editor.draft.id, true, editor.generation)
            local.update { it.copy(editor = null, message = "已封存「${editor.draft.name}」") }
        }
    }

    // ---- 帳單校正（R-CARD-23） ----

    /** 打開某張卡最近一期的帳單校正；已經輸入過的帶入上次的金額，否則帶入 App 的估計。 */
    fun openBill(cardId: Long) {
        val snapshot = shown ?: return
        val card = snapshot.account(cardId) ?: return
        val preview = BillCorrection.preview(snapshot, card) ?: return
        local.update {
            it.copy(
                bill = BillEditor(
                    cardId = card.id,
                    cardName = card.name,
                    cycle = preview.cycle,
                    estimate = preview.estimate,
                    amount = (preview.existing?.amount ?: preview.estimate).toString(),
                    minimum = preview.existing?.minimumPayment?.toString().orEmpty(),
                    existing = preview.existing != null,
                    generation = snapshot.generation,
                ),
            )
        }
    }

    fun updateBill(transform: (BillEditor) -> BillEditor) = local.update { current ->
        current.copy(bill = current.bill?.let(transform)?.copy(error = null))
    }

    fun closeBill() = local.update { it.copy(bill = null) }

    fun saveBill() {
        val bill = local.value.bill ?: return
        viewModelScope.launch(WriteGuard) {
            val snapshot = repository.snapshot.first()
            val card = snapshot.account(bill.cardId) ?: return@launch
            val amount = MoneyFormat.parse(bill.amount)
            val minimum = bill.minimum.takeIf { it.isNotBlank() }?.let { MoneyFormat.parse(it) ?: -1L }
            BillCorrection.validate(snapshot, card, amount, minimum)?.let { error ->
                local.update { it.copy(bill = bill.copy(error = error)) }
                return@launch
            }
            repository.saveBillCorrection(BillCorrection.correct(snapshot, card, bill.cycle, amount!!, minimum), bill.generation)
            local.update { it.copy(bill = null, message = "已照帳單校正「${card.name}」") }
        }
    }

    fun deleteBill() {
        val bill = local.value.bill ?: return
        viewModelScope.launch(WriteGuard) {
            repository.deleteBillCorrection(bill.cardId, bill.cycle.yearMonth, bill.generation)
            local.update { it.copy(bill = null, message = "已刪除「${bill.cardName}」這一期的帳單校正") }
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    private fun advancedError(errors: Map<String, String>): Boolean =
        errors.keys.any { it != AccountForm.Field.NAME && it != AccountForm.Field.BALANCE }
}

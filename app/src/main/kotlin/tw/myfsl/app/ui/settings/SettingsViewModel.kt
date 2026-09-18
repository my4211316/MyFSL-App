package tw.myfsl.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import tw.myfsl.app.core.data.CrashLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import tw.myfsl.app.core.data.BackupCodec
import tw.myfsl.app.core.data.BackupReadResult
import tw.myfsl.app.core.data.BackupSummary
import tw.myfsl.app.core.data.FinanceRepository
import tw.myfsl.app.core.domain.SettingsDraft
import tw.myfsl.app.core.domain.SettingsForm
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
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

data class SettingsUiState(
    val loading: Boolean = true,
    val draft: SettingsDraft? = null,
    val errors: Map<String, String> = emptyMap(),
    val liquidAccounts: List<Account> = emptyList(),
    val cards: List<Account> = emptyList(),
    val autoCash: String? = null,
    val autoTransfer: String? = null,
    val saved: Boolean = false,
    val message: String? = null,
    val busy: Boolean = false,
    val pendingRestore: BackupSummary? = null,
    val crashLog: String? = null,
    val lastBackup: String = "",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: FinanceRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val crash = MutableStateFlow(CrashLog.read(context))

    fun clearCrashLog() {
        CrashLog.clear(context)
        crash.value = null
    }

    private data class Local(
        val draft: SettingsDraft? = null,
        val errors: Map<String, String> = emptyMap(),
        val saved: Boolean = false,
        val message: String? = null,
        val busy: Boolean = false,
        val pendingRestore: BackupReadResult.Ok? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<SettingsUiState> = combine(repository.snapshot, local, crash) { snapshot, l, crashLog ->
        val accounts = snapshot.activeAccounts
        SettingsUiState(
            loading = false,
            draft = l.draft ?: SettingsForm.fromSettings(snapshot.settings),
            errors = l.errors,
            liquidAccounts = accounts.filter { it.kind.isLiquid },
            cards = snapshot.activeCards,
            autoCash = SettingsForm.autoAccountName(accounts, AccountKind.CASH),
            autoTransfer = SettingsForm.autoAccountName(accounts, AccountKind.BANK),
            saved = l.saved,
            message = l.message,
            busy = l.busy,
            pendingRestore = l.pendingRestore?.summary,
            crashLog = crashLog,
            lastBackup = snapshot.settings.lastBackupEpochDay?.let { day ->
                val date = LocalDate.ofEpochDay(day)
                "上次備份：${date.year}/${date.monthValue}/${date.dayOfMonth}"
            } ?: "還沒備份過",
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun change(transform: (SettingsDraft) -> SettingsDraft) = local.update { current ->
        val draft = current.draft ?: state.value.draft ?: return@update current
        current.copy(draft = transform(draft), saved = false)
    }

    fun save() {
        val draft = state.value.draft ?: return
        viewModelScope.launch {
            val snapshot = repository.snapshot.first()
            val result = SettingsForm.validate(draft, snapshot.settings, snapshot.accounts)
            if (!result.ok) {
                local.update { it.copy(errors = result.errors) }
                return@launch
            }
            repository.saveSettings(result.settings!!)
            local.update { Local(saved = true, message = "設定已儲存") }
        }
    }

    fun loadSample() {
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            repository.installSample()
            local.update { Local(message = "已載入示意資料") }
        }
    }

    fun clearAll() {
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            repository.clearAll()
            local.update { Local(message = "已清除所有資料") }
        }
    }

    // ---- 完整備份 ----

    suspend fun backupBytes(): ByteArray = BackupCodec.encode(repository.exportBackup()).toByteArray(Charsets.UTF_8)

    fun backupSaved() {
        viewModelScope.launch { repository.markBackedUp() }
        local.update { it.copy(message = "已匯出完整備份") }
    }

    fun onBackupLoaded(bytes: ByteArray) {
        when (val result = BackupCodec.decode(bytes.toString(Charsets.UTF_8))) {
            is BackupReadResult.Error -> local.update { it.copy(message = result.message) }
            is BackupReadResult.Ok -> local.update { it.copy(pendingRestore = result) }
        }
    }

    fun cancelRestore() = local.update { it.copy(pendingRestore = null) }

    fun confirmRestore() {
        val pending = local.value.pendingRestore ?: return
        local.update { it.copy(busy = true, pendingRestore = null) }
        viewModelScope.launch {
            runCatching { repository.restoreBackup(pending.file) }
                .onSuccess { local.update { Local(message = "已從備份還原：${pending.summary.text}") } }
                .onFailure { e -> local.update { Local(message = "還原失敗，資料沒有變動（${e.message}）") } }
        }
    }

    fun readError(message: String) = local.update { it.copy(message = message) }

    fun dismissMessage() = local.update { it.copy(message = null) }
}

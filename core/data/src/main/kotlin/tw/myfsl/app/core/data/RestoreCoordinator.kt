package tw.myfsl.app.core.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 資料的還原狀態；只有 [READY] 時可以做帳務操作。 */
enum class RestoreState {
    /** 開 App 時正在檢查（並放回）上次沒完成的還原。 */
    CHECKING,

    /** 可以正常使用。 */
    READY,

    /** 正在從備份還原：畫面擋住操作，帳務寫入排隊到還原結束。 */
    RESTORING,

    /** 還原或放回沒有完成，資料可能不一致：不能操作，只能重試。紀錄檔保留。 */
    RECOVERY_FAILED,
}

/** 還原要動到的兩個地方：資料庫與設定。資料層實作；測試用假的模擬中斷與失敗。 */
interface RestoreTarget {
    /** 目前的完整資料（含設定）。 */
    suspend fun export(): BackupFile

    /** 資料庫在同一個交易內整份換掉。 */
    suspend fun replaceDatabase(file: BackupFile)

    /** 設定換成備份裡的；備份沒有到期項目起算日時用 [autoPostFromFallback]。 */
    suspend fun replaceSettings(file: BackupFile, autoPostFromFallback: Long?)
}

/**
 * 從備份還原的流程（R-DATA-06，F11）。
 *
 * 1. 先把目前資料寫進 [RestoreJournal]（同步到磁碟、讀回確認），才開始改資料庫。
 * 2. 資料庫與設定都成功後寫「已完成」標記，再清紀錄。標記寫好之後，留下的紀錄只是待清理，下次開啟只清理、不放回；
 *    清理失敗不影響資料（標記保證不會被放回），下次開啟再清。
 * 3. 途中出錯：馬上放回；放回成功一樣寫「已完成」標記再清。放回或標記失敗時進入 [RestoreState.RECOVERY_FAILED]，紀錄保留。
 * 4. 途中程式被關掉：紀錄在、標記不在；下次開 App 先呼叫 [recover] 放回，放回完成前是 [RestoreState.CHECKING]。
 * 5. 讀紀錄、解析、放回、寫標記任何一步失敗都進入 [RestoreState.RECOVERY_FAILED]（畫面提供重試），不會卡在檢查中。
 *    放回是整份覆蓋，重試幾次結果都一樣。
 *
 * [maintenance] 是和所有帳務寫入共用的鎖：還原與放回期間，其他寫入排隊等候，不會寫進一半新一半舊的資料，也不會被整份覆蓋掉。
 */
class RestoreCoordinator(
    private val journal: RestoreJournal,
    private val target: RestoreTarget,
    private val maintenance: Mutex = Mutex(),
    private val today: () -> Long,
) {
    private val _state = MutableStateFlow(RestoreState.CHECKING)
    val state: StateFlow<RestoreState> = _state

    /** 上次還原的處理結果。 */
    enum class Recovery { NONE, RECOVERED, FAILED }

    /** 開 App 時呼叫（放回失敗後按重試也是這個）：有沒完成的還原就放回還原前的資料。不會丟例外。 */
    suspend fun recover(): Recovery = withContext(NonCancellable) {
        maintenance.withLock {
            _state.value = RestoreState.CHECKING
            try {
                if (journal.isCommitted()) {
                    // 上次已經完成，只差清理：清不掉也沒關係，資料是一致的，下次再清。
                    runCatching { journal.finish() }
                    _state.value = RestoreState.READY
                    return@withLock Recovery.NONE
                }
                val text = journal.pending()
                if (text == null) {
                    _state.value = RestoreState.READY
                    return@withLock Recovery.NONE
                }
                val previous = (BackupCodec.decode(text) as? BackupReadResult.Ok)?.file
                    ?: throw IllegalStateException("還原紀錄無法讀取")
                apply(previous, autoPostFromFallback = null)
                settle()
                _state.value = RestoreState.READY
                Recovery.RECOVERED
            } catch (e: Exception) {
                _state.value = RestoreState.RECOVERY_FAILED
                Recovery.FAILED
            }
        }
    }

    /**
     * 用備份取代目前所有資料。失敗時丟出原本的錯誤：資料已經放回（狀態 READY），
     * 或放回／寫標記失敗（狀態 RECOVERY_FAILED，畫面只能重試）。
     */
    suspend fun restore(file: BackupFile) = withContext(NonCancellable) {
        maintenance.withLock {
            check(_state.value == RestoreState.READY) { "資料還在檢查中，請稍候再還原" }
            // 有留下的紀錄或標記（連讀都讀不到也算）：先整理完才能再還原。
            val leftover = try {
                journal.pending() != null || journal.isCommitted()
            } catch (e: Exception) {
                true
            }
            check(!leftover) { "上次的還原還沒整理完，請重新開啟 App" }
            _state.value = RestoreState.RESTORING
            val previous = try {
                target.export().also { journal.begin(BackupCodec.encode(it)) }
            } catch (e: Exception) {
                // 紀錄沒寫成功：資料都還沒動。
                runCatching { journal.finish() }
                _state.value = RestoreState.READY
                throw e
            }
            try {
                apply(file, autoPostFromFallback = today())
            } catch (e: Exception) {
                try {
                    apply(previous, autoPostFromFallback = null)
                    settle()
                } catch (rollback: Exception) {
                    e.addSuppressed(rollback)
                    _state.value = RestoreState.RECOVERY_FAILED
                    throw e
                }
                _state.value = RestoreState.READY
                throw e
            }
            try {
                settle()
            } catch (e: Exception) {
                // 資料是新的，但沒辦法記下「已完成」：不開放操作，重試時會放回還原前的資料（一致的狀態）。
                _state.value = RestoreState.RECOVERY_FAILED
                throw e
            }
            _state.value = RestoreState.READY
        }
    }

    /** 資料已經一致：先寫「已完成」標記（失敗就丟例外），再清理（失敗不影響，下次開啟再清）。 */
    private fun settle() {
        journal.markCommitted()
        runCatching { journal.finish() }
    }

    private suspend fun apply(file: BackupFile, autoPostFromFallback: Long?) {
        target.replaceDatabase(file)
        target.replaceSettings(file, autoPostFromFallback)
    }
}

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

    /** 正在從備份還原。 */
    RESTORING,

    /** 上次還原沒完成，而且放回失敗：資料可能不一致，不能操作，要重試。紀錄檔保留。 */
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
 * 2. 資料庫與設定都成功才刪紀錄。
 * 3. 途中出錯：馬上放回；放回也失敗時紀錄保留，狀態為 [RestoreState.RECOVERY_FAILED]。
 * 4. 途中程式被關掉：紀錄留在磁碟上；下次開 App 先呼叫 [recover] 放回，放回完成前狀態為 [RestoreState.CHECKING]，
 *    畫面不開放帳務操作。放回再中斷或失敗時紀錄仍保留，可以重試（放回是整份覆蓋，重做幾次結果都一樣）。
 */
class RestoreCoordinator(
    private val journal: RestoreJournal,
    private val target: RestoreTarget,
    private val today: () -> Long,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(RestoreState.CHECKING)
    val state: StateFlow<RestoreState> = _state

    /** 上次還原的處理結果。 */
    enum class Recovery { NONE, RECOVERED, FAILED }

    /** 開 App 時呼叫（放回失敗後也可以重試）：有沒完成的還原就放回還原前的資料。 */
    suspend fun recover(): Recovery = withContext(NonCancellable) {
        mutex.withLock {
            val text = journal.pending()
            if (text == null) {
                _state.value = RestoreState.READY
                return@withLock Recovery.NONE
            }
            _state.value = RestoreState.CHECKING
            val previous = (BackupCodec.decode(text) as? BackupReadResult.Ok)?.file
            val ok = previous != null && try {
                apply(previous, autoPostFromFallback = null)
                true
            } catch (e: Exception) {
                false
            }
            if (ok) {
                journal.finish()
                _state.value = RestoreState.READY
                Recovery.RECOVERED
            } else {
                _state.value = RestoreState.RECOVERY_FAILED
                Recovery.FAILED
            }
        }
    }

    /** 用備份取代目前所有資料；失敗時丟出原本的錯誤（資料已放回，或放回失敗而進入 [RestoreState.RECOVERY_FAILED]）。 */
    suspend fun restore(file: BackupFile) = withContext(NonCancellable) {
        mutex.withLock {
            check(journal.pending() == null) { "上次的還原還沒放回，請重新開啟 App" }
            val before = _state.value
            _state.value = RestoreState.RESTORING
            val previous = try {
                target.export().also { journal.begin(BackupCodec.encode(it)) }
            } catch (e: Exception) {
                // 紀錄沒寫成功：資料都還沒動。
                journal.finish()
                _state.value = before
                throw e
            }
            try {
                apply(file, autoPostFromFallback = today())
            } catch (e: Exception) {
                try {
                    apply(previous, autoPostFromFallback = null)
                } catch (rollback: Exception) {
                    e.addSuppressed(rollback)
                    _state.value = RestoreState.RECOVERY_FAILED
                    throw e
                }
                journal.finish()
                _state.value = RestoreState.READY
                throw e
            }
            journal.finish()
            _state.value = RestoreState.READY
        }
    }

    private suspend fun apply(file: BackupFile, autoPostFromFallback: Long?) {
        target.replaceDatabase(file)
        target.replaceSettings(file, autoPostFromFallback)
    }
}

package tw.myfsl.app.core.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tw.myfsl.app.core.model.FinanceSnapshot

/**
 * 所有帳務寫入的共同入口（F11）。和 [RestoreCoordinator] 共用同一把 [maintenance] 鎖。
 *
 * 拿到鎖之後、動資料之前依序確認：
 * 1. 還原狀態是 [RestoreState.READY]：還原中、還原或放回沒有完成時都不能寫。
 *    在還原期間排隊的寫入，如果還原最後失敗，也會在這裡被拒絕。
 * 2. 呼叫端帶來的資料世代 [run] 的 `expected` 和現在一致：還原、放回、清除、載入示意資料之後，
 *    舊畫面發出的操作（用舊的 id）一律拒絕，不會改到別筆資料。`expected` 為 null 表示操作不牽涉任何既有資料的 id。
 *
 * 不符合時不執行寫入，送出說明給 [onRejected]，並丟 [WriteRejectedException]。
 */
class WriteGate(
    private val maintenance: Mutex,
    private val state: () -> RestoreState,
    /** 目前的資料世代；資料庫與設定不一致時為 [FinanceSnapshot.NO_GENERATION]。只在持有鎖時呼叫。 */
    private val currentGeneration: suspend () -> Long,
    private val onRejected: (String) -> Unit = {},
) {
    suspend fun <T> run(expected: Long?, block: suspend () -> T): T = maintenance.withLock {
        if (state() != RestoreState.READY) reject(NOT_READY)
        if (expected != null) {
            val current = currentGeneration()
            if (expected == FinanceSnapshot.NO_GENERATION || current == FinanceSnapshot.NO_GENERATION || expected != current) reject(STALE)
        }
        block()
    }

    private fun reject(message: String): Nothing {
        onRejected(message)
        throw WriteRejectedException(message)
    }

    companion object {
        const val NOT_READY = "正在還原資料或還原沒有完成，這次的變更沒有寫入"
        const val STALE = "資料剛從備份還原或清除過，畫面上的資料已經不是最新的；請重新確認後再操作"
    }
}

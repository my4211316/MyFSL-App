package tw.myfsl.app.core.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tw.myfsl.app.core.model.FinanceSnapshot

/**
 * 所有帳務寫入的共同入口（R-DATA-06 ⑨⑩）。和 [RestoreCoordinator] 共用同一把 [maintenance] 鎖。
 *
 * 一般寫入 [run]：拿到鎖之後、動資料之前依序確認
 * 1. 還原狀態是 [RestoreState.READY]：還原中、還原或放回沒有完成時都不能寫。
 *    在還原期間排隊的寫入，如果還原最後失敗，也會在這裡被拒絕。
 * 2. 資料庫與設定的世代一致（[currentGeneration] 不是 [FinanceSnapshot.NO_GENERATION]）：**不管有沒有帶預期世代都要檢查**；
 *    兩邊不一致表示資料正在更新或上次更新沒寫完，一般寫入一律不做。
 * 3. 有帶預期世代時和目前一致：還原、放回、清除、載入示意資料之後，舊畫面發出的操作（用舊的 id）一律拒絕，不會改到別筆資料。
 *    `expected` 為 null 只表示操作不牽涉任何既有資料的 id，並不豁免第 2 點。
 *
 * 整份替換 [replacingAll]：只給「清除全部資料」「載入示意資料」與開 App 時的世代對齊用。只確認 READY，
 * 允許在兩份世代不一致時執行，因為這些操作不引用任何既有 id，而且結束時會把資料庫與設定寫成同一個新世代（修復不一致）。
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
        val current = currentGeneration()
        if (current == FinanceSnapshot.NO_GENERATION) reject(INCONSISTENT)
        if (expected != null && expected != current) reject(STALE)
        block()
    }

    /** 整份替換資料的維護入口（見類別說明）；[block] 結束時必須讓兩份世代一致。 */
    suspend fun <T> replacingAll(block: suspend () -> T): T = maintenance.withLock {
        if (state() != RestoreState.READY) reject(NOT_READY)
        block()
    }

    private fun reject(message: String): Nothing {
        onRejected(message)
        throw WriteRejectedException(message)
    }

    companion object {
        const val NOT_READY = "正在還原資料或還原沒有完成，這次的變更沒有寫入"
        const val INCONSISTENT = "資料正在更新或上次更新沒有完成，這次的變更沒有寫入；請重新開啟 App"
        const val STALE = "資料剛從備份還原或清除過，畫面上的資料已經不是最新的；請重新確認後再操作"
    }
}

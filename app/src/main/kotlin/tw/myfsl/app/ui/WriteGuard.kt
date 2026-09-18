package tw.myfsl.app.ui

import kotlinx.coroutines.CoroutineExceptionHandler
import tw.myfsl.app.core.data.WriteRejectedException

/**
 * 發起寫入的協程都帶著它（F11）：資料層拒絕寫入（還原中、還原沒完成、畫面上的資料已經不是最新的世代）時，
 * 接住 [WriteRejectedException] 不讓 App 閃退；說明由 MyFslApp 從 FinanceRepository.notices 顯示。
 * 其他錯誤照舊交給執行緒的預設處理（當機紀錄照常寫入）。
 */
val WriteGuard = CoroutineExceptionHandler { _, error ->
    if (error !is WriteRejectedException) {
        val thread = Thread.currentThread()
        thread.uncaughtExceptionHandler?.uncaughtException(thread, error) ?: throw error
    }
}

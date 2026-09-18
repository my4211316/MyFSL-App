package tw.myfsl.app.core.data

/**
 * 資料層拒絕這次寫入（F11）：還原中、還原沒有完成，或畫面上的資料已經不是最新的世代。
 * 資料沒有任何變動；說明另外由 [FinanceRepository.notices] 送給畫面顯示。
 */
class WriteRejectedException(message: String) : IllegalStateException(message)

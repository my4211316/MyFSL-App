package tw.myfsl.app.core.data

/**
 * 整份替換資料（清除全部、載入示意資料、世代對齊）沒有完成（R-DATA-06 ⑪）。
 * 原本的錯誤在 [cause]；兩份世代因此不一致時，[WriteGate.maintenanceFailed] 會變成 true，一般寫入全部擋住，直到重試對齊成功。
 */
class MaintenanceFailedException(message: String, cause: Throwable) : IllegalStateException(message, cause)

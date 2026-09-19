package tw.myfsl.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 從通知打開 App 時要去的地方（R-REM-01）。畫面處理完就清掉。 */
object DeepLinks {
    private val _pendingBill = MutableStateFlow<Long?>(null)

    /** 要打開帳單校正的卡片。 */
    val pendingBill: StateFlow<Long?> = _pendingBill

    fun openBill(cardId: Long) {
        _pendingBill.value = cardId
    }

    fun consumeBill() {
        _pendingBill.value = null
    }
}

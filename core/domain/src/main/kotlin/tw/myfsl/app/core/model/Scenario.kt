package tw.myfsl.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** 試算情境：在現況（基準線）上疊加一組變動。 */
data class Scenario(
    val id: Long = 0,
    val name: String,
    val createdOn: LocalDate,
    val changes: List<ScenarioChange>,
    val note: String = "",
)

/** 期別一律以 [Period.index]（年 × 12 + 月 − 1）表示，方便序列化（R-PER-01）。 */
@Serializable
sealed interface ScenarioChange {

    /** 調整項目金額，percent = −25 代表減少 25%。 */
    @Serializable
    @SerialName("adjust")
    data class AdjustItems(
        val itemIds: List<Long>,
        val percent: Double,
        val fromIndex: Int,
    ) : ScenarioChange

    /** 新增一筆貸款：撥款入帳，下個月起依攤還方式繳款。 */
    @Serializable
    @SerialName("loan")
    data class AddLoan(
        val name: String,
        val principal: Money,
        val annualRatePercent: Double,
        val months: Int,
        val method: RepaymentMethod,
        val startIndex: Int,
        val depositAccountId: Long,
        val payAccountId: Long,
    ) : ScenarioChange

    /** 在指定期別一次清償負債（以當時欠款計算），可同時停止原本對這些帳戶的排程繳款。 */
    @Serializable
    @SerialName("payoff")
    data class PayOffDebts(
        val accountIds: List<Long>,
        val fromAccountId: Long,
        val atIndex: Int,
        val stopScheduledPayments: Boolean = true,
        /** 一併結清未到期的分期（多數銀行提前清償會這樣算）。 */
        val includeInstallments: Boolean = true,
    ) : ScenarioChange

    /** 把項目的某支付方式改成另一種，例如刷卡消費改現金。 */
    @Serializable
    @SerialName("method")
    data class ChangeMethod(
        val itemIds: List<Long>,
        val fromMethod: PaymentMethod,
        val toMethod: PaymentMethod,
        val fromIndex: Int,
    ) : ScenarioChange

    /** 一次性收入或支出：收入指定入帳帳戶，支出指定支付方式。 */
    @Serializable
    @SerialName("oneoff")
    data class OneOff(
        val name: String,
        val atIndex: Int,
        val type: FlowType,
        val amount: Money,
        val accountId: Long? = null,
        val method: PaymentMethod? = null,
    ) : ScenarioChange

    /** 從某期起停止某個項目。 */
    @Serializable
    @SerialName("stop")
    data class StopItem(
        val itemId: Long,
        val fromIndex: Int,
    ) : ScenarioChange
}

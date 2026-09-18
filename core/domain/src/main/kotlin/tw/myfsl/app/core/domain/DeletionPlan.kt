package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PostingKeys

/**
 * 刪除一筆記帳要做的所有事（R-REC-EDIT-05、R-REC-EDIT-07）。
 * 由規則算出來，資料層在同一個交易內照做；這樣刪除的連動可以不靠資料庫直接測試。
 */
data class DeletionPlan(
    /** 依 id 刪的記帳。 */
    val ledgerIds: List<Long> = emptyList(),
    /** 依識別碼刪的記帳（同一組：貸款本金與利息、分期同一期的本金與手續費）。 */
    val ledgerKeys: List<String> = emptyList(),
    /** 要從「已處理」清掉的識別碼（含第一次利息記下的既有卡循原值）。 */
    val postedKeys: List<String> = emptyList(),
    /** 整筆取消的分期：消費、已入帳各期、分期本身、這個分期的「已處理」識別碼一起刪。 */
    val cancelInstallmentId: Long? = null,
    /** 改回未付清的延期款。 */
    val unsettleDeferralId: Long? = null,
    /** 撤銷「已完成」的計畫列月份（到期確認的補記全刪掉時）。 */
    val reopenActual: ReopenActual? = null,
    /** 剩餘期數加回一期的貸款。 */
    val addLoanMonthTo: Long? = null,
    /** 恢復的既有卡循：(卡片, 原本的金額)。 */
    val restoreRevolving: Pair<Long, Money>? = null,
) {
    data class ReopenActual(val itemId: Long, val method: PaymentMethod?, val year: Int, val month: Int)
}

object Deletion {

    fun plan(snapshot: FinanceSnapshot, entry: LedgerEntry): DeletionPlan {
        val key = entry.postingKey
        val installmentId = entry.installmentId
        return when {
            // 只有分期消費本身才整筆取消（F09）。
            installmentId != null && entry.isInstallmentPurchase -> DeletionPlan(cancelInstallmentId = installmentId)

            key != null && key.startsWith(PostingKeys.DEFERRAL) ->
                DeletionPlan(ledgerIds = listOf(entry.id), unsettleDeferralId = key.removePrefix(PostingKeys.DEFERRAL).toLongOrNull())

            entry.source == EntrySource.CONFIRMED && entry.itemId != null -> {
                val others = snapshot.ledger.any {
                    it.id != entry.id && it.source == EntrySource.CONFIRMED && it.postingKey == null &&
                        it.itemId == entry.itemId && it.method == entry.method &&
                        it.date.year == entry.date.year && it.date.monthValue == entry.date.monthValue
                }
                DeletionPlan(
                    ledgerIds = listOf(entry.id),
                    reopenActual = if (others) null else DeletionPlan.ReopenActual(entry.itemId, entry.method, entry.date.year, entry.date.monthValue),
                )
            }

            // 到期記下的：同一組一起刪，回到本月到期清單。某一期分期只刪那一期（F09）。
            key != null -> {
                val group = DueItems.groupKeys(key)
                val interestKey = group.firstOrNull { it.startsWith(PostingKeys.CARD_INTEREST) }
                val marker = interestKey?.let { k -> snapshot.postedKeys.firstOrNull { it.startsWith("${PostingKeys.REVOLVING}$k:") } }
                val restore = marker?.let { m ->
                    val cardId = DueItems.cardIdOf(interestKey)
                    val amount = m.substringAfterLast(':').toLongOrNull()
                    if (cardId != null && amount != null) cardId to amount else null
                }
                DeletionPlan(
                    ledgerIds = listOf(entry.id),
                    ledgerKeys = group,
                    postedKeys = group + listOfNotNull(marker),
                    addLoanMonthTo = DueItems.loanIdOf(key),
                    restoreRevolving = restore,
                )
            }

            else -> DeletionPlan(ledgerIds = listOf(entry.id))
        }
    }
}

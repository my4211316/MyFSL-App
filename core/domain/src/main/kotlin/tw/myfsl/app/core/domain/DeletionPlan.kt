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
    /** 要從「已處理」清掉的識別碼。 */
    val postedKeys: List<String> = emptyList(),
    /** 整筆取消的分期：消費、已入帳各期、分期本身、這個分期的「已處理」識別碼一起刪。 */
    val cancelInstallmentId: Long? = null,
    /** 改回未付清的延期款。 */
    val unsettleDeferralId: Long? = null,
    /** 撤銷「已完成」的計畫列月份（到期確認的補記全刪掉時）。 */
    val reopenActual: ReopenActual? = null,
    /** 剩餘期數加回一期的貸款。 */
    val addLoanMonthTo: Long? = null,
    /** 刪掉的帳單校正：(卡片, 結帳年月)（R-CARD-23）。 */
    val removeStatement: Pair<Long, java.time.YearMonth>? = null,
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

            // 帳單校正的差額：整筆校正一起刪，回到 App 的估計；校正時包含的利息回到本月到期（R-CARD-23）。
            key != null && key.startsWith(PostingKeys.STATEMENT) -> BillCorrection.removal(snapshot, key)?.let { (cardId, ym) ->
                val covered = snapshot.statementOf(cardId, ym)?.coversInterest == true
                DeletionPlan(
                    ledgerIds = listOf(entry.id),
                    ledgerKeys = listOf(key),
                    postedKeys = if (covered) listOf(DueItems.cardInterestKey(cardId, ym)) else emptyList(),
                    removeStatement = cardId to ym,
                )
            } ?: DeletionPlan(ledgerIds = listOf(entry.id))

            // 到期記下的：同一組一起刪，回到本月到期清單。某一期分期只刪那一期（F09）。
            key != null -> {
                val group = DueItems.groupKeys(key)
                DeletionPlan(
                    ledgerIds = listOf(entry.id),
                    ledgerKeys = group,
                    postedKeys = group,
                    addLoanMonthTo = DueItems.loanIdOf(key),
                )
            }

            else -> DeletionPlan(ledgerIds = listOf(entry.id))
        }
    }
}

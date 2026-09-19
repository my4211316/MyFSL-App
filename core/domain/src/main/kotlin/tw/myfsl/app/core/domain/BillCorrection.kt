package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.CardStatement
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PostingKeys
import java.time.YearMonth

/**
 * 帳單校正（R-CARD-23）：結帳後輸入這一期的帳單金額（與選填的最低應繳），以帳單為準。
 *
 * - App 的估計 = 結帳日（含）以前的欠款，加上這一期還沒記下的循環利息估計。
 * - 帳單金額和「結帳日的欠款」不同時，差額記成一筆「帳單差額」（識別碼 `stmt:<卡片>:<結帳年月>`，日期是結帳日）。
 *   這一期的循環利息還沒記下時，利息視為已經包含在帳單裡（識別碼設成已處理），不會再列出一次。
 * - 同一期再校正一次：取代前一次（先扣掉前一次的差額再算）。刪除校正：差額、帳單紀錄一起刪，利息回到本月到期。
 */
object BillCorrection {

    /** 校正畫面顯示用：這一期的結帳日、截止日、App 的估計、目前已輸入的帳單。 */
    data class Preview(
        val cycle: CardRules.Cycle,
        val estimate: Money,
        val existing: CardStatement?,
    )

    /** 要寫入的東西。 */
    data class Result(
        val statement: CardStatement,
        /** 差額；和估計相同時為 null。 */
        val entry: LedgerEntry?,
        /** 要刪掉的前一次差額的識別碼（再校正時）。 */
        val replaceKey: String,
        /** 包含在帳單裡、要設成已處理的利息識別碼。 */
        val coveredInterestKey: String?,
    )

    fun key(cardId: Long, ym: YearMonth) = "${PostingKeys.STATEMENT}$cardId:${DueItems.ymKey(ym)}"

    /** 識別碼裡的（卡片, 結帳年月）。 */
    fun removal(snapshot: FinanceSnapshot, key: String): Pair<Long, YearMonth>? {
        val parts = key.removePrefix(PostingKeys.STATEMENT).split(':')
        val cardId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val ym = parts.getOrNull(1)?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: return null
        return cardId to ym
    }

    /** 目前可以校正的一期：最近一次結帳（結帳日在今天以前）；不是依帳單繳款的卡為 null。 */
    fun preview(snapshot: FinanceSnapshot, card: Account, ym: YearMonth? = null): Preview? {
        if (!card.hasCardSchedule) return null
        val cycle = if (ym != null) {
            val (s, d) = CardRules.cycleDays(card)!!
            CardRules.cycle(ym, s, d)
        } else {
            CardRules.latestCycle(card, snapshot.today)
        } ?: return null
        return Preview(cycle, estimate(snapshot, card, cycle), snapshot.statementOf(card.id, cycle.yearMonth))
    }

    /** 拿掉這一期已經輸入的校正差額：記帳與目前欠款都扣回去。 */
    private fun withoutCorrection(snapshot: FinanceSnapshot, card: Account, ym: YearMonth): Pair<FinanceSnapshot, Money> {
        val key = key(card.id, ym)
        val previous = snapshot.ledger.filter { it.postingKey == key }
        // 前一次校正把這一期利息算進帳單時，利息的「已處理」也一起拿掉，估計才會包含利息。
        val covered = snapshot.statementOf(card.id, ym)?.takeIf { it.coversInterest }?.let { DueItems.cardInterestKey(card.id, ym) }
        val clean = snapshot.copy(ledger = snapshot.ledger - previous.toSet(), postedKeys = snapshot.postedKeys - setOfNotNull(covered))
        val base = CardRules.baseBalance(snapshot, card) - previous.sumOf { BalanceRules.effect(it, card.id, card.kind) }
        return clean to base
    }

    /** App 對這一期帳單的估計（不含這一期已經輸入的校正）。 */
    fun estimate(snapshot: FinanceSnapshot, card: Account, cycle: CardRules.Cycle): Money {
        val (clean, base) = withoutCorrection(snapshot, card, cycle.yearMonth)
        val ledger = clean.ledger
        val atStatement = CardRules.balanceAt(clean, card, base, ledger, emptyList(), cycle.statement)
        val interestKey = DueItems.cardInterestKey(card.id, cycle.yearMonth)
        val pendingInterest = if (DueItems.isRecorded(clean, interestKey)) 0L else DueItems.interestFor(clean, card, base, cycle, emptyList())
        return atStatement + pendingInterest
    }

    /** 檢查輸入；沒問題時回傳 null。 */
    fun validate(snapshot: FinanceSnapshot, card: Account, amount: Money?, minimum: Money?): String? {
        if (!card.hasCardSchedule) return "這張卡沒有設定依帳單繳款"
        if (amount == null || amount < 0) return "請輸入帳單金額"
        if (minimum != null && (minimum < 0 || minimum > amount)) return "最低應繳要在 0 到帳單金額之間"
        return null
    }

    fun correct(snapshot: FinanceSnapshot, card: Account, cycle: CardRules.Cycle, amount: Money, minimum: Money?): Result {
        val ym = cycle.yearMonth
        val key = key(card.id, ym)
        val (clean, base) = withoutCorrection(snapshot, card, ym)
        val atStatement = CardRules.balanceAt(clean, card, base, clean.ledger, emptyList(), cycle.statement)
        val interestKey = DueItems.cardInterestKey(card.id, ym)
        val existing = snapshot.statementOf(card.id, ym)
        // 這一期的利息還沒記下（或是前一次校正包含的）：視為已經在帳單裡。
        val covers = existing?.coversInterest == true || !DueItems.isRecorded(snapshot, interestKey)
        val diff = amount - atStatement
        val entry = if (diff != 0L) {
            LedgerEntry(
                date = cycle.statement, type = FlowType.EXPENSE, amount = diff, accountId = card.id,
                note = "${card.name} 帳單差額（帳單 ${MoneyFormat.currency(amount)}）",
                source = EntrySource.STATEMENT, postingKey = key,
            )
        } else {
            null
        }
        return Result(
            statement = CardStatement(card.id, ym.year, ym.monthValue, amount, minimum, coversInterest = covers),
            entry = entry,
            replaceKey = key,
            coveredInterestKey = if (covers) interestKey else null,
        )
    }
}

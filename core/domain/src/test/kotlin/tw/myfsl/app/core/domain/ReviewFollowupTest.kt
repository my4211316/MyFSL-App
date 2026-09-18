package tw.myfsl.app.core.domain

import org.junit.Assert.*
import org.junit.Test
import tw.myfsl.app.core.model.*
import java.time.LocalDate

class ReviewFollowupTest {
    @Test fun onlyFirstInterestInBatchMayRestoreOriginalRevolvingBalance() {
        val s = FinanceSnapshot.empty(LocalDate.of(2026,10,18)).copy(
            accounts = listOf(Account(1,"Bank",AccountKind.BANK,balance=100000),
                Account(2,"Card",AccountKind.CREDIT_CARD,balance=60000,
                    card=CardTerms(12.0,payMode=CardPayMode.FIXED,fixedPayment=2000,
                        payAccountId=1,payDay=15,revolvingBalance=20000))),
            settings=AppSettings(autoPostFrom=LocalDate.of(2026,9,1).toEpochDay(),transferAccountId=1))
        val dues=DueItems.list(s).filter { it.kind==DueKind.CARD_INTEREST }
        assertEquals(2,dues.size)
        val result=CheckInRules.build(s,CheckInInput(dues=dues.associate { it.key to DueDecision(DueCheck.PAID) }))
        val after=s.copy(ledger=result.entries.mapIndexed { i,e -> e.copy(id=(i+1).toLong()) },
            postedKeys=result.markers.toSet(), accounts=s.accounts.map {
                if(it.id==2L) it.copy(card=result.cardTerms.getValue(2L)) else it })
        val october=after.ledger.single { it.postingKey=="cardint:2:2026-10" }
        assertNull("Deleting October must not restore September's consumed revolving base",Deletion.plan(after,october).restoreRevolving)
    }

    // ---------- F10 批次補記的其他情況（複審後補） ----------

    private fun snapshot() = FinanceSnapshot.empty(LocalDate.of(2026, 10, 18)).copy(
        accounts = listOf(Account(1, "Bank", AccountKind.BANK, balance = 100000),
            Account(2, "Card", AccountKind.CREDIT_CARD, balance = 60000,
                card = CardTerms(12.0, payMode = CardPayMode.FIXED, fixedPayment = 2000,
                    payAccountId = 1, payDay = 15, revolvingBalance = 20000))),
        settings = AppSettings(autoPostFrom = LocalDate.of(2026, 9, 1).toEpochDay(), transferAccountId = 1))

    /** 資料層寫入本週檢查結果後的快照。 */
    private fun FinanceSnapshot.write(result: CheckInResult): FinanceSnapshot = copy(
        ledger = ledger + result.entries.mapIndexed { i, e -> e.copy(id = (ledger.size + i + 1).toLong()) },
        postedKeys = postedKeys + result.markers + result.skippedKeys,
        accounts = accounts.map { a -> result.cardTerms[a.id]?.let { a.copy(card = it) } ?: a },
    )

    private fun FinanceSnapshot.undo(plan: DeletionPlan): FinanceSnapshot = copy(
        ledger = ledger.filterNot { it.id in plan.ledgerIds || it.postingKey in plan.ledgerKeys },
        postedKeys = postedKeys - plan.postedKeys.toSet(),
        accounts = accounts.map { a ->
            plan.restoreRevolving?.takeIf { it.first == a.id }?.let { a.copy(card = a.card!!.copy(revolvingBalance = it.second)) } ?: a
        },
    )

    @Test fun batchOnlyFirstInterestUsesRevolvingAndOnlyItRestores() {
        val s = snapshot()
        val dues = DueItems.list(s).filter { it.kind == DueKind.CARD_INTEREST }
        assertEquals(listOf(true, false), dues.map { it.usesRevolving })
        // 9 月：20,000 × 1% = 200；10 月：以整筆欠款計息（9 月繳款後的欠款 × 1%）
        assertEquals(200L, dues[0].amount)
        val result = CheckInRules.build(s, CheckInInput(dues = dues.associate { it.key to DueDecision(DueCheck.PAID) }))
        assertEquals("只有 9 月那一期留下原值標記", listOf("revbal:cardint:2:2026-09:20000"), result.markers)
        val after = s.write(result)

        // 刪 9 月（真正用掉既有卡循的那一期）：恢復 20,000，重記仍是 200
        val sep = after.ledger.single { it.postingKey == "cardint:2:2026-09" }
        val plan = Deletion.plan(after, sep)
        assertEquals(2L to 20000L, plan.restoreRevolving)
        val undone = after.undo(plan)
        assertEquals(200L, DueItems.list(undone).single { it.key == "cardint:2:2026-09" }.amount)
    }

    @Test fun skippingFirstInterestMovesRevolvingToNextMonth() {
        val s = snapshot()
        val dues = DueItems.list(s).filter { it.kind == DueKind.CARD_INTEREST }
        val input = CheckInInput(dues = mapOf(dues[0].key to DueDecision(DueCheck.SKIP), dues[1].key to DueDecision(DueCheck.PAID)))
        val lines = CheckInRules.dueLines(s, input = input).filter { it.kind == DueKind.CARD_INTEREST }
        assertEquals("9 月略過，10 月才是第一次用既有卡循", listOf(true, true), lines.map { it.usesRevolving })
        val result = CheckInRules.build(s, input)
        assertEquals(listOf("revbal:cardint:2:2026-10:20000"), result.markers)
        val interest = result.entries.single { it.postingKey == "cardint:2:2026-10" }
        assertEquals("10 月用既有卡循：20,000 × 1%", 200L, interest.amount)
        val after = s.write(result)
        assertEquals(2L to 20000L, Deletion.plan(after, after.ledger.single { it.postingKey == "cardint:2:2026-10" }).restoreRevolving)
    }
}

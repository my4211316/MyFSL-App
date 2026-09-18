package tw.myfsl.app.core.domain

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.CARD_B
import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.CardTerms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 既有卡循：欠款中已在計息的部分。 */
class RevolvingBalanceTest {

    private val snapshot = SampleHousehold.snapshot()

    private fun withRevolving(a: Long?, b: Long?) = snapshot.copy(
        accounts = snapshot.accounts.map {
            when (it.id) {
                CARD_A -> it.copy(card = it.card!!.copy(revolvingBalance = a))
                CARD_B -> it.copy(card = it.card!!.copy(revolvingBalance = b))
                else -> it
            }
        },
    )

    private fun interests(s: tw.myfsl.app.core.model.FinanceSnapshot) =
        CashFlowEngine.run(BaselineBuilder.build(s)).periods.flatMap { p -> p.events.filter { it.event.interestRatePercent != null }.map { it.amount } }

    @Test fun `計息金額：有填取既有卡循（不超過欠款），沒填取整筆欠款`() {
        val terms = CardTerms(15.0)
        assertEquals(50_000L, CardRules.interestBase(50_000, terms))
        assertEquals(20_000L, CardRules.interestBase(50_000, terms.copy(revolvingBalance = 20_000)))
        assertEquals("超過欠款時以欠款為準", 50_000L, CardRules.interestBase(50_000, terms.copy(revolvingBalance = 80_000)))
        assertEquals(0L, CardRules.interestBase(-100, terms))
    }

    @Test fun `合計：任一張卡有填才合計，沒填的卡以整筆欠款計`() {
        val cards = withRevolving(10_000, null).accounts.filter { it.kind == AccountKind.CREDIT_CARD }
        val b = cards.first { it.id == CARD_B }
        assertEquals(10_000L + b.balance, CardRules.pooled(cards)!!.revolvingBalance)
        assertNull(CardRules.pooled(snapshot.accounts.filter { it.kind == AccountKind.CREDIT_CARD })!!.revolvingBalance)
    }

    @Test fun `試算：只影響第一次計息，之後沒繳清的欠款都計息`() {
        val base = interests(snapshot)
        val lower = interests(withRevolving(0, 0))
        assertTrue("原本第一次有利息", base.first() > 0)
        // 利息為 0 的那一期不會產生事件，所以少一筆；第二次起照常計息。
        assertEquals("兩張卡都沒有卡循，第一次不計息", base.size - 1, lower.size)
        assertTrue(lower.first() > 0)
        assertTrue(lower.sum() < base.sum())
    }

    @Test fun `帳戶頁當期利息用既有卡循計`() {
        val s = withRevolving(12_000, null)
        val card = AccountSummaryCalculator.overview(s).cards.first { it.account.id == CARD_A }
        assertEquals(CardRules.monthlyInterest(12_000, card.account.card!!.revolvingRatePercent), card.interest)
    }

    @Test fun `表單：既有卡循選填、不能超過欠款、存得回去`() {
        val draft = AccountDraft(
            name = "卡", kind = AccountKind.CREDIT_CARD, balance = "30000",
            revolvingEnabled = true, revolvingRate = "15", minPercent = "10", minFloor = "1000",
        )
        val empty = AccountForm.validate(draft, emptyList())
        assertTrue(empty.ok)
        assertNull(empty.account!!.card!!.revolvingBalance)

        val over = AccountForm.validate(draft.copy(revolvingBalance = "40000"), emptyList())
        assertEquals("既有卡循不能超過目前欠款", over.errors[AccountForm.Field.REVOLVING_BALANCE])

        val ok = AccountForm.validate(draft.copy(revolvingBalance = "12,000"), emptyList())
        val account: Account = ok.account!!
        assertEquals(12_000L, account.card!!.revolvingBalance)
        assertEquals("12000", AccountForm.fromAccount(account).revolvingBalance)
    }
}

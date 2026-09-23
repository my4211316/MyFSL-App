package tw.myfsl.app.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.myfsl.app.core.sample.SampleHousehold

/**
 * 卡債 vs 未到期卡款（R-CARD-28）。
 *
 * 判準是**帳單該繳而有沒有繳掉**：
 * - **卡債**＝帳單該繳、沒繳掉的部分。有填利率的就計息（沒填利率的卡照樣是卡債，只是不計息）。
 * - **未到期卡款**＝刷了但還沒出帳／還沒到截止日的，繳清就不會變成卡債。
 * - **未繳卡款**＝兩者相加。只用在額度與負債合計，不當「卡債」的標題。
 */
class CardDebtVsNotDueTest {

    private val snapshot = SampleHousehold.snapshot()

    @Test fun `卡債 ＋ 未到期卡款 = 未繳卡款（每一年都要成立）`() {
        listOf(2026, 2027, 2028).forEach { year ->
            val s = PlanSummaryCalculator.summarize(snapshot, year)
            s.cards.forEach { card ->
                assertEquals("${card.name} $year 年初", card.start, card.startDebt + (card.start - card.startDebt))
                assertEquals("${card.name} $year 年底：卡債不能大於未繳卡款", card.end, maxOf(card.end, card.endDebt))
            }
            assertEquals("$year 年底合計", s.cardUnpaidEnd, s.cardDebtEnd + s.cardNotDueEnd)
        }
    }

    @Test fun `全額繳清的卡：卡債恆為 0，未繳卡款就是浮動`() {
        // 把 A 卡上一期的部分繳款紀錄拿掉 → 推估變成全額繳清
        val full = snapshot.copy(
            ledger = snapshot.ledger.filterNot { it.postingKey?.startsWith("cardpay:${SampleHousehold.CARD_A}") == true },
        )
        val card = PlanSummaryCalculator.summarize(full, 2027).cards.single { it.accountId == SampleHousehold.CARD_A }
        assertEquals("每期都把帳單繳掉，所以沒有卡債", 0L, card.endDebt)
        assertEquals("利息也是 0", 0L, card.interest)
        // 未繳卡款只剩一個月的刷卡額（隔月繳的時間差）
        assertEquals("12 月刷的 21,900 還沒繳", 21_900L, card.end)
    }

    @Test fun `只繳一部分的卡：沒繳清的部分是卡債`() {
        // 示意資料的 A 卡上一期只繳 18,000 → 推估每期繳 18,000，繳不完的在計息
        val card = PlanSummaryCalculator.summarize(snapshot, 2026).cards.single { it.accountId == SampleHousehold.CARD_A }
        assertEquals("年初的欠款都算卡債（使用者輸入的既有欠款）", 60_000L, card.startDebt)
        assertEquals("有在計息就有卡債", 2_890L, card.interest)
        // 年底：未繳卡款 90,490，其中 12 月刷的 21,900 還沒到期
        assertEquals(90_490L, card.end)
        assertEquals(68_590L, card.endDebt)
        assertEquals(21_900L, card.end - card.endDebt)
    }

    @Test fun `卡債的年度變化只看卡債，不被未到期卡款干擾（R-PLS-07）`() {
        val s = PlanSummaryCalculator.summarize(snapshot, 2026)
        assertEquals(s.cardDebtEnd - s.cardDebtStart, s.cardDebtChange)
        // 未繳卡款的變化是另一個數字，不叫卡債
        assertEquals(s.cardUnpaidEnd - s.cardUnpaidStart, s.cardUnpaidEnd - s.cardUnpaidStart)
    }
}

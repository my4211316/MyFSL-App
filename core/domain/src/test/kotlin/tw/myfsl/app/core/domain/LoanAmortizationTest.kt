package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.RepaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoanAmortizationTest {

    @Test fun `本息平均攤還的月付金符合公式`() {
        assertEquals(8_885L, LoanAmortization.levelPayment(100_000, 12.0, 12))
        assertEquals(14_507L, LoanAmortization.levelPayment(600_000, 7.5, 48))
        assertEquals(3_913L, LoanAmortization.levelPayment(200_000, 6.5, 60))
        assertEquals(1_000L, LoanAmortization.levelPayment(12_000, 0.0, 12))
    }

    @Test fun `攤還表：期數正確、本金總和等於貸款、最後一期還清`() {
        RepaymentMethod.entries.forEach { method ->
            val schedule = LoanAmortization.schedule(600_000, 7.5, 48, method)
            assertEquals(48, schedule.size)
            assertEquals(600_000L, schedule.sumOf { it.principal })
            assertEquals(0L, schedule.last().balanceAfter)
            schedule.forEach { assertEquals(it.principal + it.interest, it.payment) }
        }
    }

    @Test fun `本息平均攤還：第一期利息與本金`() {
        val first = LoanAmortization.schedule(600_000, 7.5, 48, RepaymentMethod.EQUAL_PAYMENT).first()
        assertEquals(3_750L, first.interest)
        assertEquals(10_757L, first.principal)
        assertEquals(14_507L, first.payment)
    }

    @Test fun `本金平均攤還：每期本金固定、利息遞減`() {
        val schedule = LoanAmortization.schedule(120_000, 6.0, 12, RepaymentMethod.EQUAL_PRINCIPAL)
        assertEquals(10_000L, schedule[0].principal)
        assertEquals(600L, schedule[0].interest)
        assertTrue(schedule[1].interest < schedule[0].interest)
    }

    @Test fun `只繳利息：最後一期才還本金`() {
        val schedule = LoanAmortization.schedule(100_000, 6.0, 6, RepaymentMethod.INTEREST_ONLY)
        schedule.dropLast(1).forEach {
            assertEquals(0L, it.principal)
            assertEquals(500L, it.interest)
        }
        assertEquals(100_000L, schedule.last().principal)
    }

    @Test fun `沒有欠款或期數時沒有攤還表`() {
        assertTrue(LoanAmortization.schedule(0, 5.0, 12, RepaymentMethod.EQUAL_PAYMENT).isEmpty())
        assertTrue(LoanAmortization.schedule(10_000, 5.0, 0, RepaymentMethod.EQUAL_PAYMENT).isEmpty())
        assertEquals(0L, LoanAmortization.levelPayment(0, 5.0, 12))
        assertEquals(0L, LoanAmortization.firstPayment(0, 5.0, 12, RepaymentMethod.EQUAL_PAYMENT))
    }
}

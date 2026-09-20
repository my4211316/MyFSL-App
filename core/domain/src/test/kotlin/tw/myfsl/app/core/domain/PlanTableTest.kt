package tw.myfsl.app.core.domain

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import tw.myfsl.app.core.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTableTest {

    private val snapshot = SampleHousehold.snapshot()
    private val table = PlanTableBuilder.build(snapshot, 2026)
    private val summary = PlanSummaryCalculator.summarize(snapshot, 2026)

    @Test fun `明細依群組排列，一個項目一列，全為 0 的列不顯示`() {
        val living = table.rows.filter { it.itemId == LIVING }
        assertEquals("一個項目只有一列（R-MIX-01）", 1, living.size)
        assertEquals(16_000L * 12, living[0].total)
        assertTrue(living.all { it.flexible })
        val groupIndex = table.rows.indexOfFirst { it.kind == TableRowKind.GROUP && it.label == "生活" }
        assertTrue(table.rows.indexOf(living[0]) > groupIndex)
        assertTrue(table.rows.filter { it.kind == TableRowKind.ITEM }.all { it.total != 0L })
    }

    @Test fun `合計列與計畫摘要一致，月現金流在最後`() {
        fun total(label: String) = table.rows.first { it.label == label }.monthly
        assertEquals(summary.income, total("收入合計"))
        assertEquals(summary.cardSpending, total("刷卡消費"))
        assertEquals(summary.nonCardSpending, total("非刷卡支出"))
        assertEquals(summary.debtPayments, total("繳卡費＋貸款"))
        assertEquals(TableRowKind.CASH_FLOW, table.rows.last().kind)
        assertEquals(summary.monthlyCashFlow, table.rows.last().monthly)
    }

    @Test fun `自動估算列＝繳卡費＋貸款扣掉自己填的轉帳`() {
        val auto = table.rows.firstOrNull { it.auto && it.kind == TableRowKind.ITEM }
        val planned = table.rows.filter { it.kind == TableRowKind.ITEM && !it.auto && it.label.startsWith("繳") }
        val plannedMonthly = List(12) { m -> planned.sumOf { it.monthly[m] } }
        val expected = List(12) { m -> (summary.debtPayments[m] - plannedMonthly[m]).coerceAtLeast(0) }
        if (expected.all { it == 0L }) assertNull(auto) else assertEquals(expected, auto!!.monthly)
    }

    @Test fun `當月欄：今年才標`() {
        assertEquals(9, table.currentMonth)
        assertNull(PlanTableBuilder.build(snapshot, 2027).currentMonth)
    }
}

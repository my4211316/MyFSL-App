package tw.myfsl.app.core.domain

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.myfsl.app.core.sample.SampleHousehold.LIVING

class PlanTableTest {

    private val snapshot = SampleHousehold.snapshot()
    private val table = PlanTableBuilder.build(snapshot, 2026)
    private val summary = PlanSummaryCalculator.summarize(snapshot, 2026)

    @Test fun `明細依群組排列，一個項目一列，全為 0 的列不顯示`() {
        // 一列 = 項目 × 支付方式（R-MIX-01）：生活費編了現金 9,000 與信用卡 16,000 兩列
        val living = table.rows.filter { it.itemId == LIVING }
        assertEquals(2, living.size)
        assertEquals(listOf("生活費・現金", "生活費・信用卡"), living.map { it.label })
        assertEquals(9_000L * 12, living[0].total)
        assertEquals(16_000L * 12, living[1].total)
        assertTrue(living.all { it.flexible })
        val groupIndex = table.rows.indexOfFirst { it.kind == TableRowKind.GROUP && it.label == "生活" }
        assertTrue(table.rows.indexOf(living[0]) > groupIndex)
        assertTrue(table.rows.filter { it.kind == TableRowKind.ITEM }.all { it.total != 0L })
    }

    @Test fun `合計列與計畫摘要一致，月現金流在最後`() {
        fun total(label: String) = table.rows.first { it.label == label }.monthly
        assertEquals(summary.income, total("收入合計"))
        // 列名自己說清楚哪一列是現金（R-PLS-10）
        assertEquals(summary.cardSpending, total("刷卡（隔月繳）"))
        assertEquals(summary.nonCardSpending, total("非刷卡（當月）"))
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

    // ---------- 「什麼時候付」（R-PLS-10） ----------

    private val cash = PlanTableBuilder.build(snapshot, 2026, PlanTableView.CASH)

    @Test fun `什麼時候付：刷卡列不出現，繳負債的轉帳改由逐卡逐貸款的列顯示`() {
        val labels = cash.rows.filter { it.kind == TableRowKind.ITEM }.map { it.label }
        // 刷卡不動現金，所以生活費只剩現金列；油資、手機網路、汽車保養（全刷卡）整個不見
        assertTrue(labels.toString(), labels.contains("生活費・現金"))
        assertTrue(labels.toString(), labels.none { it.contains("信用卡") && !it.startsWith("繳 ") })
        assertTrue(labels.none { it == "交通油資" || it == "手機網路" || it == "汽車保養" })
        // 自己編的「繳信用卡 B」不另外列一次，統一顯示成「繳 信用卡 B」
        assertTrue(labels.none { it == "繳信用卡 B" })
        assertEquals(listOf("繳 信用卡 A", "繳 信用卡 B", "繳 信貸"), labels.filter { it.startsWith("繳 ") })
    }

    @Test fun `什麼時候付：今天以前的月份標「已過」，不是 0`() {
        val cardA = cash.rows.first { it.label == "繳 信用卡 A" }
        // 今天 9_14：1–8 月已經過去，不再推估（R-PLS-05）
        assertEquals((1..8).toSet(), cardA.pastMonths)
        assertEquals(listOf(18_000L, 18_000L, 18_000L, 18_000L), cardA.monthly.subList(8, 12))
        // 明年整年都是推估，沒有「已過」
        val next = PlanTableBuilder.build(snapshot, 2027, PlanTableView.CASH)
        assertTrue(next.rows.first { it.label == "繳 信用卡 A" }.pastMonths.isEmpty())
    }

    @Test fun `什麼時候付：逐卡逐貸款列加起來＝繳卡費＋貸款那一列（R-PLS-10）`() {
        // 明細和合計必須是同一組數字，否則使用者一欄加起來會對不上（外部審閱 V37-03 就是這一類）
        val rows = cash.rows.filter { it.kind == TableRowKind.ITEM && it.label.startsWith("繳 ") }
        val perMonth = List(12) { m -> rows.sumOf { it.monthly[m] } }
        assertEquals(cash.rows.first { it.label == "繳卡費＋貸款" }.monthly, perMonth)
        assertEquals(PlanSummaryCalculator.summarize(snapshot, 2026).debtPayments, perMonth)
    }

    @Test fun `什麼時候付：合計和「什麼時候花」用同一組結果（R-PLS-06）`() {
        fun total(t: PlanTable, label: String) = t.rows.first { it.label == label }.monthly
        assertEquals(summary.income, total(cash, "收入合計"))
        assertEquals(summary.nonCardSpending, total(cash, "非刷卡支出"))
        assertEquals(summary.debtPayments, total(cash, "繳卡費＋貸款"))
        assertEquals(summary.monthlyCashFlow, cash.rows.last().monthly)
        // 刷卡與循環利息不是當月的現金，所以不列
        assertTrue(cash.rows.none { it.label.startsWith("刷卡") || it.label.startsWith("循環利息") })
    }
}

package tw.myfsl.app.core.domain

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Timing
import tw.myfsl.app.core.model.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanImportTest {

    private val snapshot = SampleHousehold.snapshot()
    private val accounts = snapshot.accounts
    private val groups = snapshot.groups

    private fun parse(csv: String) = PlanImport.parse(csv, 2027, accounts, groups)

    private fun csv(vararg rows: String) = (listOf(PlanImport.COLUMNS.joinToString(",")) + rows).joinToString("\n")

    private fun months(vararg values: Long) = values.toList()

    // ---------- 金額解析 ----------

    @Test fun `金額：允許錢號、逗號、括號負數、破折號當 0`() {
        assertEquals(1_234L, PlanImport.amount("$1,234"))
        assertEquals(1_234L, PlanImport.amount(" 1,234 "))
        assertEquals(-500L, PlanImport.amount("(500)"))
        assertEquals(-500L, PlanImport.amount("−500"))
        assertEquals(0L, PlanImport.amount(""))
        assertEquals(0L, PlanImport.amount("—"))
        assertEquals(1_235L, PlanImport.amount("1234.6"))
        assertNull(PlanImport.amount("abc"))
        assertNull(PlanImport.amount("12x3"))
    }

    // ---------- 基本匯入 ----------

    @Test fun `範本可以直接匯入`() {
        val preview = parse(PlanImport.template())
        assertTrue(preview.errors.isEmpty())
        assertTrue(preview.canImport)
        assertEquals(2, preview.itemCount)
        assertEquals(780_000L, preview.income)
        assertEquals(108_000L, preview.expense)
    }

    @Test fun `同一個項目的多列合併成一列，金額相加（R-MIX-01）`() {
        val preview = parse(
            csv(
                "生活,生活費,支出,現金,上下各半,,是,依記帳,,,,108000,9000,9000,9000,9000,9000,9000,9000,9000,9000,9000,9000,9000",
                "生活,生活費,支出,信用卡,上下各半,,是,依記帳,,,,192000,16000,16000,16000,16000,16000,16000,16000,16000,16000,16000,16000,16000",
            ),
        )
        assertTrue(preview.errors.isEmpty())
        assertEquals(1, preview.itemCount)
        val item = preview.items.single()
        // 預算不分支付方式：兩列直接相加成一列（R-MIX-01）
        assertEquals(List(12) { 25_000L }, item.amounts)
        assertEquals(300_000L, item.total)
        assertEquals(listOf(2, 3), item.lines)
        assertTrue(preview.issues.any { it.message.contains("相加成一列") })
        assertEquals(Timing.SPLIT, item.item.timing)
        assertEquals(Flexibility.FLEXIBLE, item.item.flexibility)
        assertEquals(TrackingMode.LEDGER, item.item.tracking)
    }

    @Test fun `收入與轉帳：帶入帳戶；支付方式欄一律忽略（R-MIX-01）`() {
        val preview = parse(
            csv(
                "收入,薪資,收入,,上半月,,否,自動計入,薪轉帳戶,,,780000,65000,65000,65000,65000,65000,65000,65000,65000,65000,65000,65000,65000",
                "繳款,繳信用卡 A,轉帳,現金,上半月,,否,自動計入,薪轉帳戶,信用卡 A,,216000,18000,18000,18000,18000,18000,18000,18000,18000,18000,18000,18000,18000",
            ),
        )
        assertTrue(preview.errors.isEmpty())
        val salary = preview.items.first { it.item.name == "薪資" }
        assertEquals(FlowType.INCOME, salary.item.type)
        assertEquals(SampleHousehold.BANK, salary.item.accountId)
        assertEquals(FlowType.INCOME, salary.item.type)
        val pay = preview.items.first { it.item.name == "繳信用卡 A" }
        assertEquals(SampleHousehold.BANK, pay.item.accountId)
        assertEquals(SampleHousehold.CARD_A, pay.item.toAccountId)
        assertTrue(preview.issues.any { it.message.contains("支付方式欄會被忽略") })
    }

    @Test fun `表頭可以用別的說法，月份可以用中文`() {
        val preview = PlanImport.parse(
            """
            大類,名稱,收支,付款,時間,彈性,控管,入帳帳戶,轉入,說明,全年,一月,二月,三月,四月,五月,六月,七月,八月,九月,十月,十一月,十二月
            生活,家用,支出,現金,上下各半,是,記帳,,,錢包,12000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000
            """.trimIndent(),
            2027,
            accounts,
            groups,
        )
        assertTrue(preview.errors.isEmpty())
        assertEquals(12_000L, preview.expense)
        assertEquals("錢包", preview.items.single().item.note)
    }

    @Test fun `Excel 貼上的 Tab 分隔也能讀`() {
        val text = PlanImport.COLUMNS.joinToString("\t") + "\n" +
            "生活\t家用\t支出\t現金\t上下各半\t\t是\t依記帳\t\t\t\t12000\t1000\t1000\t1000\t1000\t1000\t1000\t1000\t1000\t1000\t1000\t1000\t1000"
        val preview = PlanImport.parse(text, 2027, accounts, groups)
        assertTrue(preview.errors.isEmpty())
        assertEquals(12_000L, preview.expense)
    }

    @Test fun `引號內的逗號不會被當成分隔`() {
        val rows = PlanImport.splitRows("a,b\n\"含,逗號\",2")
        assertEquals(listOf("含,逗號", "2"), rows[1].second)
    }

    @Test fun `群組：沒填算未分類，沒有的群組會列出來`() {
        val preview = parse(
            csv(
                ",隨手記,支出,現金,上下各半,,是,依記帳,,,,1200,100,100,100,100,100,100,100,100,100,100,100,100",
                "訂閱,AI,支出,信用卡,上半月,,否,自動計入,,,,43200,3600,3600,3600,3600,3600,3600,3600,3600,3600,3600,3600,3600",
            ),
        )
        assertEquals(listOf("未分類", "訂閱"), preview.newGroups)
        assertTrue(preview.issues.any { it.message == "會新增群組：未分類、訂閱" })
        // 生活是示意資料已有的群組，不會列為新增
        val known = parse("生活,家用,支出,現金,上下各半,,是,依記帳,,,,0,0,0,0,0,0,0,0,0,0,0,0,0".let { csv(it) })
        assertTrue(known.newGroups.isEmpty())
    }

    // ---------- 檢查 ----------

    @Test fun `錯誤：類型看不懂、找不到帳戶、轉出等於轉入`() {
        val preview = parse(
            csv(
                "生活,亂寫,飲料,現金,上下各半,,否,自動計入,,,,0,0,0,0,0,0,0,0,0,0,0,0,0",
                "生活,沒付款,支出,,上下各半,,否,自動計入,,,,1200,100,100,100,100,100,100,100,100,100,100,100,100",
                "收入,兼職,收入,,上半月,,否,自動計入,不存在的帳戶,,,12000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000",
                "繳款,自己轉自己,轉帳,,上半月,,否,自動計入,薪轉帳戶,薪轉帳戶,,12000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000",
                "生活,家用,支出,現金,上下各半,,是,依記帳,,,,12000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000",
                "生活,家用,支出,現金,上下各半,,是,依記帳,,,,12000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000",
            ),
        )
        val messages = preview.errors.map { it.message }
        assertTrue(messages.any { it.startsWith("第 2 列的類型「飲料」") })
        assertFalse("支出沒填支付方式不再是錯誤（R-MIX-01）", messages.any { it.contains("要填支付方式") })
        assertTrue(messages.any { it == "第 4 列找不到帳戶「不存在的帳戶」" })
        assertTrue(messages.any { it == "第 5 列「自己轉自己」轉出與轉入是同一個帳戶" })
        assertFalse("同一個項目的兩列直接相加，不再是錯誤", messages.any { it.contains("出現兩次") })
        assertFalse(preview.canImport)
    }

    @Test fun `錯誤：金額看不懂、缺表頭、空檔案`() {
        val bad = parse("生活,家用,支出,現金,上下各半,,是,依記帳,,,,12000,1000,一千,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000".let { csv(it) })
        assertTrue(bad.errors.any { it.message == "第 2 列 二月的金額「一千」看不懂" })

        val noHeader = PlanImport.parse("群組,金額\n生活,100", 2027, accounts, groups)
        assertTrue(noHeader.errors.any { it.message.startsWith("表頭少了必要欄位") })

        assertTrue(PlanImport.parse("   ", 2027, accounts, groups).errors.any { it.message == "檔案是空的" })
        assertTrue(PlanImport.parse(PlanImport.COLUMNS.joinToString(","), 2027, accounts, groups).errors.any { it.message == "檔案裡沒有可以匯入的項目" })
    }

    @Test fun `警示：現行和 12 個月合計不一樣、整列 0`() {
        val preview = parse(
            csv(
                "年度,年終,收入,,上半月,,否,到期確認,薪轉帳戶,,,180000,0,180000,6000,0,0,0,0,0,6000,0,0,0",
                "生活,沒用到,支出,現金,上下各半,,否,自動計入,,,,0,0,0,0,0,0,0,0,0,0,0,0,0",
            ),
        )
        assertTrue(preview.errors.isEmpty())
        assertTrue(
            preview.issues.any {
                it.severity == Severity.WARNING &&
                    it.message == "第 2 列「年終」現行 \$180,000 和 12 個月合計 \$192,000 不一樣（差 +\$12,000）"
            },
        )
        assertTrue(preview.issues.any { it.message == "第 3 列「沒用到」12 個月都是 0" })
    }

    @Test fun `錯誤：帳戶名稱對到好幾個、同項目多列欄位不一致、負數`() {
        val preview = parse(
            csv(
                "繳款,繳卡費,轉帳,,上半月,,否,自動計入,薪轉帳戶,信用卡,,12000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000",
                "生活,餐費,支出,現金,上半月,,是,依記帳,,,,1200,100,100,100,100,100,100,100,100,100,100,100,100",
                "生活,餐費,支出,信用卡,下半月,,是,依記帳,,,,1200,100,100,100,100,100,100,100,100,100,100,100,100",
                "生活,退貨,支出,現金,上半月,,否,依記帳,,,,-100,-100,0,0,0,0,0,0,0,0,0,0,0",
            ),
        )
        val messages = preview.errors.map { it.message }
        assertTrue(messages.toString(), messages.contains("第 2 列的轉入帳戶「信用卡」對到好幾個帳戶（信用卡 A、信用卡 B），請填完整名稱"))
        assertTrue(messages.toString(), messages.contains("第 4 列「餐費」的時點和前面那列（第 3 列）不一樣"))
        assertTrue(messages.toString(), messages.contains("第 5 列 一月的金額不能是負數；退款或收入請另列一個項目"))
        assertFalse(preview.canImport)

        // 帳戶開頭相同但只有一個時可以用簡稱
        val short = parse(csv("收入,薪資,收入,,上半月,,否,自動計入,薪轉,,,12000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000"))
        assertTrue(short.errors.isEmpty())
        assertEquals(SampleHousehold.BANK, short.items.single().item.accountId)
    }

    @Test fun `日期欄：可寫 15 或 15號，超出範圍報錯`() {
        val ok = parse(csv("收入,薪資,收入,,上半月,15號,否,自動計入,薪轉帳戶,,,12000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000"))
        assertTrue(ok.errors.isEmpty())
        assertEquals(15, ok.items.single().item.dueDay)
        val bad = parse(csv("收入,薪資,收入,,上半月,32,否,自動計入,薪轉帳戶,,,12000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000,1000"))
        assertTrue(bad.errors.any { it.message == "第 2 列的日期「32」要是 1 到 31" })
    }

    // ---------- 匯出與來回 ----------

    @Test fun `匯出目前計畫再匯回來，金額一樣`() {
        val csv = PlanImport.export(
            2026,
            snapshot.groups,
            snapshot.items,
            snapshot.planForYear(2026),
            snapshot.accounts,
        )
        val preview = PlanImport.parse(csv, 2026, snapshot.accounts, snapshot.groups)
        assertTrue(preview.errors.isEmpty())
        assertEquals(snapshot.activeItems.size, preview.itemCount)
        assertTrue(preview.newGroups.isEmpty())

        val plan = PlanSummaryCalculator.summarize(snapshot, 2026)
        assertEquals(plan.totalIncome, preview.income)
        assertEquals("匯出的是計畫裡的支出，不含貸款與循環利息", plan.totalPlannedExpense, preview.expense)
        // 轉帳項目逐一原樣匯回（依合約自動繳的卡費不是計畫項目，不在檔案裡）
        val transfers = snapshot.activeItems.filter { it.type == FlowType.TRANSFER }
        assertEquals(
            transfers.sumOf { item -> snapshot.planForYear(2026).filterKeys { it.itemId == item.id }.values.sumOf { it.sum() } },
            preview.items.filter { it.item.type == FlowType.TRANSFER }.sumOf { it.total },
        )
        assertEquals(
            "日期原樣匯回",
            snapshot.activeItems.associate { it.name to it.dueDay },
            preview.items.associate { it.item.name to it.item.dueDay },
        )

        // 逐列比對金額
        val living = preview.items.first { it.item.name == "生活費" }
        assertEquals(List(12) { 16_000L }, living.amounts)
        assertEquals(TrackingMode.LEDGER, living.item.tracking)
        val service = preview.items.first { it.item.name == "汽車保養" }
        assertEquals(months(0, 0, 12_000, 0, 0, 0, 0, 0, 12_000, 0, 0, 0), service.amounts)
        assertEquals(TrackingMode.CONFIRM, service.item.tracking)
        assertEquals(Timing.SECOND_HALF, service.item.timing)
    }

    // ---------- 編輯快捷 ----------

    @Test fun `編輯快捷：同額、指定月份、調整百分比、年金額平均`() {
        assertEquals(List(12) { 9_000L }, PlanEditRules.everyMonth(9_000))
        assertEquals(List(12) { 0L }, PlanEditRules.everyMonth(-5))
        assertEquals(months(0, 0, 5_000, 0, 0, 0, 0, 0, 5_000, 0, 0, 0), PlanEditRules.onlyMonths(5_000, setOf(3, 9)))
        assertEquals(months(8_000, 8_000, 8_000, 8_000, 8_000, 8_000, 8_000, 8_000, 8_000, 8_000, 8_000, 8_000), PlanEditRules.adjust(List(12) { 10_000L }, -20.0))
        assertEquals(months(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), PlanEditRules.adjust(List(12) { 10_000L }, -100.0))
        val spread = PlanEditRules.spreadYear(100_000)
        assertEquals(100_000L, spread.sum())
        assertEquals(8_333L, spread[0])
        assertEquals(8_337L, spread[11])
        assertEquals(List(12) { 0L }, PlanEditRules.clear())
    }

    @Test fun `編輯檢查：名稱空白、負數、全部 0`() {
        assertTrue(PlanEditRules.validate("", List(12) { 100L }).any { it.message == "項目名稱不能空白" })
        assertTrue(PlanEditRules.validate("生活費", List(12) { -1L }).any { it.message == "金額不能是負數" })
        assertTrue(PlanEditRules.validate("生活費", List(11) { 100L }).any { it.message == "要有 12 個月的金額" })
        assertTrue(PlanEditRules.validate("生活費", List(12) { 0L }).any { it.severity == Severity.INFO })
        assertTrue(PlanEditRules.validate("生活費", List(12) { 100L }).none { it.severity == Severity.ERROR })
    }
}

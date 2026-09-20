package tw.myfsl.app.core.sample

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.AppSettings
import tw.myfsl.app.core.model.CardTerms
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.LoanTerms
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MonthlyAmounts
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.RepaymentMethod
import tw.myfsl.app.core.model.Timing
import tw.myfsl.app.core.model.TrackingMode
import java.time.LocalDate

/**
 * 示意家庭資料：設計稿所有數字都由這份計畫算出（DesignNumbersDump 寫入 design/mockups/sample-numbers.json）。
 * 金額為虛構，不代表任何真實帳務。
 */
object SampleHousehold {

    val today: LocalDate = LocalDate.of(2026, 9, 14)

    const val BANK = 1L
    const val CASH = 2L
    const val CARD_A = 3L
    const val CARD_B = 4L
    const val LOAN = 5L
    const val POLICY_LOAN = 6L

    const val SALARY = 101L
    const val BONUS = 102L
    const val SUBSIDY = 103L
    const val INCOME_TAX = 201L
    const val VEHICLE_TAX = 202L
    const val INSURANCE = 203L
    const val ELECTRICITY = 301L
    const val GAS = 302L
    const val PARKING = 401L
    const val POLICY_INTEREST = 402L
    const val LIVING = 501L
    const val FOOD_CASH = 505L
    const val HOUSEHOLD = 502L
    const val FUEL = 503L
    const val PHONE = 504L
    const val LESSONS = 601L
    const val CONTEST = 602L
    const val RED_ENVELOPE = 701L
    const val BIRTHDAY = 702L
    const val CAR_SERVICE = 703L
    const val TRIP = 704L
    const val PAY_CARD_B = 802L

    val accounts = listOf(
        Account(BANK, "薪轉帳戶", AccountKind.BANK, balance = 120_000, balanceAsOf = today, sortOrder = 1),
        Account(CASH, "零用現金", AccountKind.CASH, balance = 15_000, balanceAsOf = today, sortOrder = 2),
        Account(
            CARD_A, "信用卡 A", AccountKind.CREDIT_CARD, balance = 60_000, balanceAsOf = today,
            creditLimit = 150_000, paymentDueDay = 15, statementDay = 1,
            // A 卡依帳單繳款，沒繳清的部分計息；上一期（8/15）只繳了 18,000，試算照這個繳法推估（R-CARD-26）。計畫裡不另外列繳卡費。
            card = CardTerms(revolvingRatePercent = 15.0, payAccountId = BANK),
            sortOrder = 3,
        ),
        Account(
            CARD_B, "信用卡 B", AccountKind.CREDIT_CARD, balance = 45_000, balanceAsOf = today,
            creditLimit = 80_000, paymentDueDay = 25,
            // B 卡沒設循環條件：不計息，繳款照計畫裡的「繳信用卡 B」。
            sortOrder = 4,
        ),
        Account(
            LOAN, "信貸", AccountKind.LOAN, balance = 600_000, balanceAsOf = today,
            loan = LoanTerms(7.5, 48, RepaymentMethod.EQUAL_PAYMENT, BANK, 20, originalPrincipal = 800_000), sortOrder = 5,
        ),
        Account(POLICY_LOAN, "保單借款", AccountKind.POLICY_LOAN, balance = 800_000, balanceAsOf = today, sortOrder = 6),
    )

    val groups = listOf(
        PlanGroup(1, "收入", 1),
        PlanGroup(2, "稅費與保險", 2),
        PlanGroup(3, "生活繳費", 3),
        PlanGroup(4, "固定支出", 4),
        PlanGroup(5, "生活", 5),
        PlanGroup(6, "小孩活動", 6),
        PlanGroup(7, "年度", 7),
        PlanGroup(8, "繳卡費與貸款", 8),
    )

    private fun income(id: Long, name: String, timing: Timing, tracking: TrackingMode) =
        PlanItem(id, name, 1, FlowType.INCOME, accountId = BANK, timing = timing, tracking = tracking)

    private fun expense(
        id: Long,
        name: String,
        group: Long,
        timing: Timing,
        method: PaymentMethod,
        flexible: Boolean = false,
        tracking: TrackingMode = TrackingMode.AUTO,
    ) = PlanItem(
        id, name, group, FlowType.EXPENSE,
        method = method,
        timing = timing,
        flexibility = if (flexible) Flexibility.FLEXIBLE else Flexibility.FIXED,
        tracking = tracking,
    )

    val items = listOf(
        income(SALARY, "薪資", Timing.FIRST_HALF, TrackingMode.AUTO).copy(dueDay = 15),
        income(BONUS, "年終獎金", Timing.FIRST_HALF, TrackingMode.CONFIRM),
        income(SUBSIDY, "教育補助", Timing.FIRST_HALF, TrackingMode.CONFIRM),
        expense(INCOME_TAX, "所得稅", 2, Timing.SECOND_HALF, PaymentMethod.TRANSFER),
        expense(VEHICLE_TAX, "牌照燃料稅", 2, Timing.SECOND_HALF, PaymentMethod.TRANSFER),
        expense(INSURANCE, "保險費", 2, Timing.FIRST_HALF, PaymentMethod.TRANSFER),
        expense(ELECTRICITY, "電費", 3, Timing.SECOND_HALF, PaymentMethod.TRANSFER),
        expense(GAS, "瓦斯", 3, Timing.SECOND_HALF, PaymentMethod.TRANSFER),
        expense(PARKING, "停車費", 4, Timing.FIRST_HALF, PaymentMethod.TRANSFER),
        expense(POLICY_INTEREST, "保單借款利息", 4, Timing.SECOND_HALF, PaymentMethod.TRANSFER),
        expense(LIVING, "生活費", 5, Timing.SPLIT, PaymentMethod.CREDIT_CARD, flexible = true, tracking = TrackingMode.LEDGER),
        expense(FOOD_CASH, "現金伙食", 5, Timing.SPLIT, PaymentMethod.CASH, flexible = true, tracking = TrackingMode.LEDGER),
        expense(HOUSEHOLD, "家用", 5, Timing.SPLIT, PaymentMethod.CASH, flexible = true, tracking = TrackingMode.LEDGER),
        expense(FUEL, "交通油資", 5, Timing.SPLIT, PaymentMethod.CREDIT_CARD, flexible = true, tracking = TrackingMode.LEDGER),
        expense(PHONE, "手機網路", 5, Timing.FIRST_HALF, PaymentMethod.CREDIT_CARD),
        expense(LESSONS, "才藝課", 6, Timing.FIRST_HALF, PaymentMethod.TRANSFER, flexible = true),
        expense(CONTEST, "比賽報名", 6, Timing.SECOND_HALF, PaymentMethod.CASH, flexible = true, tracking = TrackingMode.CONFIRM),
        expense(RED_ENVELOPE, "紅包", 7, Timing.FIRST_HALF, PaymentMethod.CASH, flexible = true, tracking = TrackingMode.CONFIRM),
        expense(BIRTHDAY, "生日", 7, Timing.SECOND_HALF, PaymentMethod.CASH, flexible = true, tracking = TrackingMode.CONFIRM),
        expense(CAR_SERVICE, "汽車保養", 7, Timing.SECOND_HALF, PaymentMethod.CREDIT_CARD, tracking = TrackingMode.CONFIRM),
        expense(TRIP, "家族旅遊", 7, Timing.SECOND_HALF, PaymentMethod.CREDIT_CARD, flexible = true, tracking = TrackingMode.CONFIRM),
        PlanItem(PAY_CARD_B, "繳信用卡 B", 8, FlowType.TRANSFER, accountId = BANK, toAccountId = CARD_B, timing = Timing.SECOND_HALF),
    )

    private fun months(vararg pairs: Pair<Int, Money>): List<Money> =
        List(12) { index -> pairs.firstOrNull { it.first == index + 1 }?.second ?: 0L }

    private fun everyMonth(amount: Money): List<Money> = List(12) { amount }

    /** 每一年都使用同一份計畫。 */
    val yearlyPlan: MonthlyAmounts = mapOf(
        PlanLine(SALARY) to everyMonth(65_000),
        PlanLine(BONUS) to months(2 to 150_000),
        PlanLine(SUBSIDY) to months(3 to 5_000, 9 to 5_000),
        PlanLine(INCOME_TAX) to months(5 to 45_000),
        PlanLine(VEHICLE_TAX) to months(4 to 7_120, 7 to 4_800),
        PlanLine(INSURANCE) to months(1 to 45_000, 7 to 45_000),
        PlanLine(ELECTRICITY) to months(1 to 900, 3 to 900, 5 to 1_200, 7 to 1_500, 9 to 1_500, 11 to 900),
        PlanLine(GAS) to months(2 to 700, 4 to 700, 6 to 700, 8 to 700, 10 to 700, 12 to 700),
        PlanLine(PARKING) to everyMonth(2_000),
        PlanLine(POLICY_INTEREST) to months(6 to 30_000, 12 to 30_000),
        PlanLine(LIVING) to everyMonth(16_000),
        PlanLine(FOOD_CASH) to everyMonth(9_000),
        PlanLine(HOUSEHOLD) to everyMonth(1_000),
        PlanLine(FUEL) to everyMonth(3_500),
        PlanLine(PHONE) to everyMonth(2_400),
        PlanLine(LESSONS) to everyMonth(6_000),
        PlanLine(CONTEST) to months(4 to 6_000, 10 to 6_000),
        PlanLine(RED_ENVELOPE) to months(2 to 40_000),
        PlanLine(BIRTHDAY) to months(2 to 5_000, 6 to 5_000, 11 to 5_000),
        PlanLine(CAR_SERVICE) to months(3 to 12_000, 9 to 12_000),
        PlanLine(TRIP) to months(8 to 52_180),
        PlanLine(PAY_CARD_B) to everyMonth(9_000),
    )

    private fun entry(
        id: Long,
        day: Int,
        itemId: Long,
        method: PaymentMethod,
        amount: Money,
        accountId: Long?,
        note: String,
        source: EntrySource = EntrySource.MANUAL,
    ) = LedgerEntry(
        id = id, date = LocalDate.of(2026, 9, day), type = FlowType.EXPENSE, amount = amount,
        itemId = itemId, method = method, accountId = accountId, note = note, source = source,
    )

    /**
     * 9 月的記帳。項目本月花多少一律由這些記帳加總得到：
     * 現金伙食 4,400、生活費（刷卡）9,800、交通油資 2,300、家用 300。
     */
    val septemberLedger = listOf(
        entry(1, 12, FOOD_CASH, PaymentMethod.CASH, 3_775, CASH, "9/1–9/12 現金支出（示意）"),
        entry(2, 12, LIVING, PaymentMethod.CREDIT_CARD, 9_650, CARD_A, "9/1–9/12 刷卡（示意）"),
        entry(3, 12, FUEL, PaymentMethod.CREDIT_CARD, 1_500, CARD_B, "加油（示意）"),
        entry(4, 7, FOOD_CASH, PaymentMethod.CASH, 120, CASH, "", EntrySource.MISSED),
        entry(5, 13, HOUSEHOLD, PaymentMethod.CASH, 300, CASH, "清潔用品"),
        entry(6, 13, FUEL, PaymentMethod.CREDIT_CARD, 800, null, "加油"),
        entry(7, 14, FOOD_CASH, PaymentMethod.CASH, 420, CASH, "買菜"),
        entry(8, 14, LIVING, PaymentMethod.CREDIT_CARD, 150, CARD_A, "午餐"),
        entry(9, 14, FOOD_CASH, PaymentMethod.CASH, 85, CASH, "早餐"),
    )

    /** 8/15 在本月到期記下的 A 卡繳款（8/1 那期帳單沒繳清，只繳 18,000）；試算從這筆推估之後每期的繳法。 */
    val augustCardPayment = LedgerEntry(
        id = 100, date = LocalDate.of(2026, 8, 15), type = FlowType.TRANSFER, amount = 18_000, accountId = BANK, toAccountId = CARD_A,
        note = "繳 信用卡 A", source = EntrySource.DUE, postingKey = "cardpay:$CARD_A:2026-08",
    )

    val settings = AppSettings(safetyLevel = 30_000, horizonMonths = 24, pickCard = true, cashAccountId = CASH, transferAccountId = BANK)

    fun snapshot(): FinanceSnapshot = FinanceSnapshot(
        today = today,
        accounts = accounts,
        groups = groups,
        items = items,
        amountsByYear = mapOf(2026 to yearlyPlan, 2027 to yearlyPlan, 2028 to yearlyPlan),
        actuals = emptyList(),
        ledger = septemberLedger + augustCardPayment,
        settings = settings,
    )
}

package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.CardPaymentPlan
import tw.myfsl.app.core.model.AppSettings
import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.MonthlyAmounts
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.TrackingMode

data class GroupTotal(val group: PlanGroup, val monthly: List<Money>) {
    val total: Money get() = monthly.sum()
}

/**
 * 一張卡在這一年的推估（R-PLS-06）：欠款**逐月滾動**——這個月的刷卡與利息加進去、繳款扣掉，
 * 餘額帶到下個月。所以利息會隨欠款下降，也看得出年底還欠多少、照這樣還要幾個月才還得完。
 */
data class CardProjection(
    val accountId: Long,
    val name: String,
    /** 這一年開始時的**未繳卡款**（卡債 ＋ 未到期卡款；從今天的餘額一路滾過來）。 */
    val start: Money,
    /** 這一年結束時的**未繳卡款**。只用在額度與負債合計，不當「卡債」的標題（R-CARD-28）。 */
    val end: Money,
    /** 年初的**卡債**：帳單沒繳清、在計息的部分。期初的欠款都算卡債（那是使用者輸入的既有欠款）。 */
    val startDebt: Money = start,
    /** 年底的**卡債**。這才是「卡債全年增加／減少」要講的數字（R-PLS-07、R-CARD-28）。 */
    val endDebt: Money = end,
    /**
     * 繳款金額是怎麼來的（R-CARD-26 的推估依據）。依帳單繳款的卡才有；
     * 沒有條件的卡是使用者自己在計畫裡編的，所以為 null。
     */
    val paymentBasis: String? = null,
    val spending: Money,
    val interest: Money,
    val payments: Money,
    /** 照這個繳法、含計畫的刷卡，大約幾個月還得完；永遠還不完時為 null（R-CARD-06）。 */
    val monthsToClear: Int?,
    /**
     * 這一年每個月算進「繳卡費」的金額（12 格），和 [PlanSummary.cardPayments] 這張卡的部分完全一致，
     * 所以年表的逐卡列加起來一定等於合計列（R-PLS-10）：
     * - 依帳單繳款的卡（含「照計畫編的金額」）：逐月滾動算出來的、已經套過還款上限的金額（R-PAY-02），
     *   只有 [PlanSummary.autoMonths] 有值，其他月份是 0（那些月份已經發生過，不再推估，R-PLS-05）。
     * - 沒有繳款條件的卡：照計畫原樣，12 個月都有值（R-PAY-02 的年度計畫表照計畫彙總）。
     */
    val monthlyPayments: List<Money> = List(12) { 0L },
) {
    /** 這一年的卡債變化：正數 = 變多。 */
    val change: Money get() = end - start
}

/** 一筆貸款在這一年的推估：照攤還表逐月往前推，跨年會接續（R-PLS-06）。 */
data class LoanProjection(
    val accountId: Long,
    val name: String,
    val start: Money,
    val end: Money,
    val principal: Money,
    val interest: Money,
    /** 這一年結束時還剩幾期。 */
    val remainingMonths: Int,
    /** 這一年每個月要繳多少（本金＋利息，12 格；過去的月份看實際紀錄，R-PLS-11）。 */
    val monthlyPayments: List<Money> = List(12) { 0L },
)

/**
 * 年度計畫表的三層彙總：明細 → 信用卡帳 / 非刷卡帳 → 月現金流。
 *
 * **支出＝這個月一定要付出去的錢**（R-PLS-04）：非刷卡支出 ＋ 貸款月繳（本金＋利息）＋ 繳卡費。
 * 使用者把貸款的攤還條件、卡片的帳單與最低應繳輸進來之後，每期要繳多少就是算得出來的，
 * 那就是一筆一定要付的預算項目，和水電費沒有兩樣——所以整筆放進支出，不再拆成「利息算支出、本金另外列」。
 * 利息也**不另外加一次**：它已經包在實際繳出去的錢裡了。
 * 想看「不算還本金的話結構差多少」，看 [gapWithoutPrincipal]。
 */
data class PlanSummary(
    val year: Int,
    val income: List<Money>,
    /** 計畫裡的支出（不含利息）。 */
    val plannedExpense: List<Money>,
    /** 依試算的付款假設，計畫支出中算成刷卡的部分（R-MIX-02）。 */
    val cardSpending: List<Money>,
    /** 計畫支出中當月就從帳戶扣的部分。 */
    val nonCardSpending: List<Money>,
    /** 繳卡費（轉帳到信用卡）。 */
    val cardPayments: List<Money>,
    /** 繳貸款、保單借款的本金部分。 */
    val loanPrincipal: List<Money>,
    /** 貸款利息：真正的費用。 */
    val loanInterest: List<Money>,
    /** 以目前卡債與循環利率估算的每月循環利息。 */
    val cardInterest: List<Money>,
    val groups: List<GroupTotal>,
    /** 這一年有編計畫的月份範圍（1–12）；沒有計畫時為空。自動產生的繳款只算在這個範圍內（R-PLS-05）。 */
    val plannedMonths: List<Int> = (1..12).toList(),
    /** 逐卡的推估（R-PLS-06）。 */
    val cards: List<CardProjection> = emptyList(),
    /** 逐筆貸款的推估（R-PLS-06）。 */
    val loans: List<LoanProjection> = emptyList(),
    /**
     * 自動產生的繳款（卡費、貸款）實際算了哪幾個月（R-PLS-05）：
     * 有編計畫、而且不早於今天所在的月份——過去的月份不預測。
     */
    val autoMonths: List<Int> = (1..12).toList(),
) {
    /** 全年支出 = 非刷卡支出 ＋ 繳卡費 ＋ 貸款月繳（R-PLS-04）。刷卡的錢算在繳卡費那一筆，不重複算。 */
    val expense: List<Money> get() = List(12) { nonCardSpending[it] + cardPayments[it] + loanPayments[it] }

    /** 繳貸款總額（本金＋利息）。 */
    val loanPayments: List<Money> get() = List(12) { loanPrincipal[it] + loanInterest[it] }

    val totalIncome: Money get() = income.sum()
    val totalExpense: Money get() = expense.sum()
    val totalPlannedExpense: Money get() = plannedExpense.sum()
    val totalCardSpending: Money get() = cardSpending.sum()
    val totalNonCardSpending: Money get() = nonCardSpending.sum()
    val totalCardPayments: Money get() = cardPayments.sum()
    val totalLoanPayments: Money get() = loanPayments.sum()
    val totalLoanPrincipal: Money get() = loanPrincipal.sum()
    val totalLoanInterest: Money get() = loanInterest.sum()
    val totalCardInterest: Money get() = cardInterest.sum()

    /** 這一年算了幾個月的自動繳款（畫面用來說明「只算 10–12 月」）。 */
    val monthCount: Int get() = autoMonths.size

    /** 每月繳卡費＋貸款。 */
    val debtPayments: List<Money> get() = List(12) { cardPayments[it] + loanPayments[it] }

    /** 月現金流：收入 − 非刷卡支出 − 繳卡費 − 貸款繳款。 */
    val monthlyCashFlow: List<Money> get() = List(12) { income[it] - nonCardSpending[it] - cardPayments[it] - loanPayments[it] }

    /** 年初、年底的卡債與貸款餘額（R-PLS-06）。 */
    /** 年初的卡債（計息的部分，R-CARD-28）。 */
    val cardDebtStart: Money get() = cards.sumOf { it.startDebt }

    /** 年底的卡債（計息的部分）。 */
    val cardDebtEnd: Money get() = cards.sumOf { it.endDebt }

    /** 年初的未繳卡款＝卡債 ＋ 未到期卡款。只用在額度與負債合計。 */
    val cardUnpaidStart: Money get() = cards.sumOf { it.start }

    /** 年底的未繳卡款。 */
    val cardUnpaidEnd: Money get() = cards.sumOf { it.end }

    /** 年底還沒到截止日的卡款：刷了要付，但繳清就不計息，不是卡債（R-CARD-28）。 */
    val cardNotDueEnd: Money get() = cardUnpaidEnd - cardDebtEnd
    val loanDebtStart: Money get() = loans.sumOf { it.start }
    val loanDebtEnd: Money get() = loans.sumOf { it.end }

    /** 卡債全年變化（正數 = 變多、負數 = 變少）：年底欠款 − 年初欠款。 */
    val cardDebtChange: Money get() = cardDebtEnd - cardDebtStart

    /** 畫面的標題跟著方向走（R-PLS-07）：不會用「增加」去描述一個負數。 */
    val cardDebtLabel: String get() = when {
        cardDebtChange > 0 -> "卡債全年增加"
        cardDebtChange < 0 -> "卡債全年減少"
        else -> "卡債全年不變"
    }

    /** 某月卡債預計變化：刷卡 ＋ 循環利息 − 繳卡費。 */
    fun cardDebtChange(month: Int): Money = cardSpending[month - 1] + cardInterest[month - 1] - cardPayments[month - 1]

    /** 年度結構缺口 = 收入 − 支出（R-PLS-04）：這一年帳戶實際會多或少多少錢。 */
    val structuralGap: Money get() = totalIncome - totalExpense

    /**
     * 支出裡有多少是在**還債務本金**（R-PLS-04）：貸款本金 ＋（繳卡費 − 循環利息 − 刷卡消費）。
     * 這些錢不是花掉，是把負債換成淨值，所以另外標出來。
     */
    val debtPrincipal: Money get() =
        totalLoanPrincipal + (totalCardPayments - totalCardInterest - totalCardSpending)

    /** 不算還本金的缺口：只看真正的費用（含利息）比收入多或少多少。 */
    val gapWithoutPrincipal: Money get() = structuralGap + debtPrincipal
}

enum class Severity { INFO, WARNING, ERROR }

data class PlanIssue(val severity: Severity, val message: String, val itemId: Long? = null)

object PlanSummaryCalculator {

    /**
     * 年度計畫彙總（R-PLS）。
     *
     * 繳款來源（R-PAY-01）：卡片有循環條件、貸款有攤還條件時，依合約自動估算每月繳款；
     * 計畫裡繳給這些負債的轉帳只有標成「額外還款」才另外計入，否則不計，避免重複。
     * 封存的項目只算封存月份之前（R-EDT-10）。
     */
    /**
     * 這一年有編計畫的月份（R-PLS-05）：第一個到最後一個有金額的月份。
     * 中間沒填的月份也算在內（例如只有 1 月和 12 月有金額時，範圍仍是 1–12）。
     * 完全沒有計畫時回傳空清單，自動繳款一筆都不算。
     */
    fun plannedMonthRange(amounts: MonthlyAmounts, snapshot: FinanceSnapshot, year: Int): List<Int> {
        val months = (1..12).filter { month ->
            amounts.any { (line, values) ->
                (values.getOrElse(month - 1) { 0L }) != 0L &&
                    snapshot.item(line.itemId)?.let { snapshot.isItemActiveIn(it, year, month) } == true
            }
        }
        val first = months.minOrNull() ?: return emptyList()
        return (first..months.max()).toList()
    }

    fun summarize(snapshot: FinanceSnapshot, year: Int): PlanSummary {
        val amounts = snapshot.planForYear(year)
        val accounts = snapshot.accounts
        val kinds = accounts.associate { it.id to it.kind }
        val income = LongArray(12)
        val expense = LongArray(12)
        val card = LongArray(12)
        val nonCard = LongArray(12)
        val cardPay = LongArray(12)
        val loanPay = LongArray(12)
        val byGroup = mutableMapOf<Long, LongArray>()
        // 計畫裡自己編的繳卡費，逐卡分開記：沒有依帳單繳款的卡，就靠這些轉帳還（R-PAY-01）。
        val planCardPay = mutableMapOf<Long, LongArray>()

        // 一列是「項目 × 支付方式」（R-MIX-01）：刷卡與非刷卡直接看計畫列自己寫的，不推估。
        for ((line, monthly) in amounts) {
            val item = snapshot.item(line.itemId) ?: continue
            // 選了「照計畫編的金額」的卡（R-CARD-27）：這筆轉帳就是那張卡的繳款，但**金額要套還款上限**
            // （R-PAY-02：最多繳到欠款為 0），而上限只有逐月滾動算得出來。所以這裡不加，
            // 由下面的逐卡滾動把套過上限的金額加進 cardPay——和其他依合約自動繳的卡同一條路（V37-03）。
            val skipTransfer = item.type == FlowType.TRANSFER &&
                snapshot.isAutoManagedDebt(item.toAccountId) && !item.extraRepayment
            for (m in 0 until 12) {
                val amount = monthly.getOrElse(m) { 0L }
                if (amount == 0L || !snapshot.isItemActiveIn(item, year, m + 1)) continue
                when (item.type) {
                    FlowType.INCOME -> income[m] += amount
                    FlowType.EXPENSE -> {
                        expense[m] += amount
                        if (line.method == PaymentMethod.CREDIT_CARD) card[m] += amount else nonCard[m] += amount
                        byGroup.getOrPut(item.groupId) { LongArray(12) }[m] += amount
                    }

                    FlowType.TRANSFER -> {
                        // 照計畫編的卡：金額只記進 planCardPay 當「想繳多少」，等滾動套完上限才算進 cardPay。
                        val plannedForCard = snapshot.paysCardFromPlan(item.toAccountId)
                        if (plannedForCard) {
                            planCardPay.getOrPut(item.toAccountId!!) { LongArray(12) }[m] += amount
                        } else if (!skipTransfer) {
                            when (kinds[item.toAccountId]) {
                                AccountKind.CREDIT_CARD -> {
                                    cardPay[m] += amount
                                    planCardPay.getOrPut(item.toAccountId!!) { LongArray(12) }[m] += amount
                                }
                                AccountKind.LOAN, AccountKind.POLICY_LOAN -> loanPay[m] += amount
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }

        // 自動產生的繳款只算在「有編計畫的月份範圍」內（R-PLS-05）：
        // 只編了 10–12 月，就不該拿 12 個月的貸款去比 3 個月的計畫。
        val plannedMonths = plannedMonthRange(amounts, snapshot, year)
        // 而且不早於今天所在的月份：過去的卡費與貸款已經發生過，不該再預測一次。
        val autoMonths = autoMonthsOf(snapshot, year)

        // 逐卡把欠款一個月一個月往前滾（R-PLS-06）：刷卡與利息加進去、繳款扣掉，餘額帶到下個月。
        // 有依帳單繳款的卡照推估的繳款方式自動繳（R-CARD-26）；沒有的就看計畫裡自己編的繳卡費。
        val interest = LongArray(12)
        val cards = snapshot.activeCards.map { account ->
            val terms = account.card
            val scheduled = account.hasCardSchedule && terms != null
            val assumption = if (scheduled) CardRules.assumption(snapshot, account) else null
            // 計畫的刷卡都算在預設卡片上（R-CARD-05）。
            val ownSpending: (Int, Int) -> Money =
                if (account.id == snapshot.defaultCardId) { y, m -> cardSpendingOf(snapshot, y, m) } else { _, _ -> 0L }
            val ownPayment: (Int) -> Money = { month -> planCardPay[account.id]?.getOrElse(month - 1) { 0L } ?: 0L }
            // 繳款由計畫編的卡：照期別計息，但繳款金額看計畫，而且不再往 cardPay 加一次。
            val fromPlan = scheduled && snapshot.paysCardFromPlan(account.id)
            var balance = CardRules.baseBalance(snapshot, account).coerceAtLeast(0)
            var rolled: Pair<Money, Money>? = null
            if (scheduled) {
                // 照計畫編的卡，中間每一個月都要用那個月編的金額，不能一路用「目前這一期」的（V37-02）。
                val plannedOf: ((Int, Int) -> Money)? =
                    if (fromPlan) { y, m -> CardRules.plannedPayment(snapshot, account.id, y, m) } else null
                rolled = rollTo(balance, terms!!, assumption!!, snapshot, year, ownSpending, plannedOf)
                balance = rolled.first + rolled.second
            }
            val start = balance
            // 兩條線（R-CARD-28）：卡債＝帳單沒繳清、在計息的部分；未到期卡款＝當月新刷、還沒出帳的。
            // 期初的欠款一律算卡債——那是使用者輸入的既有欠款（他自己說那是在算循環利息的部分）。
            var debt = rolled?.first ?: balance
            var notDue = rolled?.second ?: 0L
            val startDebt = debt
            var spent = 0L
            var paid = 0L
            var owed = 0L
            val perMonth = LongArray(12)
            autoMonths.forEach { month ->
                val spending = ownSpending(year, month)
                val payment: Money
                val monthInterest: Money
                if (fromPlan) {
                    val outlook = CardRules.outlook(balance, terms!!, assumption!!, spending, payment = ownPayment(month))
                    payment = outlook.payment
                    monthInterest = outlook.interest
                    interest[month - 1] += monthInterest
                    // 套過還款上限的金額才算進繳卡費（R-PAY-02、V37-03）
                    cardPay[month - 1] += payment
                } else if (scheduled) {
                    val outlook = CardRules.outlook(balance, terms!!, assumption!!, spending)
                    payment = outlook.payment
                    monthInterest = outlook.interest
                    interest[month - 1] += monthInterest
                    // 依合約自動繳的那一筆才由這裡加進繳卡費；計畫自己編的已經算過了。
                    cardPay[month - 1] += payment
                } else {
                    // 還款最多還到欠款為 0（R-PAY-02）。
                    payment = minOf(ownPayment(month), balance + spending)
                    monthInterest = 0
                }
                spent += spending
                paid += payment
                owed += monthInterest
                perMonth[month - 1] = payment
                // 這一期的帳單 = 上個月結轉的（卡債 ＋ 上個月新刷的，那些這期出帳了）。
                // 繳款先抵帳單，沒繳掉的加上利息就是卡債；這個月新刷的還沒出帳，是未到期卡款。
                debt = (debt + notDue + monthInterest - payment).coerceAtLeast(0)
                notDue = spending
                balance = debt + notDue
            }
            val monthly = autoMonths.map { ownSpending(year, it) }
            CardProjection(
                accountId = account.id,
                name = account.name,
                start = start,
                end = balance,
                spending = spent,
                interest = owed,
                payments = paid,
                monthsToClear = if (!scheduled) {
                    null
                } else {
                    CardRules.monthsToClear(start, terms!!, assumption!!, if (monthly.isEmpty()) 0L else Math.round(monthly.average()))
                },
                // 逐卡列要和合計列對得起來（R-PLS-10）：依帳單繳款的卡用滾動的結果，
                // 沒有條件的卡用計畫原樣（那也正是上面加進 cardPay 的金額）。
                monthlyPayments = if (scheduled) perMonth.toList() else (planCardPay[account.id]?.toList() ?: List(12) { 0L }),
                startDebt = startDebt,
                endDebt = debt,
                paymentBasis = assumption?.basis,
            )
        }

        // 依攤還條件自動繳款的貸款（R-PLS-06）：照攤還表逐期拆本金與利息，跨年接著算下去。
        val loanInterest = LongArray(12)
        val loans = accounts.filter { !it.archived && it.loan != null && it.balance > 0 }.mapNotNull { loan ->
            val terms = loan.loan!!
            val schedule = LoanAmortization.schedule(loan.balance, terms.annualRatePercent, terms.remainingMonths, terms.method)
            // 第一期繳款在哪個月：之後每個月一期，所以第 n 個月就是攤還表的第 n 期（R-PLS-05）。
            val firstDue = java.time.YearMonth.from(BaselineBuilder.nextDue(snapshot.trackingFrom, terms.payDay))
            fun rowIndex(month: Int): Int =
                ((year - firstDue.year) * 12 + (month - firstDue.monthValue))
            val first = autoMonths.firstOrNull() ?: return@mapNotNull null
            val start = schedule.getOrNull(rowIndex(first) - 1)?.balanceAfter
                ?: loan.balance.takeIf { rowIndex(first) <= 0 }
                ?: 0L
            var principal = 0L
            var paidInterest = 0L
            var end = start
            var remaining = schedule.size
            val perMonth = LongArray(12)
            autoMonths.forEach { month ->
                val row = schedule.getOrNull(rowIndex(month)) ?: return@forEach
                loanPay[month - 1] += row.principal
                loanInterest[month - 1] += row.interest
                principal += row.principal
                paidInterest += row.interest
                perMonth[month - 1] = row.principal + row.interest
                end = row.balanceAfter
                remaining = schedule.size - row.number
            }
            LoanProjection(loan.id, loan.name, start, end, principal, paidInterest, remaining, perMonth.toList())
        }

        return PlanSummary(
            year = year,
            income = income.toList(),
            plannedExpense = expense.toList(),
            cardSpending = card.toList(),
            nonCardSpending = nonCard.toList(),
            cardPayments = cardPay.toList(),
            loanPrincipal = loanPay.toList(),
            loanInterest = loanInterest.toList(),
            cardInterest = interest.toList(),
            groups = snapshot.groups.sortedBy { it.sortOrder }.mapNotNull { g -> byGroup[g.id]?.let { GroupTotal(g, it.toList()) } },
            plannedMonths = plannedMonths,
            cards = cards,
            loans = loans,
            autoMonths = autoMonths,
        )
    }

    /** 某年某月計畫裡刷卡的支出（R-MIX-01）：計畫列自己寫的，不推估。 */
    private fun cardSpendingOf(snapshot: FinanceSnapshot, year: Int, month: Int): Money =
        snapshot.planForYear(year).entries.sumOf { (line, values) ->
            if (line.method != PaymentMethod.CREDIT_CARD) return@sumOf 0L
            val item = snapshot.item(line.itemId) ?: return@sumOf 0L
            if (item.type != FlowType.EXPENSE || !snapshot.isItemActiveIn(item, year, month)) 0L
            else values.getOrElse(month - 1) { 0L }
        }

    /**
     * 這一年實際會算到的自動繳款月份（R-PLS-05）：有編計畫、而且不早於今天所在的月份。
     * 逐月滾動與年度彙總都用這一份，所以「2026 年底的欠款」和「2027 年初的欠款」一定是同一個數字。
     */
    fun autoMonthsOf(snapshot: FinanceSnapshot, year: Int): List<Int> {
        val thisMonth = java.time.YearMonth.from(snapshot.today)
        return plannedMonthRange(snapshot.planForYear(year), snapshot, year)
            .filter { !java.time.YearMonth.of(year, it).isBefore(thisMonth) }
    }

    /**
     * 把卡債從今天所在的月份滾到 [year] 的 1 月（R-PLS-06）：
     * 看 2027 年的時候，起點是 2026 年繳完之後剩下的欠款，不是今天的欠款。
     * 中間的每一年都只算那一年會算到的月份（[autoMonthsOf]），
     * 所以上一年畫面上的「年底欠款」就是這一年的「年初欠款」。
     * [year] 不晚於今天所在的年份時，起點就是今天的欠款。
     */
    private fun rollTo(
        balance: Money,
        terms: tw.myfsl.app.core.model.CardTerms,
        assumption: CardRules.PaymentAssumption,
        snapshot: FinanceSnapshot,
        year: Int,
        spendingOf: (Int, Int) -> Money,
        /** 照計畫編的金額時，那個月想繳多少；其他繳法為 null（照 [assumption]）。 */
        paymentOf: ((Int, Int) -> Money)? = null,
    ): Pair<Money, Money> {
        // 回傳（卡債, 未到期卡款）：兩條線一起滾，下一年的年初才分得清哪一半在計息（R-CARD-28）。
        var debt = balance
        var notDue = 0L
        var ym = java.time.YearMonth.from(snapshot.today)
        val target = java.time.YearMonth.of(year, 1)
        val months = mutableMapOf<Int, List<Int>>()
        var guard = 0
        while (ym.isBefore(target) && guard++ < MAX_ROLL_MONTHS) {
            val allowed = months.getOrPut(ym.year) { autoMonthsOf(snapshot, ym.year) }
            if (ym.monthValue in allowed) {
                val spending = spendingOf(ym.year, ym.monthValue)
                val outlook = CardRules.outlook(debt + notDue, terms, assumption, spending, paymentOf?.invoke(ym.year, ym.monthValue))
                debt = (debt + notDue + outlook.interest - outlook.payment).coerceAtLeast(0)
                notDue = spending
            }
            ym = ym.plusMonths(1)
        }
        return debt to notDue
    }

    private const val MAX_ROLL_MONTHS = 600
}

/** 自動檢查計畫表的常見錯誤，取代人工核對。 */
object PlanValidator {

    fun validate(snapshot: FinanceSnapshot, year: Int): List<PlanIssue> {
        val summary = PlanSummaryCalculator.summarize(snapshot, year)
        val items = snapshot.items
        val accounts = snapshot.accounts
        val amounts = snapshot.planForYear(year)
        val settings = snapshot.settings
        val byId = accounts.associateBy { it.id }
        val liquid = accounts.filter { !it.archived && it.kind.isLiquid }
        val issues = mutableListOf<PlanIssue>()

        for (item in items.filter { !it.archived }) {
            val lines = amounts.filter { (line, months) -> line.itemId == item.id && months.any { it != 0L } }.keys
            when (item.type) {
                FlowType.INCOME -> {
                    val account = byId[item.accountId]
                    if (account == null || account.archived) {
                        issues += PlanIssue(Severity.ERROR, "「${item.name}」沒有設定入帳帳戶", item.id)
                    }
                }

                FlowType.TRANSFER -> {
                    val from = byId[item.accountId]
                    val to = byId[item.toAccountId]
                    when {
                        from == null || from.archived -> issues += PlanIssue(Severity.ERROR, "「${item.name}」沒有設定轉出帳戶", item.id)
                        to == null || to.archived -> issues += PlanIssue(Severity.ERROR, "「${item.name}」沒有設定轉入帳戶", item.id)
                        from.id == to.id -> issues += PlanIssue(Severity.ERROR, "「${item.name}」轉出與轉入是同一個帳戶", item.id)
                    }
                    if (to != null && snapshot.isAutoManagedDebt(to.id) && lines.isNotEmpty()) {
                        issues += when {
                            // 卡片選了「照計畫編的金額」：這筆就是每期繳款（R-CARD-27），不是被忽略、也不是額外的。
                            snapshot.paysCardFromPlan(to.id) -> PlanIssue(
                                Severity.INFO,
                                "「${item.name}」就是「${to.name}」每期要繳的金額；沒繳完的部分照利率計息",
                                item.id,
                            )

                            item.extraRepayment ->
                                PlanIssue(Severity.INFO, "「${item.name}」是額外還款：除了「${to.name}」依合約的月繳之外另外計入", item.id)

                            else -> PlanIssue(
                                Severity.WARNING,
                                "「${item.name}」不計入：「${to.name}」已依合約自動繳款。若這是額外還款，請在項目勾選「額外還款」",
                                item.id,
                            )
                        }
                    }
                    // 還款最多還到欠款為 0（R-PAY-02）：不會再增加欠款的負債，全年計畫比欠款多時提醒。
                    val counted = to != null && to.kind.isLiability &&
                        (!snapshot.isAutoManagedDebt(to.id) || item.extraRepayment || snapshot.paysCardFromPlan(to.id))
                    val growsWithSpending = to?.id == snapshot.defaultCardId
                    val planned = lines.sumOf { line -> amounts[line].orEmpty().sum() }
                    if (counted && !growsWithSpending && planned > to!!.balance.coerceAtLeast(0)) {
                        issues += PlanIssue(
                            Severity.INFO,
                            "「${item.name}」全年 ${MoneyFormat.currency(planned)}，比「${to.name}」目前欠款 " +
                                "${MoneyFormat.currency(to.balance.coerceAtLeast(0))} 多；還清後試算與本月到期就不再扣款",
                            item.id,
                        )
                    }
                }

                FlowType.EXPENSE -> {
                    if (lines.isEmpty()) {
                        issues += PlanIssue(Severity.INFO, "「${item.name}」今年沒有任何計畫金額", item.id)
                    }
                    if (item.flexibility == Flexibility.FLEXIBLE && item.tracking == TrackingMode.AUTO) {
                        issues += PlanIssue(
                            Severity.INFO,
                            "「${item.name}」是可調項目，建議改成依記帳，才能控管進度",
                            item.id,
                        )
                    }
                }
            }
        }

        // 計畫列自己帶支付方式（R-MIX-01）：看實際用到哪些方式，就檢查那些方式有沒有帳戶。
        fun planned(method: PaymentMethod) = amounts.any { (line, months) ->
            line.method == method && months.any { it != 0L } &&
                snapshot.item(line.itemId)?.archived == false
        }
        if (planned(PaymentMethod.CREDIT_CARD) && snapshot.defaultCardId == null) {
            issues += PlanIssue(Severity.WARNING, "計畫有刷卡，但還沒有信用卡帳戶；試算時先用一張沒有利息的替代卡片")
        }
        if (planned(PaymentMethod.CASH) && resolve(settings.cashAccountId, liquid, AccountKindHint.CASH) == null) {
            issues += PlanIssue(Severity.ERROR, "計畫有現金支出，但沒有可以扣款的帳戶，請先新增現金或銀行帳戶")
        }
        if (planned(PaymentMethod.TRANSFER) && resolve(settings.transferAccountId, liquid, AccountKindHint.BANK) == null) {
            issues += PlanIssue(Severity.ERROR, "計畫有轉帳支出，但沒有可以扣款的帳戶，請先新增銀行帳戶")
        }
        // 繳款計畫要編得起來才算得出現金流（R-CARD-27）。
        snapshot.activeCards.filter { it.hasCardSchedule }.forEach { card ->
            when (card.card?.paymentPlan) {
                CardPaymentPlan.PLANNED -> {
                    val planned = (1..12).sumOf { m -> CardRules.plannedPayment(snapshot, card.id, year, m) }
                    if (planned == 0L) {
                        issues += PlanIssue(
                            Severity.WARNING,
                            "「${card.name}」選了「${CardPaymentPlan.PLANNED.label}」，但計畫裡沒有繳這張卡的項目：" +
                                "請新增一個轉帳項目（轉入「${card.name}」）並編上每個月要繳多少",
                        )
                    }
                }

                CardPaymentPlan.MINIMUM -> {
                    val hasBill = snapshot.cardStatements.any { it.cardId == card.id && it.minimumPayment != null }
                    if (!hasBill) {
                        issues += PlanIssue(
                            Severity.WARNING,
                            "「${card.name}」選了「${CardPaymentPlan.MINIMUM.label}」，但還沒輸入帳單的最低應繳：" +
                                "目前先當成全額繳清試算",
                        )
                    }
                }

                CardPaymentPlan.AUTO, CardPaymentPlan.FULL, null -> Unit
            }
        }
        // 逐卡看卡債走向（R-PLS-06）：和上面的彙總用同一組逐月滾動的結果，畫面不會出現兩種假設。
        summary.cards.filter { it.change > 0 }.forEach { card ->
            issues += PlanIssue(
                Severity.WARNING,
                "「${card.name}」這一年刷 ${MoneyFormat.currency(card.spending)}、利息 ${MoneyFormat.currency(card.interest)}，" +
                    "繳 ${MoneyFormat.currency(card.payments)} 不夠：年底欠款會從 ${MoneyFormat.currency(card.start)} 變成 " +
                    "${MoneyFormat.currency(card.end)}",
            )
        }
        if (summary.cardDebtChange > 0) {
            issues += PlanIssue(
                Severity.WARNING,
                "全年刷卡加利息比繳卡費多 ${MoneyFormat.currency(summary.cardDebtChange)}，差額會累積成卡債",
            )
        }
        if (summary.totalIncome == 0L && items.any { !it.archived }) {
            issues += PlanIssue(Severity.WARNING, "計畫中沒有任何收入")
        }
        return issues.sortedByDescending { it.severity.ordinal }
    }

    private enum class AccountKindHint { CASH, BANK }

    private fun resolve(settingId: Long?, liquid: List<Account>, hint: AccountKindHint): Long? =
        settingId?.takeIf { id -> liquid.any { it.id == id } }
            ?: liquid.firstOrNull { it.kind == if (hint == AccountKindHint.CASH) AccountKind.CASH else AccountKind.BANK }?.id
            ?: liquid.firstOrNull()?.id
}

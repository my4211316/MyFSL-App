package tw.myfsl.app.golden

import tw.myfsl.app.core.sample.SampleHousehold
import tw.myfsl.app.core.sample.SampleHousehold.BANK
import tw.myfsl.app.core.sample.SampleHousehold.BIRTHDAY
import tw.myfsl.app.core.sample.SampleHousehold.CARD_A
import tw.myfsl.app.core.sample.SampleHousehold.CARD_B
import tw.myfsl.app.core.sample.SampleHousehold.CAR_SERVICE
import tw.myfsl.app.core.sample.SampleHousehold.CONTEST
import tw.myfsl.app.core.sample.SampleHousehold.FUEL
import tw.myfsl.app.core.sample.SampleHousehold.HOUSEHOLD
import tw.myfsl.app.core.sample.SampleHousehold.LESSONS
import tw.myfsl.app.core.sample.SampleHousehold.LIVING
import tw.myfsl.app.core.sample.SampleHousehold.PHONE
import tw.myfsl.app.core.sample.SampleHousehold.RED_ENVELOPE
import tw.myfsl.app.core.sample.SampleHousehold.TRIP
import tw.myfsl.app.core.domain.AccountSummaryCalculator
import tw.myfsl.app.core.domain.BaselineBuilder
import tw.myfsl.app.core.domain.BudgetProgressCalculator
import tw.myfsl.app.core.domain.CheckInRules
import tw.myfsl.app.core.domain.ForecastResult
import tw.myfsl.app.core.domain.ForecastSummary
import tw.myfsl.app.core.domain.PlanSummaryCalculator
import tw.myfsl.app.core.domain.PlanValidator
import tw.myfsl.app.core.domain.RecordRules
import tw.myfsl.app.core.domain.ScenarioApplier
import tw.myfsl.app.core.model.Half
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.Period
import tw.myfsl.app.core.model.RepaymentMethod
import tw.myfsl.app.core.model.ScenarioChange
import org.junit.Test
import java.io.File

/**
 * 把設計稿需要的數字全部由引擎算出後寫成 JSON，設計稿與規格書都以這份為準。
 * 檔案：design/mockups/sample-numbers.json
 */
class DesignNumbersDump {

    private val snapshot = SampleHousehold.snapshot()
    private val base = BaselineBuilder.build(snapshot)
    private val oct = Period(2026, 10, Half.FIRST)

    private val integrate = listOf(
        ScenarioChange.AddLoan("整合貸款", 200_000, 6.5, 60, RepaymentMethod.EQUAL_PAYMENT, oct.index, BANK, BANK, Half.SECOND),
        ScenarioChange.PayOffDebts(listOf(CARD_A, CARD_B), BANK, oct.index),
        ScenarioChange.ChangeMethod(listOf(LIVING, FUEL, PHONE, CAR_SERVICE, TRIP), PaymentMethod.CREDIT_CARD, PaymentMethod.CASH, oct.index),
    )
    private val cut20 = listOf(
        ScenarioChange.AdjustItems(
            listOf(LIVING, HOUSEHOLD, FUEL, LESSONS, CONTEST, RED_ENVELOPE, BIRTHDAY, TRIP),
            -20.0,
            base.start.index,
        ),
    )

    private fun scenarioJson(name: String, result: ForecastResult): String {
        val lows = ForecastSummary.monthlyLows(result).map(ForecastSummary::thousands)
        return """
        "$name": {
          "lowestLiquid": ${result.lowestLiquid},
          "lowestPeriod": "${result.lowest?.period?.fullLabel}",
          "firstBelowSafety": ${result.firstBelowSafety?.let { "\"${it.period.fullLabel}\"" }},
          "monthsUntilBelow": ${result.firstBelowSafety?.let { ForecastSummary.monthsUntil(base.start, it.period) }},
          "structuralGapPerYear": ${result.structuralGapPerYear},
          "endCardDebt": ${result.endCardDebt},
          "endLoanDebt": ${result.endLoanDebt},
          "endTotalDebt": ${result.endTotalDebt},
          "totalCardInterest": ${result.totalCardInterest},
          "monthlyLowsThousands": ${lows.joinToString(", ", "[", "]")}
        }
        """.trimIndent()
    }

    @Test fun `匯出設計稿數字`() {
        val plan = PlanSummaryCalculator.summarize(snapshot, 2026)
        val progress = BudgetProgressCalculator.forMonth(snapshot)
        val accounts = AccountSummaryCalculator.overview(snapshot)
        val reconciles = CheckInRules.reconciles(snapshot)

        val groups = plan.groups.joinToString(",\n    ") { """"${it.group.name}": ${it.total}""" }
        val progressJson = progress.joinToString(",\n    ") {
            """{"item": "${it.item.name}", "method": "${it.method.label}", "group": "${it.groupName}", """ +
                """"planned": ${it.planned}, "actual": ${it.actual}, "spentPercent": ${it.spentPercent}, """ +
                """"timePercent": ${it.timePercent}, "paceGap": ${it.paceGapPercent}, "status": "${it.status.label}", """ +
                """"dailyAllowance": ${it.dailyAllowance}}"""
        }
        val cardsJson = accounts.cards.joinToString(",\n    ") {
            """{"name": "${it.account.name}", "balance": ${it.account.balance}, "limit": ${it.account.creditLimit}, """ +
                """"utilizationPercent": ${it.utilizationPercent}, "available": ${it.available}, """ +
                """"monthSpending": ${it.monthSpending}, "fixedPayment": ${it.fixedPayment}, """ +
                """"ratePercent": ${it.account.card?.revolvingRatePercent}, "interest": ${it.interest}, """ +
                """"minimumPayment": ${it.minimumPayment}, "dueDay": ${it.account.paymentDueDay}}"""
        }
        val loansJson = accounts.loans.joinToString(",\n    ") {
            """{"name": "${it.account.name}", "balance": ${it.account.balance}, "monthlyPayment": ${it.monthlyPayment}, """ +
                """"repaidPercent": ${it.repaidPercent}}"""
        }
        val reconcileJson = reconciles.joinToString(",\n    ") {
            """{"name": "${it.name}", "computed": ${it.computed}, "pendingRecent": ${it.pendingRecent}, """ +
                """"defaultItem": "${it.defaultItem?.name}"}"""
        }
        val issues = PlanValidator.validate(snapshot, 2026).joinToString(",\n    ") { """{"severity": "${it.severity}", "message": "${it.message}"}""" }

        val json = """
        {
          "today": "${snapshot.today}",
          "scenarios": {
            ${scenarioJson("base", ScenarioApplier.run(base, emptyList()))},
            ${scenarioJson("integrate", ScenarioApplier.run(base, integrate))},
            ${scenarioJson("cut20", ScenarioApplier.run(base, cut20))}
          },
          "plan2026": {
            "income": ${plan.totalIncome},
            "expense": ${plan.totalExpense},
            "cardSpending": ${plan.totalCardSpending},
            "nonCardSpending": ${plan.totalNonCardSpending},
            "cardPayments": ${plan.totalCardPayments},
            "loanPayments": ${plan.totalLoanPayments},
            "cardInterest": ${plan.totalCardInterest},
            "cardDebtIncrease": ${plan.cardDebtIncrease},
            "structuralGap": ${plan.structuralGap},
            "monthly": {
              "income": ${plan.income.joinToString(", ", "[", "]")},
              "cardSpending": ${plan.cardSpending.joinToString(", ", "[", "]")},
              "nonCardSpending": ${plan.nonCardSpending.joinToString(", ", "[", "]")},
              "cardInterest": ${plan.cardInterest.joinToString(", ", "[", "]")},
              "cardPayments": ${plan.cardPayments.joinToString(", ", "[", "]")},
              "loanPayments": ${plan.loanPayments.joinToString(", ", "[", "]")},
              "debtPayments": ${plan.debtPayments.joinToString(", ", "[", "]")},
              "cashFlow": ${plan.monthlyCashFlow.joinToString(", ", "[", "]")}
            },
            "cardDebtChangeSeptember": ${plan.cardDebtChange(9)},
            "groups": {
    $groups
            },
            "issues": [
    $issues
            ]
          },
          "progress": [
    $progressJson
          ],
          "accounts": {
            "liquid": ${accounts.liquid},
            "cardDebt": ${accounts.cardDebt},
            "loanDebt": ${accounts.loanDebt},
            "policyLoanDebt": ${accounts.policyLoanDebt},
            "totalDebt": ${accounts.totalDebt},
            "cardInterest": ${accounts.cardInterest},
            "cards": [
    $cardsJson
            ],
            "loans": [
    $loansJson
            ]
          },
          "checkIn": {
            "confirmLines": ${CheckInRules.confirmLines(snapshot).size},
            "reportLines": ${CheckInRules.reportLines(snapshot).size},
            "reconciles": [
    $reconcileJson
            ],
            "missedLabel": "${RecordRules.missedLabel(RecordRules.missedSummary(snapshot))}",
            "monthTotals": {
              "expense": ${RecordRules.monthTotals(snapshot, 2026, 9).expense},
              "income": ${RecordRules.monthTotals(snapshot, 2026, 9).income}
            }
          }
        }
        """.trimIndent()

        val file = File("../../design/mockups/sample-numbers.json")
        file.parentFile.mkdirs()
        file.writeText(json)
        println("wrote ${file.absolutePath}")
    }
}

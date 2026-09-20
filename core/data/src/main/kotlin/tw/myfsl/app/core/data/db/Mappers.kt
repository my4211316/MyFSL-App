package tw.myfsl.app.core.data.db

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.AccountKind
import tw.myfsl.app.core.model.ActualStatus
import tw.myfsl.app.core.model.CardInstallment
import tw.myfsl.app.core.model.CardStatement
import tw.myfsl.app.core.model.InstallmentFee
import tw.myfsl.app.core.model.CardTerms
import tw.myfsl.app.core.model.CheckIn
import tw.myfsl.app.core.model.Deferral
import tw.myfsl.app.core.model.EntrySource
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.ItemActual
import tw.myfsl.app.core.model.LedgerEntry
import tw.myfsl.app.core.model.LoanTerms
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.RepaymentMethod
import tw.myfsl.app.core.model.Scenario
import tw.myfsl.app.core.model.ScenarioChange
import tw.myfsl.app.core.model.Timing
import tw.myfsl.app.core.model.TrackingMode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

internal val scenarioJson = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = true
}

private val changesSerializer = ListSerializer(ScenarioChange.serializer())

/** 主鍵欄位用：null 存成空字串。 */
fun PaymentMethod?.toColumn(): String = this?.name ?: ""

fun String?.toPaymentMethod(): PaymentMethod? = if (this.isNullOrEmpty()) null else PaymentMethod.valueOf(this)

/** balance 由 repository 依快照與記帳另外計算。 */
fun AccountEntity.toModel() = Account(
    id = id,
    name = name,
    kind = AccountKind.valueOf(kind),
    creditLimit = creditLimit,
    paymentDueDay = paymentDueDay,
    issuer = issuer,
    statementDay = cardStatementDay,
    card = if (cardSchedule) CardTerms(revolvingRatePercent = cardRatePercent, payAccountId = cardPayAccountId) else null,
    loan = if (loanRatePercent != null && loanRemainingMonths != null && loanMethod != null &&
        loanPayAccountId != null && loanPayDay != null
    ) {
        LoanTerms(
            annualRatePercent = loanRatePercent,
            remainingMonths = loanRemainingMonths,
            method = RepaymentMethod.valueOf(loanMethod),
            payAccountId = loanPayAccountId,
            payDay = loanPayDay,
            originalPrincipal = loanOriginalPrincipal,
        )
    } else {
        null
    },
    archived = archived,
    sortOrder = sortOrder,
)

fun Account.toEntity() = AccountEntity(
    id = id,
    name = name,
    kind = kind.name,
    creditLimit = creditLimit,
    paymentDueDay = paymentDueDay,
    loanRatePercent = loan?.annualRatePercent,
    loanRemainingMonths = loan?.remainingMonths,
    loanMethod = loan?.method?.name,
    loanPayAccountId = loan?.payAccountId,
    loanPayDay = loan?.payDay,
    loanOriginalPrincipal = loan?.originalPrincipal,
    cardSchedule = card != null,
    cardRatePercent = card?.revolvingRatePercent,
    cardPayAccountId = card?.payAccountId,
    cardStatementDay = statementDay,
    issuer = issuer,
    archived = archived,
    sortOrder = sortOrder,
)

fun CardStatementEntity.toModel() = CardStatement(cardId, year, month, amount, minimumPayment, coversInterest)
fun CardStatement.toEntity() = CardStatementEntity(cardId, year, month, amount, minimumPayment, coversInterest)

fun PlanGroupEntity.toModel() = PlanGroup(id, name, sortOrder)
fun PlanGroup.toEntity() = PlanGroupEntity(id, name, sortOrder)

fun PlanItemEntity.toModel() = PlanItem(
    id = id,
    name = name,
    groupId = groupId,
    type = FlowType.valueOf(type),
    accountId = accountId,
    toAccountId = toAccountId,
    timing = Timing.valueOf(timing),
    flexibility = Flexibility.valueOf(flexibility),
    tracking = TrackingMode.valueOf(tracking),
    note = note,
    archived = archived,
    sortOrder = sortOrder,
    dueDay = dueDay,
    extraRepayment = extraRepayment,
    archivedFrom = archivedFrom,
)

fun PlanItem.toEntity() = PlanItemEntity(
    id = id,
    name = name,
    groupId = groupId,
    type = type.name,
    accountId = accountId,
    toAccountId = toAccountId,
    timing = timing.name,
    flexibility = flexibility.name,
    tracking = tracking.name,
    note = note,
    archived = archived,
    sortOrder = sortOrder,
    dueDay = dueDay,
    extraRepayment = extraRepayment,
    archivedFrom = archivedFrom,
)

fun ItemActualEntity.toModel() = ItemActual(
    itemId = itemId,
    year = year,
    month = month,
    status = ActualStatus.valueOf(status),
    updatedOn = LocalDate.ofEpochDay(updatedEpochDay),
)

fun ItemActual.toEntity() = ItemActualEntity(
    itemId = itemId,
    year = year,
    month = month,
    status = status.name,
    updatedEpochDay = updatedOn.toEpochDay(),
)

fun LedgerEntryEntity.toModel() = LedgerEntry(
    id = id,
    date = LocalDate.ofEpochDay(epochDay),
    type = FlowType.valueOf(type),
    amount = amount,
    itemId = itemId,
    method = method.toPaymentMethod(),
    accountId = accountId,
    toAccountId = toAccountId,
    note = note,
    source = EntrySource.valueOf(source),
    installmentId = installmentId,
    createdAt = createdAtMillis,
    postingKey = postingKey,
)

fun LedgerEntry.toEntity() = LedgerEntryEntity(
    id = id,
    epochDay = date.toEpochDay(),
    type = type.name,
    amount = amount,
    itemId = itemId,
    method = method?.name,
    accountId = accountId,
    toAccountId = toAccountId,
    note = note,
    source = source.name,
    installmentId = installmentId,
    createdAtMillis = createdAt,
    postingKey = postingKey,
)

fun DeferralEntity.toModel() = Deferral(
    id = id,
    itemId = itemId,
    method = method.toPaymentMethod(),
    fromYear = fromYear,
    fromMonth = fromMonth,
    dueYear = dueYear,
    dueMonth = dueMonth,
    amount = amount,
    settled = settled,
)

fun Deferral.toEntity() = DeferralEntity(
    id = id,
    itemId = itemId,
    method = method.toColumn(),
    fromYear = fromYear,
    fromMonth = fromMonth,
    dueYear = dueYear,
    dueMonth = dueMonth,
    amount = amount,
    settled = settled,
)

fun CardInstallmentEntity.toModel() = CardInstallment(
    id = id,
    cardAccountId = cardAccountId,
    itemId = itemId,
    purchaseDate = LocalDate.ofEpochDay(purchaseEpochDay),
    amount = amount,
    months = months,
    fee = InstallmentFee.valueOf(fee),
    feeValue = feeValue,
    firstPeriodIndex = firstPeriodIndex,
    note = note,
    settled = settled,
)

fun CardInstallment.toEntity() = CardInstallmentEntity(
    id = id,
    cardAccountId = cardAccountId,
    itemId = itemId,
    purchaseEpochDay = purchaseDate.toEpochDay(),
    amount = amount,
    months = months,
    fee = fee.name,
    feeValue = feeValue,
    firstPeriodIndex = firstPeriodIndex,
    note = note,
    settled = settled,
)

fun CheckInEntity.toModel() = CheckIn(id, LocalDate.ofEpochDay(epochDay), note)

fun ScenarioEntity.toModel() = Scenario(
    id = id,
    name = name,
    createdOn = LocalDate.ofEpochDay(createdEpochDay),
    changes = runCatching { scenarioJson.decodeFromString(changesSerializer, changesJson) }.getOrDefault(emptyList()),
    note = note,
)

fun Scenario.toEntity() = ScenarioEntity(
    id = id,
    name = name,
    createdEpochDay = createdOn.toEpochDay(),
    changesJson = scenarioJson.encodeToString(changesSerializer, changes),
    note = note,
)

package tw.myfsl.app.core.domain

import tw.myfsl.app.core.model.Account
import tw.myfsl.app.core.model.Flexibility
import tw.myfsl.app.core.model.FlowType
import tw.myfsl.app.core.model.Money
import tw.myfsl.app.core.model.MoneyFormat
import tw.myfsl.app.core.model.PaymentMethod
import tw.myfsl.app.core.model.PlanGroup
import tw.myfsl.app.core.model.PlanItem
import tw.myfsl.app.core.model.PlanLine
import tw.myfsl.app.core.model.Timing
import tw.myfsl.app.core.model.TrackingMode

/** 匯入方式。 */
enum class ImportMode(val label: String) {
    /** 覆蓋檔案裡出現的項目在該年度的金額；沒出現的項目保持不動。 */
    REPLACE_YEAR("取代這個年度"),

    /** 只新增檔案裡有、App 裡還沒有的項目。 */
    ADD_ONLY("只新增"),
}

/** 一個要匯入的項目：項目本身＋各支付方式的 12 個月金額。 */
data class ImportItem(
    val groupName: String,
    val item: PlanItem,
    val amounts: Map<PaymentMethod?, List<Money>>,
    /** 這個項目來自檔案的哪幾列。 */
    val lines: List<Int>,
) {
    val total: Money get() = amounts.values.sumOf { it.sum() }
}

data class ImportPreview(
    val year: Int,
    val items: List<ImportItem>,
    /** 檔案裡有、App 裡還沒有的群組。 */
    val newGroups: List<String>,
    val issues: List<PlanIssue>,
) {
    val itemCount: Int get() = items.size
    val lineCount: Int get() = items.sumOf { it.amounts.size }
    val income: Money get() = items.filter { it.item.type == FlowType.INCOME }.sumOf { it.total }
    val expense: Money get() = items.filter { it.item.type == FlowType.EXPENSE }.sumOf { it.total }
    val cardSpending: Money
        get() = items.filter { it.item.type == FlowType.EXPENSE }
            .sumOf { it.amounts[PaymentMethod.CREDIT_CARD]?.sum() ?: 0L }
    val errors: List<PlanIssue> get() = issues.filter { it.severity == Severity.ERROR }
    val canImport: Boolean get() = errors.isEmpty() && items.isNotEmpty()
}

/**
 * 年度計畫的 CSV 匯入與匯出。
 *
 * 一列是一個「項目 × 支付方式」，欄位順序貼近常見的年度預算表：
 * 群組、項目、類型、支付方式、時點、可調、追蹤、帳戶、轉入帳戶、備註、現行（年合計，選填）、1–12 月。
 */
object PlanImport {

    val COLUMNS = listOf("群組", "項目", "類型", "支付方式", "時點", "可調", "追蹤", "帳戶", "轉入帳戶", "備註", "現行") +
        (1..12).map { "${it}月" }

    private val MONTH_NAMES = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二")

    /** 空白範本（含兩列示範）。 */
    fun template(): String = buildString {
        appendLine(COLUMNS.joinToString(","))
        appendLine("收入,薪資,收入,,上半月,否,自動計入,薪轉帳戶,,,780000,65000,65000,65000,65000,65000,65000,65000,65000,65000,65000,65000,65000")
        appendLine("生活,生活費,支出,現金,上下各半,是,依記帳,,,錢包,108000,9000,9000,9000,9000,9000,9000,9000,9000,9000,9000,9000,9000")
    }

    /** 把目前的計畫匯出成同一個格式，可以在 Excel 改完再匯回來。 */
    fun export(
        year: Int,
        groups: List<PlanGroup>,
        items: List<PlanItem>,
        amounts: Map<PlanLine, List<Money>>,
        accounts: List<Account>,
    ): String {
        val groupName = groups.associate { it.id to it.name }
        val accountName = accounts.associate { it.id to it.name }
        return buildString {
            appendLine(COLUMNS.joinToString(","))
            items.filter { !it.archived }.forEach { item ->
                val lines = amounts.keys.filter { it.itemId == item.id }.sortedBy { it.method?.ordinal ?: -1 }
                val rows = lines.ifEmpty { listOf(PlanLine(item.id, if (item.type == FlowType.EXPENSE) PaymentMethod.CASH else null)) }
                rows.forEach { line ->
                    val months = amounts[line] ?: List(12) { 0L }
                    val cells = listOf(
                        groupName[item.groupId].orEmpty(),
                        item.name,
                        item.type.label,
                        line.method?.label.orEmpty(),
                        item.timing.label,
                        if (item.flexibility == Flexibility.FLEXIBLE) "是" else "否",
                        item.tracking.label,
                        accountName[item.accountId].orEmpty(),
                        accountName[item.toAccountId].orEmpty(),
                        item.note,
                        months.sum().toString(),
                    ) + months.map { it.toString() }
                    appendLine(cells.joinToString(",") { escape(it) })
                }
            }
        }
    }

    private fun escape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /** 解析檔案並產生預覽；有錯誤時 [ImportPreview.canImport] 為 false。 */
    fun parse(
        text: String,
        year: Int,
        accounts: List<Account>,
        groups: List<PlanGroup> = emptyList(),
    ): ImportPreview {
        val issues = mutableListOf<PlanIssue>()
        val rows = splitRows(text)
        if (rows.isEmpty()) {
            return ImportPreview(year, emptyList(), emptyList(), listOf(PlanIssue(Severity.ERROR, "檔案是空的")))
        }
        val header = rows.first().second.map { normalize(it) }
        val index = columnIndex(header)
        val missing = listOf("項目", "類型").filter { index[it] == null }
        if (missing.isNotEmpty()) {
            return ImportPreview(year, emptyList(), emptyList(), listOf(PlanIssue(Severity.ERROR, "表頭少了必要欄位：${missing.joinToString("、")}")))
        }
        val monthColumns = (1..12).map { index["${it}月"] }
        if (monthColumns.any { it == null }) {
            issues += PlanIssue(Severity.WARNING, "表頭沒有 12 個月完整的欄位，缺少的月份會當成 0")
        }

        val byKey = LinkedHashMap<Pair<String, String>, MutableImportItem>()
        rows.drop(1).forEach { (lineNumber, cells) ->
            fun cell(name: String): String = index[name]?.let { cells.getOrNull(it) }?.trim().orEmpty()
            val name = cell("項目")
            if (name.isEmpty() && cells.all { it.isBlank() }) return@forEach
            if (name.isEmpty()) {
                issues += PlanIssue(Severity.ERROR, "第 $lineNumber 列沒有項目名稱")
                return@forEach
            }
            val type = flowType(cell("類型"))
            if (type == null) {
                issues += PlanIssue(Severity.ERROR, "第 $lineNumber 列的類型「${cell("類型")}」無法辨識，要填收入、支出或轉帳")
                return@forEach
            }
            val months = (0 until 12).map { m ->
                val raw = monthColumns[m]?.let { cells.getOrNull(it) }.orEmpty()
                val parsed = amount(raw)
                if (parsed == null) {
                    issues += PlanIssue(Severity.ERROR, "第 $lineNumber 列 ${MONTH_NAMES[m]}月的金額「${raw.trim()}」看不懂")
                    0L
                } else {
                    parsed
                }
            }
            val method = paymentMethod(cell("支付方式"))
            if (type == FlowType.EXPENSE && method == null) {
                issues += PlanIssue(Severity.ERROR, "第 $lineNumber 列「$name」是支出，要填支付方式（現金、信用卡或轉帳）")
                return@forEach
            }
            if (type != FlowType.EXPENSE && cell("支付方式").isNotEmpty()) {
                issues += PlanIssue(Severity.WARNING, "第 $lineNumber 列「$name」是${type.label}，支付方式會被忽略")
            }

            val accountId = accountId(cell("帳戶"), accounts)
            val toAccountId = accountId(cell("轉入帳戶"), accounts)
            if (cell("帳戶").isNotEmpty() && accountId == null) {
                issues += PlanIssue(Severity.ERROR, "第 $lineNumber 列找不到帳戶「${cell("帳戶")}」")
            }
            if (cell("轉入帳戶").isNotEmpty() && toAccountId == null) {
                issues += PlanIssue(Severity.ERROR, "第 $lineNumber 列找不到轉入帳戶「${cell("轉入帳戶")}」")
            }
            when (type) {
                FlowType.INCOME -> if (accountId == null && cell("帳戶").isEmpty()) {
                    issues += PlanIssue(Severity.ERROR, "第 $lineNumber 列「$name」是收入，要填入帳帳戶")
                }

                FlowType.TRANSFER -> {
                    if (accountId == null && cell("帳戶").isEmpty()) {
                        issues += PlanIssue(Severity.ERROR, "第 $lineNumber 列「$name」是轉帳，要填轉出帳戶")
                    }
                    if (toAccountId == null && cell("轉入帳戶").isEmpty()) {
                        issues += PlanIssue(Severity.ERROR, "第 $lineNumber 列「$name」是轉帳，要填轉入帳戶")
                    }
                    if (accountId != null && accountId == toAccountId) {
                        issues += PlanIssue(Severity.ERROR, "第 $lineNumber 列「$name」轉出與轉入是同一個帳戶")
                    }
                }

                FlowType.EXPENSE -> Unit
            }

            // 現行（年合計）只用來檢查
            val declared = amount(cell("現行"))
            if (cell("現行").isNotBlank() && declared != null && declared != months.sum()) {
                issues += PlanIssue(
                    Severity.WARNING,
                    "第 $lineNumber 列「$name」現行 ${MoneyFormat.currency(declared)} 和 12 個月合計 " +
                        "${MoneyFormat.currency(months.sum())} 不一樣（差 ${MoneyFormat.signed(months.sum() - declared)}）",
                )
            }
            if (months.all { it == 0L }) {
                issues += PlanIssue(Severity.INFO, "第 $lineNumber 列「$name」12 個月都是 0")
            }

            val groupName = cell("群組").ifEmpty { "未分類" }
            val key = groupName to name
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = MutableImportItem(
                    groupName = groupName,
                    item = PlanItem(
                        name = name,
                        groupId = 0,
                        type = type,
                        accountId = accountId,
                        toAccountId = toAccountId,
                        timing = timing(cell("時點")),
                        flexibility = if (yes(cell("可調"))) Flexibility.FLEXIBLE else Flexibility.FIXED,
                        tracking = tracking(cell("追蹤")),
                        note = cell("備註"),
                    ),
                    amounts = linkedMapOf(method to months),
                    lines = mutableListOf(lineNumber),
                )
            } else {
                if (existing.item.type != type) {
                    issues += PlanIssue(Severity.ERROR, "第 $lineNumber 列「$name」的類型和前面那列不一樣")
                }
                if (existing.amounts.containsKey(method)) {
                    issues += PlanIssue(
                        Severity.ERROR,
                        "「$name」的${method?.label ?: type.label}出現兩次（第 ${existing.lines.joinToString("、")} 列與第 $lineNumber 列）",
                    )
                } else {
                    existing.amounts[method] = months
                }
                existing.lines += lineNumber
            }
        }

        val items = byKey.values.map { ImportItem(it.groupName, it.item, it.amounts.toMap(), it.lines.toList()) }
        val existingGroups = groups.map { normalize(it.name) }.toSet()
        val newGroups = items.map { it.groupName }.distinct().filter { normalize(it) !in existingGroups }
        if (items.isNotEmpty()) {
            issues += PlanIssue(Severity.INFO, "共 ${items.size} 個項目、${items.sumOf { it.amounts.size }} 個計畫列")
            if (newGroups.isNotEmpty()) issues += PlanIssue(Severity.INFO, "會新增群組：${newGroups.joinToString("、")}")
        } else {
            issues += PlanIssue(Severity.ERROR, "檔案裡沒有可以匯入的項目")
        }
        return ImportPreview(year, items, newGroups, issues.sortedByDescending { it.severity.ordinal })
    }

    private class MutableImportItem(
        val groupName: String,
        val item: PlanItem,
        val amounts: LinkedHashMap<PaymentMethod?, List<Money>>,
        val lines: MutableList<Int>,
    )

    // ---------- 解析工具 ----------

    /** 回傳（行號, 欄位）；支援逗號或 Tab 分隔、雙引號、CRLF 與 BOM。 */
    internal fun splitRows(text: String): List<Pair<Int, List<String>>> {
        val clean = text.removePrefix(BOM).replace("\r\n", "\n").replace('\r', '\n')
        if (clean.isBlank()) return emptyList()
        val firstLine = clean.lineSequence().first()
        val separator = if (firstLine.count { it == '\t' } > firstLine.count { it == ',' }) '\t' else ','
        val rows = mutableListOf<Pair<Int, List<String>>>()
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var line = 1
        fun endCell() {
            cells += cell.toString()
            cell.clear()
        }
        fun endRow() {
            endCell()
            if (cells.any { it.isNotBlank() }) rows += line to cells.toList()
            cells.clear()
        }
        var i = 0
        while (i < clean.length) {
            val c = clean[i]
            when {
                quoted && c == '"' && clean.getOrNull(i + 1) == '"' -> {
                    cell.append('"'); i++
                }

                c == '"' -> quoted = !quoted
                !quoted && c == separator -> endCell()
                !quoted && c == '\n' -> {
                    endRow(); line++
                }

                else -> cell.append(c)
            }
            i++
        }
        endRow()
        return rows
    }

    /** 比對群組與項目名稱用：去掉空白、全形括號轉半形。資料層匯入時也用它判斷是不是同一個項目。 */
    fun normalize(value: String): String =
        value.trim().replace(" ", "").replace("　", "").replace("（", "(").replace("）", ")")

    private val ALIASES = mapOf(
        "群組" to listOf("群組", "大類", "分類", "類別"),
        "項目" to listOf("項目", "名稱", "科目"),
        "類型" to listOf("類型", "收支", "種類"),
        "支付方式" to listOf("支付方式", "付款", "付款方式", "支付"),
        "時點" to listOf("時點", "時間", "上下半月"),
        "可調" to listOf("可調", "可調整", "彈性"),
        "追蹤" to listOf("追蹤", "追蹤方式", "控管"),
        "帳戶" to listOf("帳戶", "入帳帳戶", "轉出帳戶", "扣款帳戶"),
        "轉入帳戶" to listOf("轉入帳戶", "轉入", "對方帳戶"),
        "備註" to listOf("備註", "說明", "註記"),
        "現行" to listOf("現行", "年", "全年", "年合計", "合計"),
    )

    private fun columnIndex(header: List<String>): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        ALIASES.forEach { (key, names) ->
            header.indexOfFirst { it in names }.takeIf { it >= 0 }?.let { result[key] = it }
        }
        (1..12).forEach { month ->
            val names = setOf("${month}月", "${MONTH_NAMES[month - 1]}月", month.toString())
            header.indexOfFirst { it in names }.takeIf { it >= 0 }?.let { result["${month}月"] = it }
        }
        return result
    }

    /** 金額：允許 $、逗號、空白；括號為負；空白與「—」視為 0；看不懂時為 null。 */
    internal fun amount(raw: String): Money? {
        var text = normalize(raw).replace("$", "").replace("＄", "").replace(",", "").replace("，", "")
        if (text.isEmpty() || text == "-" || text == "—" || text == "–" || text == "/") return 0
        var negative = false
        if (text.startsWith("(") && text.endsWith(")")) {
            negative = true
            text = text.substring(1, text.length - 1)
        }
        if (text.startsWith("−") || text.startsWith("-")) {
            negative = true
            text = text.drop(1)
        }
        val value = text.toDoubleOrNull() ?: return null
        val rounded = Math.round(value)
        return if (negative) -rounded else rounded
    }

    private fun flowType(raw: String): FlowType? = when (normalize(raw)) {
        "收入", "income", "收" -> FlowType.INCOME
        "支出", "expense", "支" -> FlowType.EXPENSE
        "轉帳", "transfer", "轉" -> FlowType.TRANSFER
        else -> null
    }

    private fun paymentMethod(raw: String): PaymentMethod? = when (normalize(raw)) {
        "現金", "cash" -> PaymentMethod.CASH
        "信用卡", "刷卡", "card" -> PaymentMethod.CREDIT_CARD
        "轉帳", "轉", "transfer" -> PaymentMethod.TRANSFER
        else -> null
    }

    private fun timing(raw: String): Timing = when (normalize(raw)) {
        "上半月", "上", "月初" -> Timing.FIRST_HALF
        "下半月", "下", "月底" -> Timing.SECOND_HALF
        else -> Timing.SPLIT
    }

    private fun tracking(raw: String): TrackingMode = when (normalize(raw)) {
        "依記帳", "記帳" -> TrackingMode.LEDGER
        "每週回報", "回報", "信封" -> TrackingMode.REPORT
        "到期確認", "確認", "不定期" -> TrackingMode.CONFIRM
        else -> TrackingMode.AUTO
    }

    private fun yes(raw: String): Boolean = normalize(raw) in setOf("是", "Y", "y", "yes", "true", "1", "可調")

    private fun accountId(raw: String, accounts: List<Account>): Long? {
        if (raw.isBlank()) return null
        val target = normalize(raw)
        return accounts.firstOrNull { normalize(it.name) == target }?.id
            ?: accounts.firstOrNull { normalize(it.name).startsWith(target) }?.id
    }
}

/** App 內編輯計畫金額的快捷。 */
object PlanEditRules {

    /** 12 個月同額。 */
    fun everyMonth(amount: Money): List<Money> = List(12) { amount.coerceAtLeast(0) }

    /** 只在指定月份放金額，其他月份 0。 */
    fun onlyMonths(amount: Money, months: Set<Int>): List<Money> =
        List(12) { if (it + 1 in months) amount.coerceAtLeast(0) else 0L }

    /** 整列調整百分比，四捨五入、最低 0。 */
    fun adjust(months: List<Money>, percent: Double): List<Money> =
        months.map { Math.round(it * (1 + percent / 100.0)).coerceAtLeast(0) }

    /** 把年金額平均分到 12 個月，餘數放在最後一個月。 */
    fun spreadYear(total: Money): List<Money> {
        if (total <= 0) return List(12) { 0L }
        val each = total / 12
        return List(12) { if (it == 11) total - each * 11 else each }
    }

    fun clear(): List<Money> = List(12) { 0L }

    /** 檢查一列可不可以存：金額不能是負的。 */
    fun validate(name: String, months: List<Money>): List<PlanIssue> = buildList {
        if (name.isBlank()) add(PlanIssue(Severity.ERROR, "項目名稱不能空白"))
        if (months.size != 12) add(PlanIssue(Severity.ERROR, "要有 12 個月的金額"))
        if (months.any { it < 0 }) add(PlanIssue(Severity.ERROR, "金額不能是負數"))
        if (months.all { it == 0L }) add(PlanIssue(Severity.INFO, "12 個月都是 0，這個項目不會出現在試算裡"))
    }
}

/** UTF-8 BOM（Excel 存檔時會加在開頭）。用碼位寫，避免原始碼裡出現看不見的字元。 */
private const val BOM = "\uFEFF"

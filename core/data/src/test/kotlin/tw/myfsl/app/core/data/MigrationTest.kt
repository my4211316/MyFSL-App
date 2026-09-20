package tw.myfsl.app.core.data

import tw.myfsl.app.core.data.db.Migrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 對照 Room 匯出到 app/schemas 的各版本 JSON，確認每一次版本升級都有 migration，
 * 而且新增的欄位與資料表都有對應的 SQL。刪欄位需要重建資料表，這裡直接擋下來。
 */
class MigrationTest {

    private val dir = File("schemas/tw.myfsl.app.core.data.db.AppDatabase")

    private data class Column(val name: String, val affinity: String, val notNull: Boolean)

    private fun load(version: Int): Map<String, List<Column>> {
        val root = Json.parseToJsonElement(File(dir, "$version.json").readText()).jsonObject
        val entities = root.getValue("database").jsonObject.getValue("entities").jsonArray
        return entities.associate { e ->
            val entity = e as JsonObject
            entity.getValue("tableName").jsonPrimitive.content to entity.getValue("fields").jsonArray.map { f ->
                val field = f.jsonObject
                Column(
                    field.getValue("columnName").jsonPrimitive.content,
                    field.getValue("affinity").jsonPrimitive.content,
                    field["notNull"]?.jsonPrimitive?.content == "true",
                )
            }
        }
    }

    private val versions: List<Int> by lazy {
        dir.listFiles().orEmpty().mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()
    }

    @Test fun `每個版本都有 migration，資料庫版本等於最新 schema`() {
        assertTrue("找不到 schema 檔：${dir.absolutePath}", versions.isNotEmpty())
        assertEquals("schema 版本要從 1 連續編號", (1..versions.last()).toList(), versions)
        val latest = versions.last()
        assertEquals((2..latest).toList(), Migrations.statements.keys.sorted())
        assertEquals(latest, Migrations.all.maxOfOrNull { it.endVersion } ?: 1)
        Migrations.all.forEach { assertEquals(it.startVersion + 1, it.endVersion) }
    }

    @Test fun `新增的欄位與資料表都有對應的 SQL，沒有刪除欄位`() {
        versions.zipWithNext().forEach { (from, to) ->
            val old = load(from)
            val new = load(to)
            val sql = Migrations.statements.getValue(to).joinToString("\n")

            old.forEach { (table, columns) ->
                val now = new[table]?.map { it.name }.orEmpty()
                val removed = columns.map { it.name } - now.toSet()
                // 刪欄位一定要重建資料表：建新表 → 搬資料 → 丟舊表 → 改名。
                val rebuilt = sql.contains("CREATE TABLE IF NOT EXISTS `${table}_new`") &&
                    sql.contains("INSERT INTO `${table}_new`") &&
                    sql.contains("DROP TABLE `$table`") &&
                    sql.contains("ALTER TABLE `${table}_new` RENAME TO `$table`")
                assertTrue("v$to 刪了 $table 的欄位 $removed，需要重建資料表的 migration", removed.isEmpty() || rebuilt)
            }
            new.forEach { (table, columns) ->
                if (table !in old) {
                    assertTrue("v$to 新增資料表 $table 沒有 CREATE TABLE", sql.contains("CREATE TABLE IF NOT EXISTS `$table`"))
                    return@forEach
                }
                val existing = old.getValue(table).map { it.name }.toSet()
                columns.filter { it.name !in existing }.forEach { column ->
                    val expected = "ALTER TABLE `$table` ADD COLUMN `${column.name}` ${column.affinity}"
                    assertTrue("v$to 缺少：$expected", sql.contains(expected))
                    if (column.notNull) {
                        assertTrue("v$to 的 NOT NULL 欄位 ${column.name} 需要預設值", sql.contains("$expected NOT NULL DEFAULT"))
                    }
                }
            }
        }
    }
}

package com.aneb.probe.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AnebDatabaseV13StructureTest {
    @Test
    fun productionAndUncachedOpenersShareOneMigrationSafeBuilder() {
        val source = databaseSource()
        assertEquals(1, "Room\\.databaseBuilder\\(".toRegex().findAll(source).count())
        assertTrue(source.contains("private fun productionBuilder("))
        assertTrue(source.contains("internal fun openUncached("))
        assertTrue(functionRegion(source, "openUncached").contains("productionBuilder("))
        assertTrue(functionRegion(source, "get").contains("productionBuilder("))
        assertTrue(source.contains("PRODUCTION_MIGRATIONS"))
        assertTrue(source.contains("MIGRATION_12_13"))
        assertTrue(source.contains("LEGACY_DESTRUCTIVE_FROM"))
        assertTrue(source.contains("fallbackToDestructiveMigrationFrom"))
        assertFalse(source.contains(".fallbackToDestructiveMigration()"))
    }

    @Test
    fun schema13FreezesThreeNormalizedPrototypeTablesAndPreservesOldElevenEntities() {
        val oldEntities = entities(schemaPath(12))
        val newEntities = entities(schemaPath(13))
        assertEquals(11, oldEntities.size)
        assertEquals(oldEntities.keys, newEntities.keys.intersect(oldEntities.keys))
        oldEntities.forEach { (tableName, oldEntity) ->
            assertEquals("v13 changed old entity $tableName", oldEntity, newEntities[tableName])
        }
        assertEquals(EXACT_PROTOTYPE_TABLES, newEntities.keys - oldEntities.keys)

        assertEntity(
            entity = newEntities.getValue("prototype_campaign"),
            fields = mapOf(
                "campaignId" to FieldSpec("TEXT", true),
                "nodeBaseUrl" to FieldSpec("TEXT", true),
                "runUrl" to FieldSpec("TEXT", true),
                "capabilityUrl" to FieldSpec("TEXT", true),
                "rawCapabilityBody" to FieldSpec("TEXT", true),
                "capabilityIdentityJson" to FieldSpec("TEXT", true),
                "summaryJson" to FieldSpec("TEXT", true),
            ),
            primaryKey = listOf("campaignId"),
            uniqueIndices = emptySet(),
            foreignKeys = emptySet(),
        )
        assertEntity(
            entity = newEntities.getValue("prototype_run"),
            fields = mapOf(
                "campaignId" to FieldSpec("TEXT", true),
                "runId" to FieldSpec("TEXT", true),
                "runIndex" to FieldSpec("INTEGER", true),
                "conditionId" to FieldSpec("TEXT", true),
                "status" to FieldSpec("TEXT", true),
                "taskSuccess" to FieldSpec("INTEGER", true),
                "scoreEligible" to FieldSpec("INTEGER", true),
                "eventsExpected" to FieldSpec("INTEGER", true),
                "eventsReceived" to FieldSpec("INTEGER", true),
                "failureReason" to FieldSpec("TEXT", false),
                "terminalReceiptValid" to FieldSpec("INTEGER", false),
                "metricsJson" to FieldSpec("TEXT", false),
            ),
            primaryKey = listOf("campaignId", "runId"),
            uniqueIndices = setOf(listOf("campaignId", "runIndex")),
            foreignKeys = setOf(
                ForeignKeySpec(
                    table = "prototype_campaign",
                    columns = listOf("campaignId"),
                    referencedColumns = listOf("campaignId"),
                ),
            ),
        )
        assertEntity(
            entity = newEntities.getValue("prototype_evidence_event"),
            fields = mapOf(
                "campaignId" to FieldSpec("TEXT", true),
                "runId" to FieldSpec("TEXT", true),
                "eventOrdinal" to FieldSpec("INTEGER", true),
                "eventJson" to FieldSpec("TEXT", true),
            ),
            primaryKey = listOf("campaignId", "runId", "eventOrdinal"),
            uniqueIndices = emptySet(),
            foreignKeys = setOf(
                ForeignKeySpec(
                    table = "prototype_run",
                    columns = listOf("campaignId", "runId"),
                    referencedColumns = listOf("campaignId", "runId"),
                ),
            ),
        )
    }

    @Test
    fun prototypeDaoUsesAbortOnlyBatchWritesAndExplicitNormalizedOrdering() {
        val source = allDataSources()
        val start = source.indexOf("interface PrototypeCampaignDao")
        require(start >= 0) { "missing PrototypeCampaignDao" }
        val nextDao = source.indexOf("\n@Dao", start + 1).takeIf { it >= 0 } ?: source.length
        val region = source.substring(start, nextDao)
        val joinedStringLiterals = region.replace(Regex("\"\\s*\\+\\s*\""), "")
        val abortInsert = Regex(
            "@Insert\\s*\\(\\s*onConflict\\s*=\\s*OnConflictStrategy\\.ABORT\\s*\\)",
        )
        assertEquals(3, abortInsert.findAll(region).count())
        assertTrue(Regex("List\\s*<\\s*PrototypeRunEntity\\s*>").containsMatchIn(region))
        assertTrue(Regex("List\\s*<\\s*PrototypeEvidenceEventEntity\\s*>").containsMatchIn(region))
        assertTrue(
            Regex(
                "FROM\\s+prototype_run[^\"]*ORDER\\s+BY\\s+runIndex",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(joinedStringLiterals),
        )
        assertTrue(
            Regex(
                "FROM\\s+prototype_evidence_event[^\"]*ORDER\\s+BY\\s+eventOrdinal",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(joinedStringLiterals),
        )
        listOf(
            "@Upsert",
            "@Update",
            "OnConflictStrategy.REPLACE",
            "OnConflictStrategy.IGNORE",
            "INSERT OR REPLACE",
        ).forEach { forbidden ->
            assertFalse("forbidden DAO write: $forbidden", region.contains(forbidden))
        }
    }

    @Test
    fun migration12To13IsExactlyAdditiveForTheThreePrototypeTables() {
        val source = databaseSource()
        val sqlStart = source.indexOf("internal val MIGRATION_12_13_SQL")
        val migrationStart = source.indexOf("internal val MIGRATION_12_13 =", sqlStart + 1)
        require(sqlStart >= 0 && migrationStart > sqlStart) { "missing v12 to v13 migration authority" }
        val sqlRegion = source.substring(sqlStart, migrationStart)
        val statements = migrationSqlStatements(sqlRegion)
        val normalizedStatements = statements.map { statement ->
            statement.replace(Regex("\\s+"), " ").trim()
        }
        assertEquals(4, normalizedStatements.size)
        val createTable = Regex(
            "^CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`([^`]+)`(?:\\s|\\()",
            RegexOption.IGNORE_CASE,
        )
        val createUniqueIndex = Regex(
            "^CREATE\\s+UNIQUE\\s+INDEX\\s+IF\\s+NOT\\s+EXISTS\\s+`([^`]+)`" +
                "\\s+ON\\s+`([^`]+)`",
            RegexOption.IGNORE_CASE,
        )
        val tableMatches = normalizedStatements.mapNotNull(createTable::find)
        val indexMatches = normalizedStatements.mapNotNull(createUniqueIndex::find)
        assertEquals(
            EXACT_PROTOTYPE_TABLES,
            tableMatches.map { match -> match.groupValues[1] }.toSet(),
        )
        assertEquals(3, tableMatches.size)
        assertEquals(1, indexMatches.size)
        assertEquals(4, tableMatches.size + indexMatches.size)
        assertEquals("index_prototype_run_campaignId_runIndex", indexMatches.single().groupValues[1])
        assertEquals("prototype_run", indexMatches.single().groupValues[2])
        val forbiddenStatement = Regex(
            "^\\s*(DROP|ALTER|INSERT|UPDATE|DELETE|REPLACE|RENAME|TRUNCATE)\\b",
            RegexOption.IGNORE_CASE,
        )
        normalizedStatements.forEach { statement ->
            assertFalse(
                "v12 to v13 migration contains forbidden statement: $statement",
                forbiddenStatement.containsMatchIn(statement),
            )
        }
        entities(schemaPath(12)).keys.forEach { oldTable ->
            val oldTableIdentifier = Regex(
                "(?<![A-Za-z0-9_])`?${Regex.escape(oldTable)}`?(?![A-Za-z0-9_])",
                RegexOption.IGNORE_CASE,
            )
            assertFalse(
                "migration touches old table $oldTable",
                normalizedStatements.any(oldTableIdentifier::containsMatchIn),
            )
        }

        val nextDeclaration = source.indexOf("\n        internal val ", migrationStart + 1)
            .takeIf { it >= 0 }
            ?: source.indexOf("\n        fun get(", migrationStart + 1).takeIf { it >= 0 }
            ?: source.length
        val migrationRegion = source.substring(migrationStart, nextDeclaration)
        assertTrue(migrationRegion.contains("Migration(12, 13)"))
        assertEquals(
            "MIGRATION_12_13_SQL.forEach(db::execSQL)",
            bracedBody(migrationRegion, "override fun migrate").replace(Regex("\\s+"), ""),
        )
    }

    private fun assertEntity(
        entity: JsonObject,
        fields: Map<String, FieldSpec>,
        primaryKey: List<String>,
        uniqueIndices: Set<List<String>>,
        foreignKeys: Set<ForeignKeySpec>,
    ) {
        val actualFields = entity.getValue("fields").jsonArray.associate { fieldElement ->
            val field = fieldElement.jsonObject
            field.getValue("columnName").jsonPrimitive.content to FieldSpec(
                affinity = field.getValue("affinity").jsonPrimitive.content,
                notNull = field.getValue("notNull").jsonPrimitive.boolean,
            )
        }
        assertEquals(fields, actualFields)
        val actualPrimaryKey = entity.getValue("primaryKey").jsonObject
        assertFalse(actualPrimaryKey.getValue("autoGenerate").jsonPrimitive.boolean)
        assertEquals(
            primaryKey,
            actualPrimaryKey.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content },
        )
        val indices = entity.getValue("indices").jsonArray.map { it.jsonObject }
        assertEquals(uniqueIndices.size, indices.size)
        assertEquals(
            uniqueIndices,
            indices.filter { index -> index.getValue("unique").jsonPrimitive.boolean }
                .map { index ->
                    index.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content }
                }
                .toSet(),
        )
        assertTrue(indices.all { index -> index.getValue("unique").jsonPrimitive.boolean })
        assertEquals(
            foreignKeys,
            entity.getValue("foreignKeys").jsonArray.map { foreignKeyElement ->
                val foreignKey = foreignKeyElement.jsonObject
                assertEquals("NO ACTION", foreignKey.getValue("onDelete").jsonPrimitive.content)
                assertEquals("NO ACTION", foreignKey.getValue("onUpdate").jsonPrimitive.content)
                ForeignKeySpec(
                    table = foreignKey.getValue("table").jsonPrimitive.content,
                    columns = foreignKey.getValue("columns").jsonArray.map { it.jsonPrimitive.content },
                    referencedColumns = foreignKey.getValue("referencedColumns").jsonArray
                        .map { it.jsonPrimitive.content },
                )
            }.toSet(),
        )
    }

    private fun functionRegion(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        require(start >= 0) { "missing function $name" }
        val nextFunction = source.indexOf("\n        fun ", start + 1)
        val companionEnd = source.indexOf("\n    }\n}", start + 1)
        val end = listOf(nextFunction, companionEnd)
            .filter { it > start }
            .minOrNull()
            ?: source.length
        return source.substring(start, end)
    }

    private fun entities(path: Path): Map<String, JsonObject> {
        val root = Json.parseToJsonElement(
            String(Files.readAllBytes(path), Charsets.UTF_8),
        ).jsonObject
        return root.getValue("database").jsonObject
            .getValue("entities").jsonArray
            .associate { element ->
                val entity = element.jsonObject
                entity.getValue("tableName").jsonPrimitive.content to entity
            }
    }

    private fun quotedStringContents(source: String): List<String> =
        Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
            .findAll(source)
            .map { match ->
                match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }
            .toList()

    private fun migrationSqlStatements(source: String): List<String> {
        val callStart = source.indexOf("listOf(")
        require(callStart >= 0) { "MIGRATION_12_13_SQL must be a literal listOf" }
        val bodyStart = callStart + "listOf(".length
        var index = bodyStart
        var parenthesisDepth = 1
        var inString = false
        var escaped = false
        while (index < source.length && parenthesisDepth > 0) {
            val character = source[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '(' -> parenthesisDepth++
                    ')' -> parenthesisDepth--
                }
            }
            index++
        }
        require(parenthesisDepth == 0 && !inString) { "unterminated MIGRATION_12_13_SQL listOf" }
        val body = source.substring(bodyStart, index - 1)
        val items = mutableListOf<String>()
        var itemStart = 0
        inString = false
        escaped = false
        parenthesisDepth = 0
        body.forEachIndexed { offset, character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '(' -> parenthesisDepth++
                    ')' -> parenthesisDepth--
                    ',' -> if (parenthesisDepth == 0) {
                        body.substring(itemStart, offset).trim()
                            .takeIf(String::isNotEmpty)
                            ?.let(items::add)
                        itemStart = offset + 1
                    }
                }
            }
        }
        body.substring(itemStart).trim().takeIf(String::isNotEmpty)?.let(items::add)
        val literalExpression = Regex(
            "\\s*\"(?:\\\\.|[^\"\\\\])*\"" +
                "(?:\\s*\\+\\s*\"(?:\\\\.|[^\"\\\\])*\")*\\s*",
        )
        return items.map { item ->
            require(literalExpression.matches(item)) {
                "migration SQL list item must contain only concatenated string literals"
            }
            quotedStringContents(item).joinToString(separator = "")
        }
    }

    private fun bracedBody(source: String, declaration: String): String {
        val declarationStart = source.indexOf(declaration)
        require(declarationStart >= 0) { "missing $declaration" }
        val bodyStart = source.indexOf('{', declarationStart)
        require(bodyStart >= 0) { "missing body for $declaration" }
        var depth = 1
        var index = bodyStart + 1
        while (index < source.length && depth > 0) {
            when (source[index]) {
                '{' -> depth++
                '}' -> depth--
            }
            index++
        }
        require(depth == 0) { "unterminated body for $declaration" }
        return source.substring(bodyStart + 1, index - 1)
    }

    private fun databaseSource(): String = String(
        Files.readAllBytes(databaseSourcePath()),
        Charsets.UTF_8,
    ).replace("\r\n", "\n")

    private fun allDataSources(): String = Files.list(dataSourceDirectory()).use { paths ->
        paths.filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
            .sorted()
            .map { path -> String(Files.readAllBytes(path), Charsets.UTF_8).replace("\r\n", "\n") }
            .toList()
            .joinToString(separator = "\n")
    }

    private fun databaseSourcePath(): Path = locate(
        Path.of("probe/src/main/java/com/aneb/probe/data/AnebDatabase.kt"),
        Path.of("app/probe/src/main/java/com/aneb/probe/data/AnebDatabase.kt"),
    )

    private fun dataSourceDirectory(): Path = databaseSourcePath().parent

    private fun schemaPath(version: Int): Path = locate(
        Path.of("probe/schemas/com.aneb.probe.data.AnebDatabase/$version.json"),
        Path.of("app/probe/schemas/com.aneb.probe.data.AnebDatabase/$version.json"),
    )

    private fun locate(vararg candidates: Path): Path {
        val start = Path.of("").toAbsolutePath().normalize()
        val roots = generateSequence(start) { path -> path.parent }
        return requireNotNull(
            roots.asSequence()
                .flatMap { root -> candidates.asSequence().map(root::resolve) }
                .firstOrNull(Files::isRegularFile),
        ) {
            "required repository file is missing from $start: ${candidates.joinToString()}"
        }
    }

    private data class FieldSpec(
        val affinity: String,
        val notNull: Boolean,
    )

    private data class ForeignKeySpec(
        val table: String,
        val columns: List<String>,
        val referencedColumns: List<String>,
    )

    private companion object {
        val EXACT_PROTOTYPE_TABLES = setOf(
            "prototype_campaign",
            "prototype_run",
            "prototype_evidence_event",
        )
    }
}

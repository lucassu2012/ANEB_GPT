package com.aneb.probe.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnebDatabaseMigrationV20Test {
    @Test
    fun migratesV19ToV20Safely() {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val databaseName = "m20-${UUID.randomUUID().toString().take(8)}.db"
        val sentinel = "RAW_SENTINEL_V19_API_PROBE"
        var v19Helper: SupportSQLiteOpenHelper? = null
        var v20Helper: SupportSQLiteOpenHelper? = null

        context.deleteDatabase(databaseName)
        val databasePath = context.getDatabasePath(databaseName)
        println("MIGRATION_FIXTURE_DB_PATH_LENGTH=${databasePath.absolutePath.length}")
        assertTrue(databasePath.absolutePath.length < 200)
        databasePath.parentFile?.mkdirs()
        try {
            v19Helper = openHelper(
                databaseName = databaseName,
                callback = object : SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(SCHEMA_19_API_PROBE_CREATE_SQL)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        fail("unexpected v19 upgrade $oldVersion->$newVersion")
                    }
                },
            )
            val v19 = v19Helper.writableDatabase
            assertEquals(19, v19.version)
            assertEquals(
                41L,
                v19.insert(
                    "api_probe_result",
                    SQLiteDatabase.CONFLICT_ABORT,
                    legacyRow(id = 41L, sentinel = sentinel),
                ),
            )
            v19Helper.close()
            v19Helper = null

            val migrations = AnebDatabase.Companion::class.java.declaredMethods
                .asSequence()
                .filter { method ->
                    method.parameterCount == 0 &&
                        Migration::class.java.isAssignableFrom(method.returnType)
                }
                .map { method ->
                    method.isAccessible = true
                    method.invoke(AnebDatabase.Companion) as Migration
                }
                .filter { migration -> migration.startVersion == 19 && migration.endVersion == 20 }
                .toList()
            assertEquals("exactly one 19 to 20 migration must be declared", 1, migrations.size)
            val migration = migrations.single()
            var migrationCalls = 0

            v20Helper = openHelper(
                databaseName = databaseName,
                callback = object : SupportSQLiteOpenHelper.Callback(20) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        fail("v20 reopen must upgrade the existing v19 database")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        assertEquals(19, oldVersion)
                        assertEquals(20, newVersion)
                        migrationCalls += 1
                        migration.migrate(db)
                    }
                },
            )
            val v20 = v20Helper.writableDatabase
            assertEquals(20, v20.version)
            assertEquals(1, migrationCalls)
            assertV20Columns(v20)
            assertMigratedLegacyRow(v20, sentinel)

            assertEquals(
                42L,
                v20.insert(
                    "api_probe_result",
                    SQLiteDatabase.CONFLICT_ABORT,
                    safeV20Row(),
                ),
            )
            v20.query("SELECT seq FROM sqlite_sequence WHERE name = 'api_probe_result'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(42L, cursor.getLong(0))
                assertFalse(cursor.moveToNext())
            }
        } finally {
            runCatching { v20Helper?.close() }
            runCatching { v19Helper?.close() }
            context.deleteDatabase(databaseName)
        }
    }

    private fun openHelper(
        databaseName: String,
        callback: SupportSQLiteOpenHelper.Callback,
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(
            RuntimeEnvironment.getApplication().applicationContext,
        )
            .name(databaseName)
            .callback(callback)
            .build(),
    )

    private fun legacyRow(id: Long, sentinel: String): ContentValues = ContentValues().apply {
        put("id", id)
        put("startedAtEpochMs", 1_700_000_000_041L)
        put("provider", "openai_compat")
        put("protocolId", "openai_chat")
        put("baseUrl", sentinel)
        put("model", sentinel)
        put("claimScope", "hostile_scope_$sentinel")
        put("httpCode", 503)
        put("error", sentinel)
        put("ttftMs", 12.5)
        put("itlMedianMs", 23.5)
        put("itlP95Ms", 45.5)
        put("itlSampleCount", 6)
        put("tokenEventCount", 7)
        put("totalMs", 88.5)
        put("totalTextChars", 99)
        put("inputTokens", 10)
        put("outputTokens", 11)
        put("stopReason", sentinel)
        put("parseErrors", 12)
        put("protocolError", sentinel)
        put("proxyDetected", true)
        put("vpnDetected", false)
        put("guardMetadata", sentinel)
        put("readCount", 13)
        put("totalBytes", 14L)
    }

    private fun safeV20Row(): ContentValues = ContentValues().apply {
        put("startedAtEpochMs", 1_700_000_000_042L)
        put("provider", "openai_compat")
        put("protocolId", "openai_chat")
        put("claimScope", EXPECTED_CLAIM_SCOPE)
        put("httpCode", 200)
        put("ttftMs", 1.5)
        put("itlMedianMs", 2.5)
        put("itlP95Ms", 3.5)
        put("itlSampleCount", 4)
        put("tokenEventCount", 5)
        put("totalMs", 6.5)
        put("totalTextChars", 7)
        put("inputTokens", 8)
        put("outputTokens", 9)
        put("parseErrors", 0)
        put("proxyDetected", false)
        put("vpnDetected", false)
        put("readCount", 10)
        put("totalBytes", 11L)
        put("resultCode", "HTTP_ACCEPTED")
        put("transportClass", "HTTP")
        put("errorBodyByteCount", 0L)
        putNull("errorBodySha256")
        put("errorBodyDigestScope", "NONE")
        put("errorBodyTruncated", false)
        put("completionClass", "COMPLETED")
    }

    private fun assertV20Columns(db: SupportSQLiteDatabase) {
        val observed = db.query("PRAGMA table_info(`api_probe_result`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                    add(
                        ColumnInfo(
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                            notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                            defaultValue = if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex),
                            primaryKeyPosition = cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                        ),
                    )
                }
            }
        }
        assertEquals(EXPECTED_V20_COLUMNS, observed)
        assertTrue(observed.all { column -> column.defaultValue == null })
        LEGACY_RAW_COLUMNS.forEach { legacy ->
            assertFalse(observed.any { column -> column.name == legacy })
        }
    }

    private fun assertMigratedLegacyRow(db: SupportSQLiteDatabase, sentinel: String) {
        db.query("SELECT * FROM api_probe_result WHERE id = 41").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(41L, cursor.long("id"))
            assertEquals(1_700_000_000_041L, cursor.long("startedAtEpochMs"))
            assertEquals("openai_compat", cursor.string("provider"))
            assertEquals("openai_chat", cursor.string("protocolId"))
            assertEquals(EXPECTED_CLAIM_SCOPE, cursor.string("claimScope"))
            assertEquals(503, cursor.int("httpCode"))
            assertEquals(12.5, cursor.double("ttftMs"), 0.0)
            assertEquals(23.5, cursor.double("itlMedianMs"), 0.0)
            assertEquals(45.5, cursor.double("itlP95Ms"), 0.0)
            assertEquals(6, cursor.int("itlSampleCount"))
            assertEquals(7, cursor.int("tokenEventCount"))
            assertEquals(88.5, cursor.double("totalMs"), 0.0)
            assertEquals(99, cursor.int("totalTextChars"))
            assertEquals(10, cursor.int("inputTokens"))
            assertEquals(11, cursor.int("outputTokens"))
            assertEquals(12, cursor.int("parseErrors"))
            assertEquals(1, cursor.int("proxyDetected"))
            assertEquals(0, cursor.int("vpnDetected"))
            assertEquals(13, cursor.int("readCount"))
            assertEquals(14L, cursor.long("totalBytes"))
            assertEquals("LEGACY_UNCLASSIFIED", cursor.string("resultCode"))
            assertEquals("NONE", cursor.string("transportClass"))
            assertEquals(0L, cursor.long("errorBodyByteCount"))
            assertNull(cursor.nullableString("errorBodySha256"))
            assertEquals("NONE", cursor.string("errorBodyDigestScope"))
            assertEquals(0, cursor.int("errorBodyTruncated"))
            assertEquals("LEGACY_UNCLASSIFIED", cursor.string("completionClass"))
            assertFalse(cursor.moveToNext())
        }

        db.query("SELECT * FROM api_probe_result").use { cursor ->
            val rendered = StringBuilder()
            while (cursor.moveToNext()) {
                repeat(cursor.columnCount) { index ->
                    if (!cursor.isNull(index)) rendered.append(cursor.getString(index))
                }
            }
            assertEquals(0, occurrences(rendered.toString(), sentinel))
        }
    }

    private fun android.database.Cursor.long(column: String): Long =
        getLong(getColumnIndexOrThrow(column))

    private fun android.database.Cursor.int(column: String): Int =
        getInt(getColumnIndexOrThrow(column))

    private fun android.database.Cursor.double(column: String): Double =
        getDouble(getColumnIndexOrThrow(column))

    private fun android.database.Cursor.string(column: String): String =
        getString(getColumnIndexOrThrow(column))

    private fun android.database.Cursor.nullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var fromIndex = 0
        while (true) {
            val next = text.indexOf(needle, fromIndex)
            if (next < 0) return count
            count += 1
            fromIndex = next + needle.length
        }
    }

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKeyPosition: Int,
    )

    private companion object {
        const val EXPECTED_CLAIM_SCOPE = "application_end_to_end_to_llm_api"

        val LEGACY_RAW_COLUMNS = setOf(
            "baseUrl",
            "model",
            "error",
            "stopReason",
            "protocolError",
            "guardMetadata",
        )

        val EXPECTED_V20_COLUMNS = listOf(
            ColumnInfo("id", "INTEGER", true, null, 1),
            ColumnInfo("startedAtEpochMs", "INTEGER", true, null, 0),
            ColumnInfo("provider", "TEXT", true, null, 0),
            ColumnInfo("protocolId", "TEXT", true, null, 0),
            ColumnInfo("claimScope", "TEXT", true, null, 0),
            ColumnInfo("httpCode", "INTEGER", false, null, 0),
            ColumnInfo("ttftMs", "REAL", false, null, 0),
            ColumnInfo("itlMedianMs", "REAL", false, null, 0),
            ColumnInfo("itlP95Ms", "REAL", false, null, 0),
            ColumnInfo("itlSampleCount", "INTEGER", true, null, 0),
            ColumnInfo("tokenEventCount", "INTEGER", true, null, 0),
            ColumnInfo("totalMs", "REAL", false, null, 0),
            ColumnInfo("totalTextChars", "INTEGER", true, null, 0),
            ColumnInfo("inputTokens", "INTEGER", false, null, 0),
            ColumnInfo("outputTokens", "INTEGER", false, null, 0),
            ColumnInfo("parseErrors", "INTEGER", true, null, 0),
            ColumnInfo("proxyDetected", "INTEGER", true, null, 0),
            ColumnInfo("vpnDetected", "INTEGER", true, null, 0),
            ColumnInfo("readCount", "INTEGER", false, null, 0),
            ColumnInfo("totalBytes", "INTEGER", false, null, 0),
            ColumnInfo("resultCode", "TEXT", true, null, 0),
            ColumnInfo("transportClass", "TEXT", true, null, 0),
            ColumnInfo("errorBodyByteCount", "INTEGER", true, null, 0),
            ColumnInfo("errorBodySha256", "TEXT", false, null, 0),
            ColumnInfo("errorBodyDigestScope", "TEXT", true, null, 0),
            ColumnInfo("errorBodyTruncated", "INTEGER", true, null, 0),
            ColumnInfo("completionClass", "TEXT", true, null, 0),
        )

        const val SCHEMA_19_API_PROBE_CREATE_SQL =
            "CREATE TABLE IF NOT EXISTS `api_probe_result` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`startedAtEpochMs` INTEGER NOT NULL, " +
                "`provider` TEXT NOT NULL, " +
                "`protocolId` TEXT NOT NULL, " +
                "`baseUrl` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, " +
                "`claimScope` TEXT NOT NULL, " +
                "`httpCode` INTEGER, " +
                "`error` TEXT, " +
                "`ttftMs` REAL, " +
                "`itlMedianMs` REAL, " +
                "`itlP95Ms` REAL, " +
                "`itlSampleCount` INTEGER NOT NULL, " +
                "`tokenEventCount` INTEGER NOT NULL, " +
                "`totalMs` REAL, " +
                "`totalTextChars` INTEGER NOT NULL, " +
                "`inputTokens` INTEGER, " +
                "`outputTokens` INTEGER, " +
                "`stopReason` TEXT, " +
                "`parseErrors` INTEGER NOT NULL, " +
                "`protocolError` TEXT, " +
                "`proxyDetected` INTEGER NOT NULL, " +
                "`vpnDetected` INTEGER NOT NULL, " +
                "`guardMetadata` TEXT, " +
                "`readCount` INTEGER, " +
                "`totalBytes` INTEGER)"
    }
}

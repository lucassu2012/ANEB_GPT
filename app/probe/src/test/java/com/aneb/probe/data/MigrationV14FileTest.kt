package com.aneb.probe.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MigrationV14FileTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val databaseNames = mutableListOf<String>()

    @After
    fun deleteDatabases() {
        databaseNames.forEach { databaseName -> context.deleteDatabase(databaseName) }
    }

    @Test
    fun productionBuilderMigratesRealV13RowsWithoutInventingAuthority(): Unit = runBlocking {
        val databaseName = "prototype-migration-${UUID.randomUUID()}.db".also(databaseNames::add)
        createRealV13File(databaseName)

        val database = AnebDatabase.openUncached(context, databaseName)
        val writable = database.openHelper.writableDatabase
        assertEquals(14, writable.version)
        writable.query(
            "SELECT summaryJson, captureAuthorityJson FROM prototype_campaign WHERE campaignId = ?",
            arrayOf(CAMPAIGN_ID),
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(SUMMARY_JSON, cursor.getString(0))
            assertNull(cursor.getString(1))
            assertEquals(false, cursor.moveToNext())
        }
        writable.query(
            "SELECT status, runAuthorityJson FROM prototype_run WHERE campaignId = ?",
            arrayOf(CAMPAIGN_ID),
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("COMPLETE", cursor.getString(0))
            assertNull(cursor.getString(1))
            assertEquals(false, cursor.moveToNext())
        }
        database.close()
    }

    private fun createRealV13File(databaseName: String) {
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        val schema = Json.parseToJsonElement(
            String(Files.readAllBytes(schemaV13Path()), Charsets.UTF_8),
        ).jsonObject.getValue("database").jsonObject
        val database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            schema.getValue("entities").jsonArray.forEach { element ->
                val entity = element.jsonObject
                val tableName = entity.getValue("tableName").jsonPrimitive.content
                database.execSQL(
                    entity.getValue("createSql").jsonPrimitive.content
                        .replace("\${TABLE_NAME}", tableName),
                )
                entity.getValue("indices").jsonArray.forEach { index ->
                    database.execSQL(
                        index.jsonObject.getValue("createSql").jsonPrimitive.content
                            .replace("\${TABLE_NAME}", tableName),
                    )
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { query ->
                database.execSQL(query.jsonPrimitive.content)
            }
            database.version = 13
            database.execSQL(
                "INSERT INTO prototype_campaign " +
                    "(campaignId,nodeBaseUrl,runUrl,capabilityUrl,rawCapabilityBody," +
                    "capabilityIdentityJson,summaryJson) VALUES(?,?,?,?,?,?,?)",
                arrayOf(
                    CAMPAIGN_ID,
                    "http://192.168.1.8:18088",
                    "http://192.168.1.8:18088/api/v1/prototype/runs",
                    "http://192.168.1.8:18088/api/v1/prototype/capabilities",
                    "{}",
                    "{}",
                    SUMMARY_JSON,
                ),
            )
            database.execSQL(
                "INSERT INTO prototype_run " +
                    "(campaignId,runId,runIndex,conditionId,status,taskSuccess,scoreEligible," +
                    "eventsExpected,eventsReceived,failureReason,terminalReceiptValid,metricsJson) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(
                    CAMPAIGN_ID,
                    RUN_ID,
                    1,
                    "baseline_v0.1",
                    "COMPLETE",
                    1,
                    1,
                    120,
                    120,
                    null,
                    1,
                    "{}",
                ),
            )
        } finally {
            database.close()
        }
    }

    private fun schemaV13Path(): Path {
        val relative = Path.of("schemas", "com.aneb.probe.data.AnebDatabase", "13.json")
        return requireNotNull(
            listOf(
                Path.of("probe").resolve(relative),
                Path.of("app", "probe").resolve(relative),
                relative,
            ).firstOrNull(Files::isRegularFile),
        ) { "committed Room v13 schema must be available to the JVM migration test" }
    }

    private companion object {
        const val CAMPAIGN_ID = "prototype-v13-campaign"
        const val RUN_ID = "prototype-v13-run-1"
        const val SUMMARY_JSON = "{\"schema_version\":\"aneb-prototype-campaign-summary-0.1\"}"
    }
}

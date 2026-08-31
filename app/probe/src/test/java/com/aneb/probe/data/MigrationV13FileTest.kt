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
class MigrationV13FileTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val databaseNames = mutableListOf<String>()

    @After
    fun deleteDatabases() {
        databaseNames.forEach { databaseName -> context.deleteDatabase(databaseName) }
    }

    @Test
    fun productionBuilderMigratesRealV12FileWithoutWipeAndReopens(): Unit = runBlocking {
        val databaseName = uniqueDatabaseName("migration")
        val sentinels = createRealV12File(databaseName)

        var database = AnebDatabase.openUncached(context, databaseName)
        assertV13State(database, sentinels)
        database.close()

        database = AnebDatabase.openUncached(context, databaseName)
        assertV13State(database, sentinels)
        database.close()
    }

    private suspend fun assertV13State(
        database: AnebDatabase,
        sentinels: V12Sentinels,
    ) {
        val writable = database.openHelper.writableDatabase
        assertEquals(13, writable.version)
        assertEquals(
            setOf("prototype_campaign", "prototype_run", "prototype_evidence_event"),
            writable.query(
                "SELECT name FROM sqlite_master " +
                    "WHERE type = 'table' AND name LIKE 'prototype_%' ORDER BY name"
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            },
        )
        assertEquals(
            sentinels.basicSpeed,
            database.basicSpeedResultDao().byId(sentinels.basicSpeed.runId),
        )
        assertEquals(
            sentinels.reportBody,
            database.reportBodyDao().forRun(sentinels.reportBody.runId),
        )
    }

    private fun createRealV12File(databaseName: String): V12Sentinels {
        val databaseFile = context.getDatabasePath(databaseName)
        databaseFile.parentFile?.mkdirs()
        val schema = Json.parseToJsonElement(
            String(Files.readAllBytes(schemaV12Path()), Charsets.UTF_8)
        ).jsonObject
            .getValue("database").jsonObject
        val database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        try {
            schema.getValue("entities").jsonArray.forEach { entity ->
                val objectValue = entity.jsonObject
                val tableName = objectValue.getValue("tableName").jsonPrimitive.content
                database.execSQL(
                    objectValue.getValue("createSql").jsonPrimitive.content
                        .replace("\${TABLE_NAME}", tableName)
                )
                objectValue.getValue("indices").jsonArray.forEach { index ->
                    database.execSQL(
                        index.jsonObject.getValue("createSql").jsonPrimitive.content
                            .replace("\${TABLE_NAME}", tableName)
                    )
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { query ->
                database.execSQL(query.jsonPrimitive.content)
            }
            database.version = 12
            database.execSQL(
                "INSERT INTO basic_speed_result (" +
                    "runId, startedAtEpochMs, serverBase, claimScope, profileId, profileVersion, " +
                    "conclusionPolicyId, status, downloadMbps, uploadMbps, pingMs, jitterMs, " +
                    "requestLossRate, postLoadPingMs, downloadBytes, uploadBytes, transferErrors" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    V12_SENTINEL_RUN_ID,
                    1_725_000_000_123L,
                    "https://v12.example.invalid/节点",
                    "app_to_node_application_layer",
                    "basic-speed-v0.1",
                    "0.1",
                    "basic-speed-conclusion-v0.1",
                    "partial",
                    null,
                    17.25,
                    28.5,
                    null,
                    0.125,
                    31.75,
                    1_234_567L,
                    7_654_321L,
                    "[\"timeout\",\"Unicode-错误\"]",
                ),
            )
            database.execSQL(
                "INSERT INTO report_body (runId, body) VALUES (?, ?)",
                arrayOf(V12_REPORT_RUN_ID, V12_REPORT_BODY),
            )
        } finally {
            database.close()
        }
        return V12Sentinels(
            basicSpeed = BasicSpeedResultEntity(
                runId = V12_SENTINEL_RUN_ID,
                startedAtEpochMs = 1_725_000_000_123L,
                serverBase = "https://v12.example.invalid/节点",
                claimScope = "app_to_node_application_layer",
                profileId = "basic-speed-v0.1",
                profileVersion = "0.1",
                conclusionPolicyId = "basic-speed-conclusion-v0.1",
                status = "partial",
                downloadMbps = null,
                uploadMbps = 17.25,
                pingMs = 28.5,
                jitterMs = null,
                requestLossRate = 0.125,
                postLoadPingMs = 31.75,
                downloadBytes = 1_234_567L,
                uploadBytes = 7_654_321L,
                transferErrors = "[\"timeout\",\"Unicode-错误\"]",
            ),
            reportBody = ReportBodyEntity(
                runId = V12_REPORT_RUN_ID,
                body = V12_REPORT_BODY,
            ),
        )
    }

    private fun uniqueDatabaseName(prefix: String): String =
        "prototype-$prefix-${UUID.randomUUID()}.db".also(databaseNames::add)

    private fun schemaV12Path(): Path {
        val relative = Path.of(
            "schemas",
            "com.aneb.probe.data.AnebDatabase",
            "12.json",
        )
        val candidates = listOf(
            Path.of("probe").resolve(relative),
            Path.of("app", "probe").resolve(relative),
            relative,
        )
        return requireNotNull(candidates.firstOrNull(Files::isRegularFile)) {
            "committed Room v12 schema must be available to the JVM migration test"
        }
    }

    private companion object {
        const val V12_SENTINEL_RUN_ID = "v12-sentinel-保留"
        const val V12_REPORT_RUN_ID = "v12-report-保留"
        const val V12_REPORT_BODY = "{\"version\":12,\"message\":\"Unicode-报告保留\"}"
    }

    private data class V12Sentinels(
        val basicSpeed: BasicSpeedResultEntity,
        val reportBody: ReportBodyEntity,
    )
}

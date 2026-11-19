package com.aneb.probe.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.aneb.probe.engine.VoiceRunner
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class MigrationV15VoiceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val databaseNames = mutableListOf<String>()

    @After
    fun deleteDatabases() {
        databaseNames.forEach { context.deleteDatabase(it) }
    }

    @Test
    fun schema15AddsOnlyVoiceResultAndPreservesAllFourteenV14EntitiesUnchanged() {
        val v14Entities = entities(schemaPath(14))
        val v15Entities = entities(schemaPath(15))
        assertEquals(14, v14Entities.size)
        assertEquals(15, v15Entities.size)
        assertEquals(setOf("voice_result"), v15Entities.keys - v14Entities.keys)
        v14Entities.forEach { (tableName, oldEntity) ->
            assertEquals("v15 altered existing v14 table $tableName", oldEntity, v15Entities[tableName])
        }

        val voice = v15Entities.getValue("voice_result")
        val actualFields = voice.getValue("fields").jsonArray.associate { elem ->
            val obj = elem.jsonObject
            obj.getValue("columnName").jsonPrimitive.content to (
                obj.getValue("affinity").jsonPrimitive.content to obj.getValue("notNull").jsonPrimitive.boolean
                )
        }
        val expectedFields = linkedMapOf(
            "id" to ("INTEGER" to true),
            "tsEpochMs" to ("INTEGER" to true),
            "caliber" to ("TEXT" to false),
            "lowConfidence" to ("INTEGER" to true),
            "rttMs" to ("REAL" to false),
            "jitterMs" to ("REAL" to false),
            "upFrameJitterMs" to ("REAL" to false),
            "downFrameJitterMs" to ("REAL" to false),
            "mouthEarBudgetMs" to ("REAL" to false),
            "framesSent" to ("INTEGER" to false),
            "framesRecv" to ("INTEGER" to false),
            "ttfbP50Ms" to ("REAL" to false),
            "ttfbP95Ms" to ("REAL" to false),
            "downNetJitterMs" to ("REAL" to false),
            "mouthEarProxyMs" to ("REAL" to false),
            "turnSwitchP50Ms" to ("REAL" to false),
            "bargeStopMaxMs" to ("REAL" to false),
            "turnsOk" to ("INTEGER" to false),
            "m7MaxFrameGapMs" to ("REAL" to false),
            "voiceNearZeroArrivalRatio" to ("REAL" to false),
        )
        assertEquals(expectedFields, actualFields)
        val pk = voice.getValue("primaryKey").jsonObject
        assertTrue(pk.getValue("autoGenerate").jsonPrimitive.boolean)
        assertEquals(listOf("id"), pk.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(voice.getValue("indices").jsonArray.isEmpty())
        assertTrue(voice.getValue("foreignKeys").jsonArray.isEmpty())

        assertEquals(14, AnebDatabase.MIGRATION_14_15.startVersion)
        assertEquals(15, AnebDatabase.MIGRATION_14_15.endVersion)
        assertEquals(1, AnebDatabase.MIGRATION_14_15_SQL.size)
        val createSql = voice.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", "voice_result")
        assertEquals(createSql, AnebDatabase.MIGRATION_14_15_SQL.single())
        val dbSource = String(Files.readAllBytes(databaseSourcePath()), Charsets.UTF_8)
        assertTrue(dbSource.contains("MIGRATION_14_15,"))
        val upper = createSql.uppercase()
        assertFalse(upper.contains("DROP "))
        assertFalse(upper.contains("ALTER "))
        assertFalse(upper.contains("DEFAULT "))
    }

    @Test
    fun migratesRealV14ToV15AndRoundTripsVoiceResults(): Unit = runBlocking {
        val dbName = "v14-${UUID.randomUUID()}.db".also(databaseNames::add)
        createRealV14FileWithSentinels(dbName)

        val db = AnebDatabase.openUncached(context, dbName)
        try {
            assertEquals(15, db.openHelper.writableDatabase.version)
            assertEquals(
                "{\"schema_version\":\"aneb-prototype-campaign-summary-0.1\"}",
                db.prototypeCampaignDao().campaign(SENTINEL_CAMPAIGN)?.summaryJson,
            )

            val repo = VoiceResultRepository(db.voiceResultDao())
            // 1. Direct DAO row with all 17 nullable columns set to null (R-10: SQLite stores NULL, never coerced to 0)
            val allNullId = db.voiceResultDao().insert(
                VoiceResultEntity(
                    tsEpochMs = 1_760_000_000_000L,
                    caliber = null,
                    lowConfidence = false,
                    rttMs = null,
                    jitterMs = null,
                    upFrameJitterMs = null,
                    downFrameJitterMs = null,
                    mouthEarBudgetMs = null,
                    framesSent = null,
                    framesRecv = null,
                    ttfbP50Ms = null,
                    ttfbP95Ms = null,
                    downNetJitterMs = null,
                    mouthEarProxyMs = null,
                    turnSwitchP50Ms = null,
                    bargeStopMaxMs = null,
                    turnsOk = null,
                    m7MaxFrameGapMs = null,
                    voiceNearZeroArrivalRatio = null,
                )
            )
            assertTrue(allNullId > 0L)

            // 2. All-null Double metrics Done sample via VoiceResultRepository (R-10: never coerced to 0.0)
            val nullSample = VoiceRunner.Sample(
                phase = VoiceRunner.Phase.Done,
                rttMs = null,
                jitterMs = null,
                upFrameJitterMs = null,
                downFrameJitterMs = null,
                mouthEarBudgetMs = null,
                framesSent = 0,
                framesRecv = 0,
                progress = 1f,
            )
            val nullId = repo.persistIfDone(nullSample, tsEpochMs = 1_760_000_001_000L)!!
            assertTrue(nullId > allNullId)

            // 3. Full v2 SIM_CALIBER Done sample including M7 max gap + near-zero ratio
            val simSample = VoiceRunner.Sample(
                phase = VoiceRunner.Phase.Done,
                rttMs = 24.5,
                jitterMs = 3.2,
                upFrameJitterMs = 6.1,
                downFrameJitterMs = null,
                mouthEarBudgetMs = null,
                framesSent = 400,
                framesRecv = 480,
                progress = 1f,
                caliber = VoiceRunner.SIM_CALIBER,
                ttfbP50Ms = 115.0,
                ttfbP95Ms = 180.0,
                downNetJitterMs = 4.8,
                mouthEarProxyMs = 142.3,
                turnSwitchP50Ms = 190.0,
                bargeStopMaxMs = 95.0,
                turnsOk = 8,
                lowConfidence = true,
                m7MaxFrameGapMs = 4550.0,
                voiceNearZeroArrivalRatio = 0.125,
            )
            val simId = repo.persistIfDone(simSample, tsEpochMs = 1_760_000_002_000L)!!
            assertTrue(simId > nullId)

            val recent = repo.recent(10)
            assertEquals(3, recent.size)
            val newest = recent[0]
            assertEquals(1_760_000_002_000L, newest.tsEpochMs)
            assertEquals(VoiceRunner.SIM_CALIBER, newest.caliber)
            assertTrue(newest.lowConfidence)
            assertEquals(24.5, newest.rttMs!!, 1e-9)
            assertEquals(142.3, newest.mouthEarProxyMs!!, 1e-9)
            assertEquals(4550.0, newest.m7MaxFrameGapMs!!, 1e-9)
            assertEquals(0.125, newest.voiceNearZeroArrivalRatio!!, 1e-9)
            assertEquals(8, newest.turnsOk)

            val middle = recent[1]
            assertEquals(1_760_000_001_000L, middle.tsEpochMs)
            assertNull(middle.caliber)
            assertFalse(middle.lowConfidence)
            assertNull(middle.rttMs)
            assertNull(middle.jitterMs)
            assertNull(middle.upFrameJitterMs)
            assertNull(middle.downFrameJitterMs)
            assertNull(middle.mouthEarBudgetMs)
            assertEquals(0, middle.framesSent)
            assertEquals(0, middle.framesRecv)
            assertEquals(0, middle.turnsOk)
            assertNull(middle.ttfbP50Ms)
            assertNull(middle.mouthEarProxyMs)
            assertNull(middle.m7MaxFrameGapMs)
            assertNull(middle.voiceNearZeroArrivalRatio)

            val oldest = recent[2]
            assertEquals(1_760_000_000_000L, oldest.tsEpochMs)
            assertNull(oldest.caliber)
            assertFalse(oldest.lowConfidence)
            assertNull(oldest.rttMs)
            assertNull(oldest.framesSent)
            assertNull(oldest.framesRecv)
            assertNull(oldest.turnsOk)
            assertNull(oldest.m7MaxFrameGapMs)
            assertNull(oldest.voiceNearZeroArrivalRatio)
        } finally {
            db.close()
        }
    }

    private fun createRealV14FileWithSentinels(databaseName: String) {
        val dbFile = context.getDatabasePath(databaseName)
        dbFile.parentFile?.mkdirs()
        val schema = Json.parseToJsonElement(
            String(Files.readAllBytes(schemaPath(14)), Charsets.UTF_8),
        ).jsonObject.getValue("database").jsonObject
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            schema.getValue("entities").jsonArray.forEach { element ->
                val entity = element.jsonObject
                val tableName = entity.getValue("tableName").jsonPrimitive.content
                sqlite.execSQL(
                    entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", tableName),
                )
                entity.getValue("indices").jsonArray.forEach { index ->
                    sqlite.execSQL(
                        index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", tableName),
                    )
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { query ->
                sqlite.execSQL(query.jsonPrimitive.content)
            }
            sqlite.version = 14
            sqlite.execSQL(
                "INSERT INTO prototype_campaign " +
                    "(campaignId,nodeBaseUrl,runUrl,capabilityUrl,rawCapabilityBody,capabilityIdentityJson,summaryJson,captureAuthorityJson) " +
                    "VALUES(?,?,?,?,?,?,?,?)",
                arrayOf(
                    SENTINEL_CAMPAIGN,
                    "http://192.168.1.8:18088",
                    "http://192.168.1.8:18088/api/v1/prototype/runs",
                    "http://192.168.1.8:18088/api/v1/prototype/capabilities",
                    "{}",
                    "{}",
                    "{\"schema_version\":\"aneb-prototype-campaign-summary-0.1\"}",
                    null,
                ),
            )
        } finally {
            sqlite.close()
        }
    }

    private fun entities(path: Path): Map<String, JsonObject> =
        Json.parseToJsonElement(String(Files.readAllBytes(path), Charsets.UTF_8)).jsonObject
            .getValue("database").jsonObject
            .getValue("entities").jsonArray
            .associate {
                val obj = it.jsonObject
                obj.getValue("tableName").jsonPrimitive.content to obj
            }

    private fun schemaPath(version: Int): Path = locate(
        Path.of("probe/schemas/com.aneb.probe.data.AnebDatabase/$version.json"),
        Path.of("app/probe/schemas/com.aneb.probe.data.AnebDatabase/$version.json"),
    )

    private fun databaseSourcePath(): Path = locate(
        Path.of("probe/src/main/java/com/aneb/probe/data/AnebDatabase.kt"),
        Path.of("app/probe/src/main/java/com/aneb/probe/data/AnebDatabase.kt"),
    )

    private fun locate(vararg candidates: Path): Path {
        val start = Path.of("").toAbsolutePath().normalize()
        return requireNotNull(
            generateSequence(start) { it.parent }
                .flatMap { root -> candidates.asSequence().map(root::resolve) }
                .firstOrNull(Files::isRegularFile),
        ) { "missing file among ${candidates.joinToString()}" }
    }

    private companion object {
        const val SENTINEL_CAMPAIGN = "v14-sentinel-campaign"
    }
}

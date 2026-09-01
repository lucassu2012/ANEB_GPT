package com.aneb.probe.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.aneb.probe.prototype.PrototypeCampaignConfig
import com.aneb.probe.prototype.PrototypeCampaignPersistenceFixture
import com.aneb.probe.prototype.PrototypeQuickCampaignRunner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PrototypeCampaignRoomRepositoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val databaseNames = mutableListOf<String>()

    @After
    fun deleteDatabases() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun completeQuickRoundTripsFormalAuthorityAndOrderedChildrenAcrossReopen(): Unit = runBlocking {
        val config = roomConfig(
            PrototypeCampaignPersistenceFixture.COMPLETE_CAMPAIGN_ID,
        )
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        assertFormalCapabilityAuthority(config)
        assertCompleteRunnerAuthority(result)
        assertEquals(ROOM_RUN_URL, config.nodeTicket.runUrl)

        val databaseName = uniqueDatabaseName("complete")
        var database = openFreshDatabase(databaseName)
        var repository = PrototypeCampaignRoomRepository(database)
        repository.save(config, result)
        assertTableCounts(database, campaigns = 1L, runs = 3L, events = 366L)
        val firstRead = requireNotNull(repository.load(config.campaignId))
        assertStoredAuthority(config, result, firstRead)
        database.close()

        database = openFreshDatabase(databaseName)
        repository = PrototypeCampaignRoomRepository(database)
        val reopened = requireNotNull(repository.load(config.campaignId))
        assertEquals(firstRead, reopened)
        assertStoredAuthority(config, result, reopened)
        assertTableCounts(database, campaigns = 1L, runs = 3L, events = 366L)

        val duplicateFailure = runCatching { repository.save(config, result) }.exceptionOrNull()
        assertNotNull("duplicate campaign coordinates must abort", duplicateFailure)
        assertEquals(reopened, repository.load(config.campaignId))
        assertTableCounts(database, campaigns = 1L, runs = 3L, events = 366L)
        database.close()
    }

    @Test
    fun completeAcceptanceRoundTripsNineRunsAndThreeConditionSummariesAcrossReopen(): Unit =
        runBlocking {
            val config = roomConfig("campaign-room-v13-acceptance")
            val result = PrototypeCampaignPersistenceFixture.completeAcceptanceCampaign(config)
            val expectedConditions = List(3) {
                listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1")
            }.flatten()
            assertEquals("acceptance", result.summary.campaignMode)
            assertEquals(9, result.summary.plannedRuns)
            assertEquals((1..9).toList(), result.runs.map { it.runIndex })
            assertEquals(expectedConditions, result.runs.map { it.conditionId })
            assertEquals(listOf(122, 122, 122, 122, 122, 122, 122, 122, 122), result.runs.map {
                it.evidenceEvents.size
            })
            assertTrue(result.runs.all { run ->
                run.evidenceEvents.all { event -> event.string("campaign_mode") == "acceptance" }
            })
            result.summary.conditionSummaries.forEach { summary ->
                assertEquals(3, summary.plannedRuns)
                assertEquals(3, summary.successfulRuns)
                assertEquals(PrototypeQuickCampaignRunner.Confidence.HIGH, summary.confidence)
            }

            val databaseName = uniqueDatabaseName("acceptance")
            var database = openFreshDatabase(databaseName)
            var repository = PrototypeCampaignRoomRepository(database)
            repository.save(config, result)
            assertTableCounts(database, campaigns = 1L, runs = 9L, events = 1_098L)
            val firstRead = requireNotNull(repository.load(config.campaignId))
            assertStoredAuthority(config, result, firstRead)
            database.close()

            database = openFreshDatabase(databaseName)
            repository = PrototypeCampaignRoomRepository(database)
            val reopened = requireNotNull(repository.load(config.campaignId))
            assertEquals(firstRead, reopened)
            assertStoredAuthority(config, result, reopened)
            assertTableCounts(database, campaigns = 1L, runs = 9L, events = 1_098L)
            database.close()
        }

    @Test
    fun partialQuickRoundTripsZeroNullAndOrderedNullReasonsWithoutRecomputation(): Unit =
        runBlocking {
            val config = roomConfig(
                PrototypeCampaignPersistenceFixture.PARTIAL_CAMPAIGN_ID,
            )
            val result = PrototypeCampaignPersistenceFixture.partialQuickCampaign(config)
            assertEquals(
                listOf("COMPLETE", "INTERRUPTED", "NOT_STARTED"),
                result.runs.map { it.status.name },
            )
            assertEquals(listOf(122, 3, 0), result.runs.map { it.evidenceEvents.size })
            assertEquals(listOf(true, null, null), result.runs.map { it.terminalReceiptValid })
            assertEquals(0, requireNotNull(result.runs[0].metrics).stallCount)
            assertNull(requireNotNull(result.runs[1].metrics).stallCount)
            assertNull(result.runs[2].metrics)
            assertEquals(0, result.runs[2].eventsReceived)
            assertTrue(result.runs[2].evidenceEvents.isEmpty())
            assertEquals(
                listOf("campaign_incomplete"),
                result.summary.conditionSummaries[0].allNullReasons,
            )
            val missingReasons = listOf(
                "campaign_incomplete",
                "no_successful_condition_run",
                "mandatory_metric_missing",
            )
            assertEquals(missingReasons, result.summary.conditionSummaries[1].allNullReasons)
            assertEquals(missingReasons, result.summary.conditionSummaries[2].allNullReasons)

            val databaseName = uniqueDatabaseName("partial")
            var database = openFreshDatabase(databaseName)
            var repository = PrototypeCampaignRoomRepository(database)
            repository.save(config, result)
            assertTableCounts(database, campaigns = 1L, runs = 3L, events = 125L)
            val firstRead = requireNotNull(repository.load(config.campaignId))
            assertStoredAuthority(config, result, firstRead)
            database.close()

            database = openFreshDatabase(databaseName)
            repository = PrototypeCampaignRoomRepository(database)
            val reopened = requireNotNull(repository.load(config.campaignId))
            assertEquals(firstRead, reopened)
            assertStoredAuthority(config, result, reopened)
            assertEquals(missingReasons, reopened.summary.conditionSummaries[2].allNullReasons)
            assertEquals(0, reopened.runs[2].eventsReceived)
            assertTrue(reopened.runs[2].evidenceEvents.isEmpty())
            assertTableCounts(database, campaigns = 1L, runs = 3L, events = 125L)
            database.close()
        }

    @Test
    fun invalidSequenceRoundTripsCanonicalPrefixAndNullMetricsAcrossReopen(): Unit = runBlocking {
        val config = roomConfig(PrototypeCampaignPersistenceFixture.INVALID_SEQUENCE_CAMPAIGN_ID)
        val result = PrototypeCampaignPersistenceFixture.invalidSequenceQuickCampaign(config)
        assertEquals(
            listOf("INVALID_SEQUENCE", "NOT_STARTED", "NOT_STARTED"),
            result.runs.map { it.status.name },
        )
        assertEquals(listOf(1, 0, 0), result.runs.map { it.eventsReceived })
        assertEquals(listOf(3, 0, 0), result.runs.map { it.evidenceEvents.size })
        assertEquals("invalid_sequence", result.runs.first().failureReason)
        assertNull(result.runs.first().metrics)

        val databaseName = uniqueDatabaseName("invalid-sequence")
        var database = openFreshDatabase(databaseName)
        var repository = PrototypeCampaignRoomRepository(database)
        repository.save(config, result)
        assertTableCounts(database, campaigns = 1L, runs = 3L, events = 3L)
        val firstRead = requireNotNull(repository.load(config.campaignId))
        assertStoredAuthority(config, result, firstRead)
        database.close()

        database = openFreshDatabase(databaseName)
        repository = PrototypeCampaignRoomRepository(database)
        val reopened = requireNotNull(repository.load(config.campaignId))
        assertEquals(firstRead, reopened)
        assertStoredAuthority(config, result, reopened)
        assertEquals("INVALID_SEQUENCE", reopened.runs.first().status.name)
        assertNull(reopened.runs.first().metrics)
        assertTableCounts(database, campaigns = 1L, runs = 3L, events = 3L)
        database.close()
    }

    @Test
    fun cancelledQuickRoundTripsCanonicalPrefixAndNotStartedSuffixAcrossReopen(): Unit =
        runBlocking {
            val config = roomConfig(PrototypeCampaignPersistenceFixture.CANCELLED_CAMPAIGN_ID)
            val result = PrototypeCampaignPersistenceFixture.cancelledQuickCampaign(config)
            assertEquals(
                listOf("CANCELLED", "NOT_STARTED", "NOT_STARTED"),
                result.runs.map { it.status.name },
            )
            assertEquals(listOf(1, 0, 0), result.runs.map { it.eventsReceived })
            assertEquals(listOf(3, 0, 0), result.runs.map { it.evidenceEvents.size })
            assertEquals("cancelled", result.runs.first().failureReason)
            assertNull(result.runs.first().terminalReceiptValid)
            assertEquals("CANCELLED", result.summary.status.name)

            val databaseName = uniqueDatabaseName("cancelled")
            var database = openFreshDatabase(databaseName)
            var repository = PrototypeCampaignRoomRepository(database)
            repository.save(config, result)
            assertTableCounts(database, campaigns = 1L, runs = 3L, events = 3L)
            val firstRead = requireNotNull(repository.load(config.campaignId))
            assertStoredAuthority(config, result, firstRead)
            database.close()

            database = openFreshDatabase(databaseName)
            repository = PrototypeCampaignRoomRepository(database)
            val reopened = requireNotNull(repository.load(config.campaignId))
            assertEquals(firstRead, reopened)
            assertStoredAuthority(config, result, reopened)
            assertEquals("CANCELLED", reopened.summary.status.name)
            assertEquals("CANCELLED", reopened.runs.first().status.name)
            assertTableCounts(database, campaigns = 1L, runs = 3L, events = 3L)
            database.close()
        }

    @Test
    fun cancelledQuickAfterAllContentButBeforeDoneRoundTripsAcrossReopen(): Unit = runBlocking {
        val config = roomConfig("campaign-room-v13-cancelled-after-content")
        val result = PrototypeCampaignPersistenceFixture.cancelledQuickCampaign(
            config = config,
            contentCount = 120,
        )
        assertEquals(
            listOf("CANCELLED", "NOT_STARTED", "NOT_STARTED"),
            result.runs.map { run -> run.status.name },
        )
        assertEquals(120, result.runs.first().eventsReceived)
        assertEquals(122, result.runs.first().evidenceEvents.size)

        val databaseName = uniqueDatabaseName("cancelled-after-content")
        var database = openFreshDatabase(databaseName)
        PrototypeCampaignRoomRepository(database).save(config, result)
        assertTableCounts(database, campaigns = 1L, runs = 3L, events = 122L)
        database.close()

        database = openFreshDatabase(databaseName)
        val reopened = requireNotNull(
            PrototypeCampaignRoomRepository(database).load(config.campaignId),
        )
        assertStoredAuthority(config, result, reopened)
        database.close()
    }

    @Test
    fun cancelledQuickBeforeFirstFrameRoundTripsNotStartedTopologyAcrossReopen(): Unit =
        runBlocking {
            val config = roomConfig("campaign-room-v13-cancelled-before-first-frame")
            val result = PrototypeCampaignPersistenceFixture
                .cancelledBeforeFirstFrameQuickCampaign(config)
            assertEquals(
                listOf("NOT_STARTED", "NOT_STARTED", "NOT_STARTED"),
                result.runs.map { run -> run.status.name },
            )
            assertEquals("PARTIAL", result.summary.status.name)

            val databaseName = uniqueDatabaseName("cancelled-before-first-frame")
            var database = openFreshDatabase(databaseName)
            PrototypeCampaignRoomRepository(database).save(config, result)
            assertTableCounts(database, campaigns = 1L, runs = 3L, events = 0L)
            database.close()

            database = openFreshDatabase(databaseName)
            val reopened = requireNotNull(
                PrototypeCampaignRoomRepository(database).load(config.campaignId),
            )
            assertStoredAuthority(config, result, reopened)
            database.close()
        }

    @Test
    fun cancelledQuickDuringCooldownRoundTripsCompletedPrefixAcrossReopen(): Unit = runBlocking {
        val config = roomConfig("campaign-room-v13-cancelled-during-cooldown")
        val result = PrototypeCampaignPersistenceFixture.cancelledDuringCooldownQuickCampaign(config)
        assertEquals(
            listOf("COMPLETE", "NOT_STARTED", "NOT_STARTED"),
            result.runs.map { run -> run.status.name },
        )
        assertEquals("PARTIAL", result.summary.status.name)

        val databaseName = uniqueDatabaseName("cancelled-during-cooldown")
        var database = openFreshDatabase(databaseName)
        PrototypeCampaignRoomRepository(database).save(config, result)
        assertTableCounts(database, campaigns = 1L, runs = 3L, events = 122L)
        database.close()

        database = openFreshDatabase(databaseName)
        val reopened = requireNotNull(
            PrototypeCampaignRoomRepository(database).load(config.campaignId),
        )
        assertStoredAuthority(config, result, reopened)
        database.close()
    }

    @Test
    fun campaignTerminalStatusMustBeDerivedFromRunTopologyBeforeAnyRow(): Unit = runBlocking {
        val cancelledConfig = roomConfig("campaign-room-v13-cancelled-status-forged")
        val cancelled = PrototypeCampaignPersistenceFixture.cancelledQuickCampaign(cancelledConfig)
        val cancelledAsPartial = cancelled.copy(
            summary = cancelled.summary.copy(
                status = PrototypeQuickCampaignRunner.CampaignStatus.PARTIAL,
            ),
        )
        val partialConfig = roomConfig("campaign-room-v13-partial-status-forged")
        val partial = PrototypeCampaignPersistenceFixture.partialQuickCampaign(partialConfig)
        val partialAsCancelled = partial.copy(
            summary = partial.summary.copy(
                status = PrototypeQuickCampaignRunner.CampaignStatus.CANCELLED,
            ),
        )
        val beforeFirstConfig = roomConfig("campaign-room-v13-before-first-status-forged")
        val beforeFirst = PrototypeCampaignPersistenceFixture
            .cancelledBeforeFirstFrameQuickCampaign(beforeFirstConfig)
            .let { result ->
                result.copy(
                    summary = result.summary.copy(
                        status = PrototypeQuickCampaignRunner.CampaignStatus.PARTIAL,
                    ),
                )
            }
        val beforeFirstAsCancelled = beforeFirst.copy(
            summary = beforeFirst.summary.copy(
                status = PrototypeQuickCampaignRunner.CampaignStatus.CANCELLED,
            ),
        )
        val cooldownConfig = roomConfig("campaign-room-v13-cooldown-status-forged")
        val cooldown = PrototypeCampaignPersistenceFixture
            .cancelledDuringCooldownQuickCampaign(cooldownConfig)
            .let { result ->
                result.copy(
                    summary = result.summary.copy(
                        status = PrototypeQuickCampaignRunner.CampaignStatus.PARTIAL,
                    ),
                )
            }
        val cooldownAsCancelled = cooldown.copy(
            summary = cooldown.summary.copy(
                status = PrototypeQuickCampaignRunner.CampaignStatus.CANCELLED,
            ),
        )

        listOf(
            cancelledConfig to cancelledAsPartial,
            partialConfig to partialAsCancelled,
            beforeFirstConfig to beforeFirstAsCancelled,
            cooldownConfig to cooldownAsCancelled,
        ).forEachIndexed { index, (config, forged) ->
            val database = openFreshDatabase(uniqueDatabaseName("terminal-status-$index"))
            val failure = runCatching {
                PrototypeCampaignRoomRepository(database).save(config, forged)
            }.exceptionOrNull()
            assertEquals(INVALID_GRAPH, failure?.message)
            assertTableCounts(database, campaigns = 0L, runs = 0L, events = 0L)
            database.close()
        }
    }

    @Test
    fun saveRejectsConfigResultCampaignMismatchBeforeAnyRow(): Unit = runBlocking {
        val config = roomConfig("campaign-room-v13-config-authority")
        val resultConfig = roomConfig("campaign-room-v13-result-authority")
        val mismatchedResult = PrototypeCampaignPersistenceFixture.completeQuickCampaign(resultConfig)
        assertTrue(config.campaignId != mismatchedResult.summary.campaignId)

        val databaseName = uniqueDatabaseName("config-result-mismatch")
        var database = openFreshDatabase(databaseName)
        val repository = PrototypeCampaignRoomRepository(database)
        assertNotNull(
            "config and result campaign authorities must be one chain",
            runCatching { repository.save(config, mismatchedResult) }.exceptionOrNull(),
        )
        assertTableCounts(database, campaigns = 0L, runs = 0L, events = 0L)
        database.close()

        database = openFreshDatabase(databaseName)
        assertTableCounts(database, campaigns = 0L, runs = 0L, events = 0L)
        database.close()
    }

    @Test
    fun lateFinalRunCoordinateFailureRollsBackAllThreeTablesAcrossReopen(): Unit = runBlocking {
        val config = roomConfig("campaign-room-v13-run-coordinate-rollback")
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)

        val databaseName = uniqueDatabaseName("late-run-coordinate")
        var database = openFreshDatabase(databaseName)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER force_duplicate_last_run_coordinate
            AFTER INSERT ON prototype_run
            WHEN NEW.runIndex = 3
            BEGIN
              INSERT INTO prototype_run (
                campaignId, runId, runIndex, conditionId, status, taskSuccess, scoreEligible,
                eventsExpected, eventsReceived, failureReason, terminalReceiptValid, metricsJson
              ) VALUES (
                NEW.campaignId, NEW.runId || '-coordinate-duplicate', NEW.runIndex,
                NEW.conditionId, NEW.status, NEW.taskSuccess, NEW.scoreEligible,
                NEW.eventsExpected, NEW.eventsReceived, NEW.failureReason,
                NEW.terminalReceiptValid, NEW.metricsJson
              );
            END
            """.trimIndent(),
        )
        val repository = PrototypeCampaignRoomRepository(database)
        val failure = runCatching { repository.save(config, result) }.exceptionOrNull()
        assertNotNull("last-run coordinate collision must abort", failure)
        assertTrue(
            "failure must reach the bounded late-run unique coordinate",
            generateSequence(failure) { it.cause }
                .mapNotNull(Throwable::message)
                .any { message ->
                    message.contains("UNIQUE constraint failed") &&
                        message.contains("prototype_run")
                },
        )
        assertTableCounts(database, campaigns = 0L, runs = 0L, events = 0L)
        database.close()

        database = openFreshDatabase(databaseName)
        assertTableCounts(database, campaigns = 0L, runs = 0L, events = 0L)
        database.close()
    }

    @Test
    fun lateFinalChildCoordinateFailureRollsBackAllThreeTablesAcrossReopen(): Unit = runBlocking {
        val config = roomConfig("campaign-room-v13-rollback")
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val lastRun = result.runs.last()
        assertEquals(3, lastRun.runIndex)
        assertEquals("terminal_event", lastRun.evidenceEvents.last().string("event_type"))

        val databaseName = uniqueDatabaseName("late-child")
        var database = openFreshDatabase(databaseName)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER force_duplicate_last_evidence_coordinate
            AFTER INSERT ON prototype_evidence_event
            WHEN NEW.runId = '${lastRun.runId}' AND NEW.eventOrdinal = 121
            BEGIN
              INSERT INTO prototype_evidence_event (campaignId, runId, eventOrdinal, eventJson)
              VALUES (NEW.campaignId, NEW.runId, NEW.eventOrdinal, NEW.eventJson);
            END
            """.trimIndent(),
        )
        val repository = PrototypeCampaignRoomRepository(database)
        val failure = runCatching { repository.save(config, result) }.exceptionOrNull()
        assertNotNull("last-run duplicate evidence coordinate must abort", failure)
        assertTrue(
            "failure must reach the bounded late-child trigger",
            generateSequence(failure) { it.cause }
                .mapNotNull(Throwable::message)
                .any { message ->
                    message.contains("UNIQUE constraint failed") &&
                        message.contains("prototype_evidence_event")
                },
        )
        assertTableCounts(database, campaigns = 0L, runs = 0L, events = 0L)
        database.close()

        database = openFreshDatabase(databaseName)
        assertTableCounts(database, campaigns = 0L, runs = 0L, events = 0L)
        database.close()
    }

    @Test
    fun completeForTestShapeIsRejectedBeforeAnyParentOrChildRow(): Unit = runBlocking {
        val config = roomConfig("campaign-room-v13-invalid")
        val canonical = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val source = canonical.runs.last()
        val invalidComplete = PrototypeQuickCampaignRunner.RunResult.completeForTest(
            runIndex = source.runIndex,
            runId = source.runId,
            conditionId = source.conditionId,
            streamResult = source.streamResult,
        )
        assertTrue(invalidComplete.evidenceEvents.isEmpty())
        assertNull(invalidComplete.metrics)
        val invalid = canonical.copy(runs = canonical.runs.dropLast(1) + invalidComplete)

        val databaseName = uniqueDatabaseName("invalid-complete")
        var database = openFreshDatabase(databaseName)
        val repository = PrototypeCampaignRoomRepository(database)
        assertNotNull(
            "COMPLETE with empty evidence and null metrics must fail closed",
            runCatching { repository.save(config, invalid) }.exceptionOrNull(),
        )
        assertTableCounts(database, campaigns = 0L, runs = 0L, events = 0L)
        database.close()

        database = openFreshDatabase(databaseName)
        assertTableCounts(database, campaigns = 0L, runs = 0L, events = 0L)
        database.close()
    }

    @Test
    fun parentWithEmptyChildTablesFailsClosedInsteadOfReturningARecomputedAggregate(): Unit =
        runBlocking {
            val config = roomConfig("campaign-room-v13-orphan")
            val result = PrototypeCampaignPersistenceFixture.partialQuickCampaign(config)
            val databaseName = uniqueDatabaseName("orphan")
            val database = openFreshDatabase(databaseName)
            val repository = PrototypeCampaignRoomRepository(database)
            repository.save(config, result)
            database.openHelper.writableDatabase.execSQL("DELETE FROM prototype_evidence_event")
            database.openHelper.writableDatabase.execSQL("DELETE FROM prototype_run")
            assertTableCounts(database, campaigns = 1L, runs = 0L, events = 0L)
            assertNotNull(
                "stored parent JSON cannot replace missing normalized child rows",
                runCatching { repository.load(config.campaignId) }.exceptionOrNull(),
            )
            database.close()
        }

    @Test
    fun swappedNormalizedRunCoordinatesFailClosedAcrossReopen(): Unit = runBlocking {
        val config = roomConfig("campaign-room-v13-run-tamper")
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val databaseName = uniqueDatabaseName("run-tamper")
        var database = openFreshDatabase(databaseName)
        val repository = PrototypeCampaignRoomRepository(database)
        repository.save(config, result)
        val writable = database.openHelper.writableDatabase
        writable.execSQL(
            "UPDATE prototype_run SET runIndex = 99 WHERE campaignId = ? AND runIndex = 2",
            arrayOf<Any?>(config.campaignId),
        )
        writable.execSQL(
            "UPDATE prototype_run SET runIndex = 2 WHERE campaignId = ? AND runIndex = 3",
            arrayOf<Any?>(config.campaignId),
        )
        writable.execSQL(
            "UPDATE prototype_run SET runIndex = 3 WHERE campaignId = ? AND runIndex = 99",
            arrayOf<Any?>(config.campaignId),
        )
        assertEquals(
            "unstable_v0.1",
            scalarString(
                database,
                "SELECT conditionId FROM prototype_run WHERE campaignId = ? AND runIndex = 2",
                config.campaignId,
            ),
        )
        assertTableCounts(database, campaigns = 1L, runs = 3L, events = 366L)
        database.close()

        database = openFreshDatabase(databaseName)
        val reopenedRepository = PrototypeCampaignRoomRepository(database)
        assertNotNull(
            "normalized run coordinates must agree with the preserved run payload",
            runCatching { reopenedRepository.load(config.campaignId) }.exceptionOrNull(),
        )
        assertTableCounts(database, campaigns = 1L, runs = 3L, events = 366L)
        database.close()
    }

    @Test
    fun swappedNormalizedEvidenceOrdinalsFailClosedAcrossReopen(): Unit = runBlocking {
        val config = roomConfig("campaign-room-v13-event-tamper")
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val runId = result.runs[1].runId
        val databaseName = uniqueDatabaseName("event-tamper")
        var database = openFreshDatabase(databaseName)
        val repository = PrototypeCampaignRoomRepository(database)
        repository.save(config, result)
        val writable = database.openHelper.writableDatabase
        writable.execSQL(
            "UPDATE prototype_evidence_event SET eventOrdinal = 999 " +
                "WHERE campaignId = ? AND runId = ? AND eventOrdinal = 60",
            arrayOf<Any?>(config.campaignId, runId),
        )
        writable.execSQL(
            "UPDATE prototype_evidence_event SET eventOrdinal = 60 " +
                "WHERE campaignId = ? AND runId = ? AND eventOrdinal = 61",
            arrayOf<Any?>(config.campaignId, runId),
        )
        writable.execSQL(
            "UPDATE prototype_evidence_event SET eventOrdinal = 61 " +
                "WHERE campaignId = ? AND runId = ? AND eventOrdinal = 999",
            arrayOf<Any?>(config.campaignId, runId),
        )
        val eventAtSixty = Json.parseToJsonElement(
            scalarString(
                database,
                "SELECT eventJson FROM prototype_evidence_event " +
                    "WHERE campaignId = ? AND runId = ? AND eventOrdinal = 60",
                config.campaignId,
                runId,
            ),
        ).jsonObject
        assertEquals(61, eventAtSixty.getValue("details").jsonObject.getValue("seq").jsonPrimitive.int)
        assertTableCounts(database, campaigns = 1L, runs = 3L, events = 366L)
        database.close()

        database = openFreshDatabase(databaseName)
        val reopenedRepository = PrototypeCampaignRoomRepository(database)
        assertNotNull(
            "normalized evidence ordinal must agree with the preserved canonical event JSON",
            runCatching { reopenedRepository.load(config.campaignId) }.exceptionOrNull(),
        )
        assertTableCounts(database, campaigns = 1L, runs = 3L, events = 366L)
        database.close()
    }

    @Test
    fun uniformlyShiftedNormalizedEvidenceOrdinalsFailClosedAcrossReopen(): Unit = runBlocking {
        val config = roomConfig("campaign-room-v13-event-range-tamper")
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val runId = result.runs[1].runId
        val databaseName = uniqueDatabaseName("event-range-tamper")
        var database = openFreshDatabase(databaseName)
        PrototypeCampaignRoomRepository(database).save(config, result)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE prototype_evidence_event SET eventOrdinal = eventOrdinal + 1000 " +
                "WHERE campaignId = ? AND runId = ?",
            arrayOf<Any?>(config.campaignId, runId),
        )
        assertEquals(
            "1000:1121",
            scalarString(
                database,
                "SELECT MIN(eventOrdinal) || ':' || MAX(eventOrdinal) " +
                    "FROM prototype_evidence_event WHERE campaignId = ? AND runId = ?",
                config.campaignId,
                runId,
            ),
        )
        assertTableCounts(database, campaigns = 1L, runs = 3L, events = 366L)
        database.close()

        database = openFreshDatabase(databaseName)
        val failure = runCatching {
            PrototypeCampaignRoomRepository(database).load(config.campaignId)
        }.exceptionOrNull()
        assertEquals(INVALID_GRAPH, failure?.message)
        assertTableCounts(database, campaigns = 1L, runs = 3L, events = 366L)
        database.close()
    }

    @Test
    fun forgedConditionSummaryRpiIsRejectedBeforeAnyRow(): Unit = runBlocking {
        val config = roomConfig("campaign-room-v13-summary-save-tamper")
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val slow = result.summary.conditionSummaries.single { summary ->
            summary.conditionId == "slow_v0.1"
        }
        assertEquals(48, slow.rpi)
        val forged = result.copy(
            summary = result.summary.copy(
                conditionSummaries = result.summary.conditionSummaries.map { summary ->
                    if (summary.conditionId == "slow_v0.1") summary.copy(rpi = 100) else summary
                },
            ),
        )
        val database = openFreshDatabase(uniqueDatabaseName("summary-save-tamper"))
        val failure = runCatching {
            PrototypeCampaignRoomRepository(database).save(config, forged)
        }.exceptionOrNull()
        assertEquals(INVALID_GRAPH, failure?.message)
        assertTableCounts(database, campaigns = 0L, runs = 0L, events = 0L)
        database.close()
    }

    @Test
    fun tamperedPersistedConditionSummaryRpiFailsClosedAcrossReopen(): Unit = runBlocking {
        val config = roomConfig("campaign-room-v13-summary-load-tamper")
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        assertEquals(
            48,
            result.summary.conditionSummaries.single { summary ->
                summary.conditionId == "slow_v0.1"
            }.rpi,
        )
        val databaseName = uniqueDatabaseName("summary-load-tamper")
        var database = openFreshDatabase(databaseName)
        PrototypeCampaignRoomRepository(database).save(config, result)
        val storedSummary = Json.parseToJsonElement(
            scalarString(
                database,
                "SELECT summaryJson FROM prototype_campaign WHERE campaignId = ?",
                config.campaignId,
            ),
        ).jsonObject
        val conditions = storedSummary.getValue("conditionSummaries").jsonArray
        val tamperedConditions = conditions.map { element ->
            val summary = element.jsonObject
            if (summary.getValue("conditionId").jsonPrimitive.content == "slow_v0.1") {
                JsonObject(summary + ("rpi" to JsonPrimitive(100)))
            } else {
                summary
            }
        }
        val tamperedSummary = JsonObject(
            storedSummary + ("conditionSummaries" to JsonArray(tamperedConditions)),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE prototype_campaign SET summaryJson = ? WHERE campaignId = ?",
            arrayOf<Any?>(tamperedSummary.toString(), config.campaignId),
        )
        assertTableCounts(database, campaigns = 1L, runs = 3L, events = 366L)
        database.close()

        database = openFreshDatabase(databaseName)
        val failure = runCatching {
            PrototypeCampaignRoomRepository(database).load(config.campaignId)
        }.exceptionOrNull()
        assertEquals(INVALID_GRAPH, failure?.message)
        assertTableCounts(database, campaigns = 1L, runs = 3L, events = 366L)
        database.close()
    }

    @Test
    fun loadUsesExplicitRunAndEvidenceOrderingInsteadOfInsertionOrder(): Unit = runBlocking {
        val config = roomConfig("campaign-room-v13-read-order")
        val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
        val databaseName = uniqueDatabaseName("read-order")
        var database = openFreshDatabase(databaseName)
        PrototypeCampaignRoomRepository(database).save(config, result)
        database.close()

        val queries = mutableListOf<String>()
        database = openFreshDatabase(databaseName, queries)
        database.openHelper.writableDatabase.execSQL("PRAGMA reverse_unordered_selects = ON")
        queries.clear()
        val stored = requireNotNull(
            PrototypeCampaignRoomRepository(database).load(config.campaignId),
        )
        assertStoredAuthority(config, result, stored)
        val normalizedQueries = queries.map(::normalizeSql)
        assertTrue(
            "prototype runs must be selected with explicit runIndex ordering",
            normalizedQueries.any { sql ->
                "from prototype_run" in sql && "order by runindex" in sql
            },
        )
        assertTrue(
            "prototype evidence must be selected with explicit eventOrdinal ordering",
            normalizedQueries.any { sql ->
                "from prototype_evidence_event" in sql && "order by eventordinal" in sql
            },
        )
        database.close()
    }

    @Test
    fun loadExportSnapshotPreservesValidatedCampaignAndOrderedLexicalEvidence(): Unit =
        runBlocking {
            val config = roomConfig("campaign-room-v13-export-lexical")
            val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
            val database = openFreshDatabase(uniqueDatabaseName("export-lexical"))
            val repository = PrototypeCampaignRoomRepository(database)
            repository.save(config, result)

            val lexicalCapability = " \t${config.nodeTicket.rawCapabilityBody.trim()}\t \n"
            assertEquals(
                Json.parseToJsonElement(config.nodeTicket.rawCapabilityBody),
                Json.parseToJsonElement(lexicalCapability),
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE prototype_campaign SET rawCapabilityBody = ? WHERE campaignId = ?",
                arrayOf<Any?>(lexicalCapability, config.campaignId),
            )

            val targetRun = result.runs[1]
            val targetOrdinal = 60
            val originalEvent = scalarString(
                database,
                "SELECT eventJson FROM prototype_evidence_event " +
                    "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                config.campaignId,
                targetRun.runId,
                targetOrdinal.toString(),
            )
            val originalObject = Json.parseToJsonElement(originalEvent).jsonObject
            val originalServerMonotonic = originalObject
                .getValue("client_monotonic_ns")
                .jsonPrimitive
                .content
            check(originalServerMonotonic.toLongOrNull() != null)
            val lexicalEvent = originalObject.entries
                .reversed()
                .joinToString(prefix = "{ ", separator = " , ", postfix = " }") { (key, value) ->
                    val encodedValue = if (key == "client_monotonic_ns") {
                        "\t$originalServerMonotonic  "
                    } else {
                        value.toString()
                    }
                    "${JsonPrimitive(key)} : $encodedValue"
                }
            assertFalse(lexicalEvent.contains('\r'))
            assertFalse(lexicalEvent.contains('\n'))
            val lexicalObject = Json.parseToJsonElement(lexicalEvent).jsonObject
            assertEquals(18, lexicalObject.size)
            assertEquals(originalObject, lexicalObject)
            assertTrue(
                lexicalEvent.contains(
                    "\"client_monotonic_ns\" : \t$originalServerMonotonic  ",
                ),
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE prototype_evidence_event SET eventJson = ? " +
                    "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                arrayOf<Any?>(
                    lexicalEvent,
                    config.campaignId,
                    targetRun.runId,
                    targetOrdinal,
                ),
            )

            val snapshot = requireNotNull(repository.loadExportSnapshot(config.campaignId))
            assertEquals(requireNotNull(repository.load(config.campaignId)), snapshot.campaign)
            assertEquals(lexicalCapability, snapshot.rawCapabilityBody)
            assertEquals(
                result.runs.flatMap { run ->
                    run.evidenceEvents.indices.map { eventOrdinal -> run.runIndex to eventOrdinal }
                },
                snapshot.lexicalEvidence.map { evidence ->
                    evidence.runIndex to evidence.eventOrdinal
                },
            )
            assertEquals(
                lexicalEvent,
                snapshot.lexicalEvidence.single { evidence ->
                    evidence.runIndex == targetRun.runIndex &&
                        evidence.eventOrdinal == targetOrdinal
                }.eventJson,
            )
            database.close()
        }

    @Test
    fun loadExportSnapshotRejectsCapabilityRawBodyOutsideThePersistedIdentityContract(): Unit =
        runBlocking {
            val config = roomConfig("campaign-room-v13-export-capability-privacy")
            val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
            val database = openFreshDatabase(uniqueDatabaseName("export-capability-privacy"))
            val repository = PrototypeCampaignRoomRepository(database)
            repository.save(config, result)

            val rawCapability = config.nodeTicket.rawCapabilityBody
            val closingBrace = rawCapability.lastIndexOf('}')
            check(closingBrace >= 0)
            val capabilityWithCredential = rawCapability.substring(0, closingBrace) +
                ",\n  \"credential\":\"do-not-export\"" +
                rawCapability.substring(closingBrace)
            val originalObject = Json.parseToJsonElement(rawCapability).jsonObject
            val mutatedObject = Json.parseToJsonElement(capabilityWithCredential).jsonObject
            assertEquals(setOf("credential"), mutatedObject.keys - originalObject.keys)
            assertEquals(originalObject, JsonObject(mutatedObject - "credential"))
            assertEquals("do-not-export", mutatedObject.getValue("credential").jsonPrimitive.content)
            database.openHelper.writableDatabase.execSQL(
                "UPDATE prototype_campaign SET rawCapabilityBody = ? WHERE campaignId = ?",
                arrayOf<Any?>(capabilityWithCredential, config.campaignId),
            )

            val failures = listOf(
                runCatching { requireNotNull(repository.load(config.campaignId)) }
                    .exceptionOrNull(),
                runCatching { requireNotNull(repository.loadExportSnapshot(config.campaignId)) }
                    .exceptionOrNull(),
            )
            assertEquals(listOf(INVALID_GRAPH, INVALID_GRAPH), failures.map { it?.message })
            database.close()
        }

    @Test
    fun persistedEvidenceRejectsAnUnknownOuterKeyThatReplacesARequiredExact18Key(): Unit =
        runBlocking {
            val config = roomConfig("campaign-room-v13-evidence-outer-keys")
            val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
            val database = openFreshDatabase(uniqueDatabaseName("evidence-outer-keys"))
            val repository = PrototypeCampaignRoomRepository(database)
            repository.save(config, result)

            val targetRun = result.runs.first()
            val targetOrdinal = 60
            val originalEvent = Json.parseToJsonElement(
                scalarString(
                    database,
                    "SELECT eventJson FROM prototype_evidence_event " +
                        "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                    config.campaignId,
                    targetRun.runId,
                    targetOrdinal.toString(),
                ),
            ).jsonObject
            check("clock_unit" in originalEvent)
            check("credential" !in originalEvent)
            val mutatedEvent = JsonObject(
                (originalEvent - "clock_unit") +
                    ("credential" to JsonPrimitive("do-not-export")),
            )
            assertEquals(18, originalEvent.size)
            assertEquals(18, mutatedEvent.size)
            assertEquals(setOf("clock_unit"), originalEvent.keys - mutatedEvent.keys)
            assertEquals(setOf("credential"), mutatedEvent.keys - originalEvent.keys)
            assertEquals(
                originalEvent - "clock_unit",
                mutatedEvent - "credential",
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE prototype_evidence_event SET eventJson = ? " +
                    "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                arrayOf<Any?>(
                    mutatedEvent.toString(),
                    config.campaignId,
                    targetRun.runId,
                    targetOrdinal,
                ),
            )
            assertEquals(
                mutatedEvent.toString(),
                scalarString(
                    database,
                    "SELECT eventJson FROM prototype_evidence_event " +
                        "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                    config.campaignId,
                    targetRun.runId,
                    targetOrdinal.toString(),
                ),
            )

            val failures = listOf(
                runCatching { requireNotNull(repository.load(config.campaignId)) }
                    .exceptionOrNull(),
                runCatching { requireNotNull(repository.loadExportSnapshot(config.campaignId)) }
                    .exceptionOrNull(),
            )
            assertEquals(listOf(INVALID_GRAPH, INVALID_GRAPH), failures.map { it?.message })
            database.close()
        }

    @Test
    fun persistedEvidenceRejectsDuplicateRootDetailsThatHideLexicalCredential(): Unit =
        runBlocking {
            val config = roomConfig("campaign-room-v13-evidence-root-duplicate")
            val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
            val database = openFreshDatabase(uniqueDatabaseName("evidence-root-duplicate"))
            val repository = PrototypeCampaignRoomRepository(database)
            repository.save(config, result)

            val targetRun = result.runs.first()
            val targetOrdinal = 60
            val originalEvent = scalarString(
                database,
                "SELECT eventJson FROM prototype_evidence_event " +
                    "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                config.campaignId,
                targetRun.runId,
                targetOrdinal.toString(),
            )
            val detailsMember = "\"details\":"
            assertEquals(1, originalEvent.split(detailsMember).size - 1)
            val duplicateDetailsEvent = originalEvent.replaceFirst(
                detailsMember,
                "\"details\":{\"credential\":\"do-not-export\"},$detailsMember",
            )
            assertEquals(2, duplicateDetailsEvent.split(detailsMember).size - 1)
            assertTrue(duplicateDetailsEvent.contains("\"credential\":\"do-not-export\""))

            val originalObject = Json.parseToJsonElement(originalEvent).jsonObject
            val materializedDuplicate = Json.parseToJsonElement(duplicateDetailsEvent).jsonObject
            assertEquals(18, originalObject.size)
            assertEquals(18, materializedDuplicate.size)
            assertEquals(originalObject, materializedDuplicate)
            assertFalse(
                (materializedDuplicate.getValue("details") as JsonObject)
                    .containsKey("credential"),
            )

            database.openHelper.writableDatabase.execSQL(
                "UPDATE prototype_evidence_event SET eventJson = ? " +
                    "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                arrayOf<Any?>(
                    duplicateDetailsEvent,
                    config.campaignId,
                    targetRun.runId,
                    targetOrdinal,
                ),
            )
            val persistedEvent = scalarString(
                database,
                "SELECT eventJson FROM prototype_evidence_event " +
                    "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                config.campaignId,
                targetRun.runId,
                targetOrdinal.toString(),
            )
            assertEquals(duplicateDetailsEvent, persistedEvent)
            assertEquals(2, persistedEvent.split(detailsMember).size - 1)

            val failures = listOf(
                runCatching { requireNotNull(repository.load(config.campaignId)) }
                    .exceptionOrNull(),
                runCatching { requireNotNull(repository.loadExportSnapshot(config.campaignId)) }
                    .exceptionOrNull(),
            )
            assertEquals(listOf(INVALID_GRAPH, INVALID_GRAPH), failures.map { it?.message })
            database.close()
        }

    @Test
    fun persistedContentEvidenceRejectsDuplicatePayloadIdHiddenByLastWins(): Unit =
        runBlocking {
            val config = roomConfig("campaign-room-v13-content-details-duplicate")
            val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
            val database = openFreshDatabase(uniqueDatabaseName("content-details-duplicate"))
            val repository = PrototypeCampaignRoomRepository(database)
            repository.save(config, result)

            val targetRun = result.runs.first()
            val targetOrdinal = 60
            val originalEvent = scalarString(
                database,
                "SELECT eventJson FROM prototype_evidence_event " +
                    "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                config.campaignId,
                targetRun.runId,
                targetOrdinal.toString(),
            )
            val detailsMember = "\"details\":"
            val payloadMember = "\"payload_id\":"
            assertEquals(1, originalEvent.split(detailsMember).size - 1)
            assertEquals(1, originalEvent.split(payloadMember).size - 1)
            val duplicatePayloadEvent = originalEvent.replaceFirst(
                payloadMember,
                "\"payload_id\":\"credential-do-not-export\",$payloadMember",
            )
            assertFalse(duplicatePayloadEvent == originalEvent)
            assertEquals(1, duplicatePayloadEvent.split(detailsMember).size - 1)
            assertEquals(2, duplicatePayloadEvent.split(payloadMember).size - 1)
            assertTrue(
                duplicatePayloadEvent.contains(
                    "\"payload_id\":\"credential-do-not-export\"",
                ),
            )

            val originalObject = Json.parseToJsonElement(originalEvent).jsonObject
            val materializedDuplicate = Json.parseToJsonElement(duplicatePayloadEvent).jsonObject
            val originalDetails = originalObject.getValue("details") as JsonObject
            val materializedDetails = materializedDuplicate.getValue("details") as JsonObject
            assertEquals("content_event", originalObject.getValue("event_type").jsonPrimitive.content)
            assertEquals(18, originalObject.size)
            assertEquals(18, materializedDuplicate.size)
            assertEquals(originalObject, materializedDuplicate)
            assertEquals(setOf("seq", "planned_offset_ms", "payload_id"), materializedDetails.keys)
            assertEquals(originalDetails, materializedDetails)
            assertFalse(
                materializedDetails.values.contains(JsonPrimitive("credential-do-not-export")),
            )

            database.openHelper.writableDatabase.execSQL(
                "UPDATE prototype_evidence_event SET eventJson = ? " +
                    "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                arrayOf<Any?>(
                    duplicatePayloadEvent,
                    config.campaignId,
                    targetRun.runId,
                    targetOrdinal,
                ),
            )
            val persistedEvent = scalarString(
                database,
                "SELECT eventJson FROM prototype_evidence_event " +
                    "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                config.campaignId,
                targetRun.runId,
                targetOrdinal.toString(),
            )
            assertEquals(duplicatePayloadEvent, persistedEvent)
            assertEquals(1, persistedEvent.split(detailsMember).size - 1)
            assertEquals(2, persistedEvent.split(payloadMember).size - 1)

            val failures = listOf(
                runCatching { requireNotNull(repository.load(config.campaignId)) }
                    .exceptionOrNull(),
                runCatching { requireNotNull(repository.loadExportSnapshot(config.campaignId)) }
                    .exceptionOrNull(),
            )
            assertEquals(listOf(INVALID_GRAPH, INVALID_GRAPH), failures.map { it?.message })
            database.close()
        }

    @Test
    fun persistedContentEvidenceRejectsUnknownDetailsKey(): Unit =
        runBlocking {
            val config = roomConfig("campaign-room-v13-content-details-unknown")
            val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
            val database = openFreshDatabase(uniqueDatabaseName("content-details-unknown"))
            val repository = PrototypeCampaignRoomRepository(database)
            repository.save(config, result)

            val targetRun = result.runs.first()
            val targetOrdinal = 60
            val originalEvent = Json.parseToJsonElement(
                scalarString(
                    database,
                    "SELECT eventJson FROM prototype_evidence_event " +
                        "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                    config.campaignId,
                    targetRun.runId,
                    targetOrdinal.toString(),
                ),
            ).jsonObject
            val originalDetails = originalEvent.getValue("details") as JsonObject
            assertEquals("content_event", originalEvent.getValue("event_type").jsonPrimitive.content)
            assertEquals(18, originalEvent.size)
            assertEquals(setOf("seq", "planned_offset_ms", "payload_id"), originalDetails.keys)
            val mutatedDetails = JsonObject(
                originalDetails + ("credential" to JsonPrimitive("do-not-export")),
            )
            val mutatedEvent = JsonObject(originalEvent + ("details" to mutatedDetails))
            assertEquals(18, mutatedEvent.size)
            assertEquals(4, mutatedDetails.size)
            assertEquals(setOf("credential"), mutatedDetails.keys - originalDetails.keys)
            assertEquals(originalDetails, JsonObject(mutatedDetails - "credential"))
            assertEquals("do-not-export", mutatedDetails.getValue("credential").jsonPrimitive.content)

            database.openHelper.writableDatabase.execSQL(
                "UPDATE prototype_evidence_event SET eventJson = ? " +
                    "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                arrayOf<Any?>(
                    mutatedEvent.toString(),
                    config.campaignId,
                    targetRun.runId,
                    targetOrdinal,
                ),
            )
            val persistedEvent = scalarString(
                database,
                "SELECT eventJson FROM prototype_evidence_event " +
                    "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                config.campaignId,
                targetRun.runId,
                targetOrdinal.toString(),
            )
            assertEquals(mutatedEvent.toString(), persistedEvent)
            val persistedObject = Json.parseToJsonElement(persistedEvent).jsonObject
            val persistedDetails = persistedObject.getValue("details") as JsonObject
            assertEquals(18, persistedObject.size)
            assertEquals(4, persistedDetails.size)
            assertEquals("do-not-export", persistedDetails.getValue("credential").jsonPrimitive.content)

            val failures = listOf(
                runCatching { requireNotNull(repository.load(config.campaignId)) }
                    .exceptionOrNull(),
                runCatching { requireNotNull(repository.loadExportSnapshot(config.campaignId)) }
                    .exceptionOrNull(),
            )
            assertEquals(listOf(INVALID_GRAPH, INVALID_GRAPH), failures.map { it?.message })
            database.close()
        }

    @Test
    fun persistedContentEvidenceRejectsPayloadIdOutsideDeterministicSequenceIdentity(): Unit =
        runBlocking {
            val config = roomConfig("campaign-room-v13-content-payload-identity")
            val result = PrototypeCampaignPersistenceFixture.completeQuickCampaign(config)
            val database = openFreshDatabase(uniqueDatabaseName("content-payload-identity"))
            val repository = PrototypeCampaignRoomRepository(database)
            repository.save(config, result)

            val targetRun = result.runs.first()
            val targetOrdinal = 60
            val originalEvent = Json.parseToJsonElement(
                scalarString(
                    database,
                    "SELECT eventJson FROM prototype_evidence_event " +
                        "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                    config.campaignId,
                    targetRun.runId,
                    targetOrdinal.toString(),
                ),
            ).jsonObject
            val originalDetails = originalEvent.getValue("details") as JsonObject
            val expectedPayloadId = "ref-${targetOrdinal.toString().padStart(4, '0')}"
            assertEquals("content_event", originalEvent.getValue("event_type").jsonPrimitive.content)
            assertEquals(18, originalEvent.size)
            assertEquals(setOf("seq", "planned_offset_ms", "payload_id"), originalDetails.keys)
            assertEquals(targetOrdinal, originalDetails.getValue("seq").jsonPrimitive.int)
            assertEquals(expectedPayloadId, originalDetails.getValue("payload_id").jsonPrimitive.content)
            val mutatedDetails = JsonObject(
                originalDetails + ("payload_id" to JsonPrimitive("third-party-secret")),
            )
            val mutatedEvent = JsonObject(originalEvent + ("details" to mutatedDetails))
            assertEquals(originalEvent.keys, mutatedEvent.keys)
            assertEquals(originalDetails.keys, mutatedDetails.keys)
            assertEquals(
                originalDetails - "payload_id",
                mutatedDetails - "payload_id",
            )
            assertEquals(
                "third-party-secret",
                mutatedDetails.getValue("payload_id").jsonPrimitive.content,
            )

            database.openHelper.writableDatabase.execSQL(
                "UPDATE prototype_evidence_event SET eventJson = ? " +
                    "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                arrayOf<Any?>(
                    mutatedEvent.toString(),
                    config.campaignId,
                    targetRun.runId,
                    targetOrdinal,
                ),
            )
            val persistedEvent = scalarString(
                database,
                "SELECT eventJson FROM prototype_evidence_event " +
                    "WHERE campaignId = ? AND runId = ? AND eventOrdinal = ?",
                config.campaignId,
                targetRun.runId,
                targetOrdinal.toString(),
            )
            assertEquals(mutatedEvent.toString(), persistedEvent)
            val persistedDetails = Json.parseToJsonElement(persistedEvent)
                .jsonObject
                .getValue("details") as JsonObject
            assertEquals(setOf("seq", "planned_offset_ms", "payload_id"), persistedDetails.keys)
            assertEquals(targetOrdinal, persistedDetails.getValue("seq").jsonPrimitive.int)
            assertEquals(
                "third-party-secret",
                persistedDetails.getValue("payload_id").jsonPrimitive.content,
            )

            val failures = listOf(
                runCatching { requireNotNull(repository.load(config.campaignId)) }
                    .exceptionOrNull(),
                runCatching { requireNotNull(repository.loadExportSnapshot(config.campaignId)) }
                    .exceptionOrNull(),
            )
            assertEquals(listOf(INVALID_GRAPH, INVALID_GRAPH), failures.map { it?.message })
            database.close()
        }

    private fun assertFormalCapabilityAuthority(config: PrototypeCampaignConfig) {
        val raw = config.nodeTicket.rawCapabilityBody
        assertEquals(PrototypeCampaignPersistenceFixture.formalCapabilityBody(), raw)
        assertTrue(raw.endsWith("\n"))
        assertTrue(raw.contains("\"server_version\" : \"room-v13-节点-正式\""))
        assertTrue(raw.contains("\"content_event_count\":120.0"))
        assertTrue(raw.contains("\"nominal_interval_ms\":5e1"))
        assertEquals("aneb-prototype-capabilities-0.1", config.nodeTicket.identity.schemaVersion)
        assertEquals("prototype-stream-0.1", config.nodeTicket.identity.protocolVersion)
        assertEquals("application_end_to_end_to_probe_node", config.nodeTicket.identity.claimScope)
        assertEquals(
            listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1"),
            config.nodeTicket.identity.conditions.map { it.id },
        )
    }

    private fun assertCompleteRunnerAuthority(result: PrototypeQuickCampaignRunner.CampaignResult) {
        assertEquals(3, result.summary.plannedRuns)
        assertEquals(listOf(1, 2, 3), result.runs.map { it.runIndex })
        assertEquals(
            listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1"),
            result.runs.map { it.conditionId },
        )
        assertEquals(
            listOf("baseline_v0.1", "slow_v0.1", "unstable_v0.1"),
            result.summary.conditionSummaries.map { it.conditionId },
        )
        assertEquals(listOf(122, 122, 122), result.runs.map { it.evidenceEvents.size })
        assertEquals(listOf(100, 48, 62), result.summary.conditionSummaries.map { it.rpi })
        assertTrue(result.runs.all { it.metrics != null })
        assertTrue(result.runs.all { it.terminalReceiptValid == true })
        val timestamps = result.runs.flatMap { run ->
            run.evidenceEvents.map { event -> event.long("client_monotonic_ns") }
        }
        assertTrue(timestamps.contains(PrototypeCampaignPersistenceFixture.LARGE_MONOTONIC_NS))
        assertTrue(timestamps.all { it > 9_007_199_254_740_992L })
    }

    private fun assertStoredAuthority(
        config: PrototypeCampaignConfig,
        result: PrototypeQuickCampaignRunner.CampaignResult,
        stored: PrototypeCampaignRoomRepository.StoredCampaign,
    ) {
        assertEquals(config.campaignId, stored.campaignId)
        assertEquals(config.nodeTicket.nodeBaseUrl, stored.nodeBaseUrl)
        assertEquals(config.nodeTicket.runUrl, stored.runUrl)
        assertEquals(config.nodeTicket.capabilityUrl, stored.capabilityUrl)
        assertEquals(config.nodeTicket.rawCapabilityBody, stored.rawCapabilityBody)
        assertEquals(config.nodeTicket.identity, stored.capabilityIdentity)
        assertEquals(result.summary, stored.summary)
        assertEquals(result.runs.size, stored.runs.size)
        assertEquals(result.runs.map { it.runIndex }, stored.runs.map { it.runIndex })
        assertEquals(result.runs.map { it.conditionId }, stored.runs.map { it.conditionId })

        result.runs.zip(stored.runs).forEach { (expected, actual) ->
            assertEquals(expected.runIndex, actual.runIndex)
            assertEquals(expected.runId, actual.runId)
            assertEquals(expected.conditionId, actual.conditionId)
            assertEquals(expected.status, actual.status)
            assertEquals(expected.taskSuccess, actual.taskSuccess)
            assertEquals(expected.scoreEligible, actual.scoreEligible)
            assertEquals(expected.eventsExpected, actual.eventsExpected)
            assertEquals(expected.eventsReceived, actual.eventsReceived)
            assertEquals(expected.failureReason, actual.failureReason)
            assertEquals(expected.terminalReceiptValid, actual.terminalReceiptValid)
            assertEquals(expected.metrics, actual.metrics)
            assertEquals(expected.evidenceEvents, actual.evidenceEvents)
            actual.evidenceEvents.forEachIndexed { index, event ->
                assertEquals(
                    expected.evidenceEvents[index].long("client_monotonic_ns"),
                    event.long("client_monotonic_ns"),
                )
            }
            if (actual.terminalReceiptValid == true) {
                val first = actual.evidenceEvents.first()
                val terminal = actual.evidenceEvents.last()
                assertEquals(18, first.size)
                assertEquals("aneb-prototype-evidence-0.1", first.string("schema_version"))
                assertEquals("android", first.string("source"))
                assertEquals("terminal_event", terminal.string("event_type"))
                assertEquals(24, (terminal.getValue("details") as JsonObject).size)
            } else {
                assertFalse(actual.evidenceEvents.any { it.string("event_type") == "terminal_event" })
            }
        }
    }

    private fun assertTableCounts(
        database: AnebDatabase,
        campaigns: Long,
        runs: Long,
        events: Long,
    ) {
        assertEquals(campaigns, rowCount(database, "prototype_campaign"))
        assertEquals(runs, rowCount(database, "prototype_run"))
        assertEquals(events, rowCount(database, "prototype_evidence_event"))
    }

    private fun rowCount(database: AnebDatabase, table: String): Long {
        require(table in EXACT_TABLES)
        return database.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM `$table`")
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
    }

    private fun scalarString(
        database: AnebDatabase,
        sql: String,
        vararg bindArgs: Any?,
    ): String = database.openHelper.writableDatabase.query(sql, bindArgs)
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun JsonObject.string(key: String): String =
        (getValue(key) as JsonPrimitive).content

    private fun JsonObject.long(key: String): Long =
        (getValue(key) as JsonPrimitive).content.toLong()

    private fun uniqueDatabaseName(prefix: String): String =
        "${prefix.first()}${databaseNames.size}.db".also(databaseNames::add)

    private fun roomConfig(campaignId: String): PrototypeCampaignConfig =
        PrototypeCampaignPersistenceFixture.campaignConfig(campaignId, ROOM_RUN_URL)

    private fun openFreshDatabase(
        databaseName: String,
        queryLog: MutableList<String>? = null,
    ): AnebDatabase {
        context.getDatabasePath(databaseName).parentFile?.let { directory ->
            check(directory.isDirectory || directory.mkdirs()) {
                "failed to create test database directory: $directory"
            }
        }
        val builder = Room.databaseBuilder(context, AnebDatabase::class.java, databaseName)
        if (queryLog != null) {
            builder.setQueryCallback(
                RoomDatabase.QueryCallback { sqlQuery, _ -> queryLog += sqlQuery },
                Executor { command -> command.run() },
            )
        }
        return builder.build()
    }

    private fun normalizeSql(sql: String): String = sql
        .lowercase()
        .replace("`", "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        const val ROOM_RUN_URL = "http://10.23.45.67:28088/api/v1/prototype/runs"
        const val INVALID_GRAPH = "prototype campaign persistence graph is inconsistent"

        val EXACT_TABLES = setOf(
            "prototype_campaign",
            "prototype_run",
            "prototype_evidence_event",
        )
    }
}

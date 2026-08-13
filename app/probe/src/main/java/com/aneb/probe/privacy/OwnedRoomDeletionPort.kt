package com.aneb.probe.privacy

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aneb.probe.data.AnebDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class OwnedRoomTable(
    val sqlName: String,
    val autoIncrement: Boolean = false,
) {
    TEST_RUN("test_run"),
    TOKEN_EVENT("token_event", autoIncrement = true),
    SCENARIO_RESULT("scenario_result", autoIncrement = true),
    ECHO_SAMPLE("echo_sample", autoIncrement = true),
    ENV_EVENT("env_event", autoIncrement = true),
    RADIO_SAMPLE("radio_sample", autoIncrement = true),
    REPORT_BODY("report_body"),
    CONTINUITY_RESULT("continuity_result"),
    API_PROBE_RESULT("api_probe_result", autoIncrement = true),
    AB_RESULT("ab_result", autoIncrement = true),
    BASIC_SPEED_RESULT("basic_speed_result"),
    TOKEN_SIMULATION_RESULT("token_simulation_result"),
    REALTIME_SIMULATION_RESULT("realtime_simulation_result"),
    NETWORK_COMPREHENSIVE_RESULT("network_comprehensive_result"),
    RESULT_ENVELOPE("result_envelope"),
    ;

    companion object {
        val autoIncrementTables: Set<OwnedRoomTable> = entries.filterTo(linkedSetOf()) {
            it.autoIncrement
        }
        val bySqlName: Map<String, OwnedRoomTable> = entries.associateBy(OwnedRoomTable::sqlName)
    }
}

internal data class OwnedRoomInventory(
    val rows: Map<OwnedRoomTable, Long>,
    val sequences: Map<OwnedRoomTable, Long>,
)

internal sealed interface OwnedRoomDeletionResult {
    data class Deleted(
        val before: OwnedRoomInventory,
        val after: OwnedRoomInventory,
    ) : OwnedRoomDeletionResult

    data class SchemaMismatch(
        val actualTables: Set<String>,
        val mutationAttempted: Boolean,
    ) : OwnedRoomDeletionResult

    data class ResidueDetected(
        val before: OwnedRoomInventory,
        val after: OwnedRoomInventory,
    ) : OwnedRoomDeletionResult

    data class Failed(
        val before: OwnedRoomInventory?,
        val afterFailure: OwnedRoomInventory?,
    ) : OwnedRoomDeletionResult
}

internal fun interface OwnedRoomDeletionPort {
    suspend fun deleteOwnedRoomData(): OwnedRoomDeletionResult
}

internal class AndroidOwnedRoomDeletionPort(
    private val databaseProvider: () -> AnebDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val beforeSequenceDeletion: () -> Unit = {},
) : OwnedRoomDeletionPort {
    override suspend fun deleteOwnedRoomData(): OwnedRoomDeletionResult = withContext(ioDispatcher) {
        var before: OwnedRoomInventory? = null
        var sqlite: SupportSQLiteDatabase? = null
        try {
            val database = databaseProvider()
            val openedSqlite = database.openHelper.writableDatabase
            sqlite = openedSqlite
            val expectedTables = OwnedRoomTable.entries.mapTo(linkedSetOf(), OwnedRoomTable::sqlName)
            val actualTables = openedSqlite.readOwnedUserTables()
            if (actualTables != expectedTables) {
                return@withContext OwnedRoomDeletionResult.SchemaMismatch(
                    actualTables = actualTables,
                    mutationAttempted = false,
                )
            }

            database.withTransaction {
                val beforeInventory = openedSqlite.readOwnedInventory()
                before = beforeInventory
                OwnedRoomTable.entries.forEach { table ->
                    openedSqlite.execSQL("DELETE FROM `${table.sqlName}`")
                }
                beforeSequenceDeletion()
                openedSqlite.clearOwnedSequences()
                val after = openedSqlite.readOwnedInventory()
                check(after.rows.values.all { it == 0L })
                check(after.sequences.isEmpty())
                OwnedRoomDeletionResult.Deleted(before = beforeInventory, after = after)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            val afterFailure = try {
                sqlite?.readOwnedInventory()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            OwnedRoomDeletionResult.Failed(before = before, afterFailure = afterFailure)
        }
    }

    private fun SupportSQLiteDatabase.readOwnedUserTables(): Set<String> = query(
        "SELECT name FROM sqlite_master " +
            "WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('android_metadata','room_master_table') ORDER BY name",
    ).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    private fun SupportSQLiteDatabase.readOwnedInventory(): OwnedRoomInventory {
        val rows = OwnedRoomTable.entries.associateWith { table ->
            query("SELECT COUNT(*) FROM `${table.sqlName}`").use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
        }
        val sequences = query(
            "SELECT name, seq FROM sqlite_sequence WHERE name IN " +
                OwnedRoomTable.autoIncrementTables.joinToString(",", prefix = "(", postfix = ")") { "?" },
            OwnedRoomTable.autoIncrementTables.map { it.sqlName }.toTypedArray(),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val table = checkNotNull(OwnedRoomTable.bySqlName[cursor.getString(0)])
                    put(table, cursor.getLong(1))
                }
            }
        }
        return OwnedRoomInventory(rows = rows, sequences = sequences)
    }

    private fun SupportSQLiteDatabase.clearOwnedSequences() {
        execSQL(
            "DELETE FROM sqlite_sequence WHERE name IN " +
                OwnedRoomTable.autoIncrementTables.joinToString(",", prefix = "(", postfix = ")") { "?" },
            OwnedRoomTable.autoIncrementTables.map<OwnedRoomTable, Any?> { it.sqlName }.toTypedArray(),
        )
    }
}

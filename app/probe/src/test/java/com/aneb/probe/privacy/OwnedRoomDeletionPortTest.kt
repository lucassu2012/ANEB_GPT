package com.aneb.probe.privacy

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aneb.probe.data.AnebDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OwnedRoomDeletionPortTest {
    private lateinit var database: AnebDatabase

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AnebDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun `deletes all sixteen owned tables and their sequence metadata`() = runBlocking {
        val sqlite = database.openHelper.writableDatabase
        OwnedRoomTable.entries.forEach { seedOneRow(sqlite, it.sqlName) }

        val result = AndroidOwnedRoomDeletionPort(
            databaseProvider = { database },
        ).deleteOwnedRoomData() as OwnedRoomDeletionResult.Deleted

        assertEquals(OwnedRoomTable.entries.toSet(), result.before.rows.keys)
        assertTrue(result.before.rows.values.all { it == 1L })
        assertEquals(OwnedRoomTable.autoIncrementTables, result.before.sequences.keys)
        assertEquals(OwnedRoomTable.entries.toSet(), result.after.rows.keys)
        assertTrue(result.after.rows.values.all { it == 0L })
        assertTrue(result.after.sequences.isEmpty())
    }

    @Test
    fun `sequence phase failure rolls back every owned table deletion`() = runBlocking {
        val sqlite = database.openHelper.writableDatabase
        OwnedRoomTable.entries.forEach { seedOneRow(sqlite, it.sqlName) }
        val result = AndroidOwnedRoomDeletionPort(
            databaseProvider = { database },
            beforeSequenceDeletion = { error("injected_sequence_failure") },
        ).deleteOwnedRoomData() as OwnedRoomDeletionResult.Failed

        assertTrue(result.before?.rows?.values?.all { it == 1L } == true)
        assertTrue(result.afterFailure?.rows?.values?.all { it == 1L } == true)
        assertEquals(
            OwnedRoomTable.autoIncrementTables,
            result.afterFailure?.sequences?.keys,
        )
    }

    @Test
    fun `unknown user table fails closed before any owned row mutation`() = runBlocking {
        val sqlite = database.openHelper.writableDatabase
        OwnedRoomTable.entries.forEach { seedOneRow(sqlite, it.sqlName) }
        sqlite.execSQL("CREATE TABLE `future_private_table` (`id` INTEGER NOT NULL PRIMARY KEY)")

        val result = AndroidOwnedRoomDeletionPort(
            databaseProvider = { database },
        ).deleteOwnedRoomData() as OwnedRoomDeletionResult.SchemaMismatch

        assertFalse(result.mutationAttempted)
        assertTrue("future_private_table" in result.actualTables)
        OwnedRoomTable.entries.forEach { table ->
            sqlite.query("SELECT COUNT(*) FROM `${table.sqlName}`").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }
        }
    }

    @Test
    fun `database provider failure returns a typed failure without mutation evidence`() = runBlocking {
        val result = AndroidOwnedRoomDeletionPort(
            databaseProvider = { error("raw_provider_failure_must_not_escape") },
        ).deleteOwnedRoomData() as OwnedRoomDeletionResult.Failed

        assertEquals(null, result.before)
        assertEquals(null, result.afterFailure)
    }

    private fun seedOneRow(database: SupportSQLiteDatabase, table: String) {
        val columns = database.query("PRAGMA table_info(`${table.escapeIdentifier()}`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                    val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1
                    val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                    val hasDefault = !cursor.isNull(defaultIndex)
                    val primaryKey = cursor.getInt(cursor.getColumnIndexOrThrow("pk")) > 0
                    if (notNull && !hasDefault && !(primaryKey && type.equals("INTEGER", ignoreCase = true))) {
                        add(name to type)
                    }
                }
            }
        }
        val names = columns.joinToString(",") { "`${it.first.escapeIdentifier()}`" }
        val placeholders = columns.joinToString(",") { "?" }
        val values = columns.map { (name, type) -> seedValue(table, name, type) }.toTypedArray()
        database.execSQL(
            "INSERT INTO `${table.escapeIdentifier()}` ($names) VALUES ($placeholders)",
            values,
        )
    }

    private fun seedValue(table: String, column: String, type: String): Any = when {
        type.contains("INT", ignoreCase = true) -> 1L
        type.contains("REAL", ignoreCase = true) ||
            type.contains("FLOA", ignoreCase = true) ||
            type.contains("DOUB", ignoreCase = true) -> 1.0
        type.contains("BLOB", ignoreCase = true) -> byteArrayOf(1)
        else -> "seed-$table-$column"
    }

    private fun String.escapeIdentifier(): String = replace("`", "``")
}

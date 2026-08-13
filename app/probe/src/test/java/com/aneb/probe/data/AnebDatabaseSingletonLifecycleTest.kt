package com.aneb.probe.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnebDatabaseSingletonLifecycleTest {
    @Test
    fun `closed singleton is retired without letting a stale close evict its replacement`() = runBlocking {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val databaseName = "aneb-probe.db"
        context.deleteDatabase(databaseName)
        var first: AnebDatabase? = null
        var replacement: AnebDatabase? = null

        try {
            val initial = AnebDatabase.get(context)
            first = initial
            assertSame(initial, AnebDatabase.get(context))
            withContext(Dispatchers.IO) {
                assertTrue(initial.apiProbeResultDao().recent(1).isEmpty())
            }
            assertTrue(initial.isOpen)

            initial.close()
            assertFalse(initial.isOpen)
            val current = AnebDatabase.get(context)
            replacement = current
            assertNotSame(initial, current)
            assertSame(current, AnebDatabase.get(context))
            withContext(Dispatchers.IO) {
                assertTrue(current.apiProbeResultDao().recent(1).isEmpty())
            }
            assertTrue(current.isOpen)

            initial.close()
            assertSame(current, AnebDatabase.get(context))
            withContext(Dispatchers.IO) {
                assertTrue(current.apiProbeResultDao().recent(1).isEmpty())
            }
        } finally {
            replacement?.close()
            first?.close()
            context.deleteDatabase(databaseName)
        }
    }
}

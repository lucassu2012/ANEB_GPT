package com.aneb.probe.privacy

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactNetlogDeletionPortTest {
    @Test
    fun `deletes only exact owned netlog files and preserves cache siblings`() = runBlocking {
        val root = Files.createTempDirectory("aneb-netlog-port").toFile()
        val internal = root.resolve("internal").apply { mkdirs() }
        val externalOne = root.resolve("external-one").apply { mkdirs() }
        val externalTwo = root.resolve("external-two").apply { mkdirs() }
        val targets = listOf(internal, externalOne, externalTwo).map { cache ->
            cache.resolve("ab_netlog_b.json").apply { writeText("private") }
        }
        val sibling = internal.resolve("must_remain.json").apply { writeText("keep") }

        val result = ExactNetlogDeletionPort(
            internalCacheDir = internal,
            externalCacheDirs = listOf(externalOne, externalTwo),
        ).deleteOwnedNetlogs()

        assertEquals(ExactNetlogDeletionResult.Cleared(fileCount = 3), result)
        assertTrue(targets.all { !it.exists() })
        assertTrue(sibling.exists())
        assertEquals("keep", sibling.readText())
        assertFalse(root.resolve("unrelated").exists())
    }

    @Test
    fun `unavailable external root does not suppress internal owned netlog deletion`() =
        runBlocking {
            val root = Files.createTempDirectory("aneb-netlog-unavailable-external").toFile()
            try {
                val internal = root.resolve("internal").apply { mkdirs() }
                val unavailableExternal = root.resolve("missing-external")
                val internalTarget = internal.resolve("ab_netlog_b.json").apply {
                    writeText("private")
                }
                val sibling = internal.resolve("must_remain.json").apply { writeText("keep") }

                val result = ExactNetlogDeletionPort(
                    internalCacheDir = internal,
                    externalCacheDirs = listOf(unavailableExternal),
                ).deleteOwnedNetlogs()

                assertEquals(ExactNetlogDeletionResult.StateUnavailable, result)
                assertFalse(internalTarget.exists())
                assertTrue(sibling.exists())
                assertEquals("keep", sibling.readText())
                assertFalse(unavailableExternal.exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `internal netlog port deletes only the internal owned target`() = runBlocking {
        val root = Files.createTempDirectory("aneb-internal-netlog-port").toFile()
        try {
            val internal = root.resolve("internal").apply { mkdirs() }
            val external = root.resolve("external").apply { mkdirs() }
            val internalTarget = internal.resolve("ab_netlog_b.json").apply {
                writeText("internal-private")
            }
            val externalTarget = external.resolve("ab_netlog_b.json").apply {
                writeText("external-private")
            }
            val sibling = internal.resolve("must_remain.json").apply { writeText("keep") }

            val result = InternalNetlogDeletionPort(
                internalCacheDir = internal,
            ).deleteOwnedNetlog()

            assertEquals(ExactNetlogDeletionResult.Cleared(fileCount = 1), result)
            assertFalse(internalTarget.exists())
            assertTrue(externalTarget.exists())
            assertEquals("external-private", externalTarget.readText())
            assertTrue(sibling.exists())
            assertEquals("keep", sibling.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `external netlog port deletes only external owned targets`() = runBlocking {
        val root = Files.createTempDirectory("aneb-external-netlog-port").toFile()
        try {
            val internal = root.resolve("internal").apply { mkdirs() }
            val externalOne = root.resolve("external-one").apply { mkdirs() }
            val externalTwo = root.resolve("external-two").apply { mkdirs() }
            val internalTarget = internal.resolve("ab_netlog_b.json").apply {
                writeText("internal-private")
            }
            val externalTargets = listOf(externalOne, externalTwo).mapIndexed { index, dir ->
                dir.resolve("ab_netlog_b.json").apply { writeText("external-private-$index") }
            }
            val sibling = externalOne.resolve("must_remain.json").apply { writeText("keep") }

            val result = ExternalNetlogDeletionPort(
                externalCacheDirs = listOf(externalOne, externalTwo),
            ).deleteOwnedNetlogs()

            assertEquals(ExactNetlogDeletionResult.Cleared(fileCount = 2), result)
            assertTrue(internalTarget.exists())
            assertEquals("internal-private", internalTarget.readText())
            assertTrue(externalTargets.all { target -> !target.exists() })
            assertTrue(sibling.exists())
            assertEquals("keep", sibling.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}

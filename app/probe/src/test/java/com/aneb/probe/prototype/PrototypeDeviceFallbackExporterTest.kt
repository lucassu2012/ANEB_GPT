package com.aneb.probe.prototype

import com.aneb.probe.data.Exporter
import com.aneb.probe.data.PrototypeCampaignRoomRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

class PrototypeDeviceFallbackExporterTest {
    @Test
    fun `validated snapshot is written once to an atomic unverified fallback zip`() = runBlocking {
        val source = exportSnapshot()
        val campaignId = source.campaign.campaignId
        val expectedFileName = "ANEB-Prototype-device-fallback-unique-001.zip"
        val publishedUri = "content://media/external/downloads/501"
        val publishedBytes = 98_765
        val loadCalls = mutableListOf<String>()
        var publishCalls = 0
        var capturedFileName: String? = null
        var capturedMimeType: String? = null
        var zipBytes = byteArrayOf()
        val exporter = PrototypeDeviceFallbackExporter(
            loadSnapshot = { requestedCampaignId ->
                loadCalls += requestedCampaignId
                source
            },
            publish = { fileName, mimeType, writer ->
                publishCalls += 1
                capturedFileName = fileName
                capturedMimeType = mimeType
                val destination = ByteArrayOutputStream()
                writer(destination)
                zipBytes = destination.toByteArray()
                Exporter.ExportOutcome(
                    ok = true,
                    uri = publishedUri,
                    bytes = publishedBytes,
                    error = null,
                )
            },
            fileNameFactory = { expectedFileName },
        )

        val success = requireSuccess(exporter.export(campaignId))

        assertEquals(listOf(campaignId), loadCalls)
        assertEquals(1, publishCalls)
        assertEquals(expectedFileName, capturedFileName)
        assertEquals("application/zip", capturedMimeType)
        assertFalse(requireNotNull(capturedFileName).contains(campaignId))
        listOf(
            source.campaign.nodeBaseUrl,
            source.campaign.runUrl,
            source.campaign.capabilityUrl,
        ).forEach { url -> assertFalse(requireNotNull(capturedFileName).contains(url)) }
        assertEquals(publishedUri, success.uri)
        assertEquals(publishedBytes, success.bytes)

        val entries = readEntries(zipBytes)
        assertArrayEquals(
            source.rawCapabilityBody.toByteArray(Charsets.UTF_8),
            entries.getValue("capability-response.json"),
        )
        val expectedEvents = source.lexicalEvidence.joinToString(
            separator = "\n",
            postfix = "\n",
        ) { evidence -> evidence.eventJson }.toByteArray(Charsets.UTF_8)
        assertArrayEquals(expectedEvents, entries.getValue("events.jsonl"))

        val campaign = Json.parseToJsonElement(
            entries.getValue("campaign-snapshot.json").toString(Charsets.UTF_8),
        ).jsonObject
        assertEquals(source.campaign.summary.campaignId, campaign.getValue("campaign_id").jsonPrimitive.content)
        assertEquals(source.campaign.summary.status.name, campaign.getValue("campaign_status").jsonPrimitive.content)
        val emittedRuns = campaign.getValue("runs").jsonArray.map { element -> element.jsonObject }
        assertEquals(source.campaign.runs.size, emittedRuns.size)
        source.campaign.runs.zip(emittedRuns).forEach { (expected, actual) ->
            assertEquals(expected.runIndex.toString(), actual.getValue("run_index").jsonPrimitive.content)
            assertEquals(expected.runId, actual.getValue("run_id").jsonPrimitive.content)
            assertEquals(expected.conditionId, actual.getValue("condition_id").jsonPrimitive.content)
            assertEquals(expected.status.name, actual.getValue("status").jsonPrimitive.content)
            assertEquals(expected.eventsExpected.toString(), actual.getValue("events_expected").jsonPrimitive.content)
            assertEquals(expected.eventsReceived.toString(), actual.getValue("events_received").jsonPrimitive.content)
        }

        val marker = entries.getValue("DEVICE_FALLBACK_UNVERIFIED.txt").toString(Charsets.UTF_8)
        assertTrue(marker.contains("device_fallback_unverified"))
        assertTrue(marker.contains("unverified"))
        assertTrue(marker.contains("not canonical evidence"))
        entries.values.forEach { bytes ->
            val text = bytes.toString(Charsets.UTF_8)
            assertFalse(text.contains(source.campaign.nodeBaseUrl))
            assertFalse(text.contains(source.campaign.runUrl))
            assertFalse(text.contains(source.campaign.capabilityUrl))
        }
    }

    @Test
    fun `concurrent calls are busy without side effects and success releases the gate`() = runBlocking {
        val source = exportSnapshot()
        val loadEntered = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val loadedIds = mutableListOf<String>()
        var fileNameCalls = 0
        var publishCalls = 0
        val exporter = PrototypeDeviceFallbackExporter(
            loadSnapshot = { campaignId ->
                loadedIds += campaignId
                if (loadedIds.size == 1) {
                    loadEntered.complete(Unit)
                    releaseLoad.await()
                }
                source
            },
            publish = { _, _, writer ->
                publishCalls += 1
                successfulPublish(writer, suffix = publishCalls)
            },
            fileNameFactory = {
                fileNameCalls += 1
                "ANEB-Prototype-device-fallback-gate-$fileNameCalls.zip"
            },
        )
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            exporter.export("campaign-owner")
        }
        loadEntered.await()

        assertSame(PrototypeDeviceFallbackExporter.Outcome.Busy, exporter.export("campaign-busy-1"))
        assertSame(PrototypeDeviceFallbackExporter.Outcome.Busy, exporter.export("campaign-busy-2"))
        assertEquals(listOf("campaign-owner"), loadedIds)
        assertEquals(0, fileNameCalls)
        assertEquals(0, publishCalls)

        releaseLoad.complete(Unit)
        requireSuccess(first.await())
        requireSuccess(exporter.export("campaign-after-success"))
        assertEquals(listOf("campaign-owner", "campaign-after-success"), loadedIds)
        assertEquals(2, fileNameCalls)
        assertEquals(2, publishCalls)
    }

    @Test
    fun `gate remains busy after writer completes until publisher returns`() = runBlocking {
        val source = exportSnapshot()
        val publisherAfterWrite = CountDownLatch(1)
        val releasePublisher = CountDownLatch(1)
        val loadCalls = AtomicInteger(0)
        val fileNameCalls = AtomicInteger(0)
        val publishCalls = AtomicInteger(0)
        val exporter = PrototypeDeviceFallbackExporter(
            loadSnapshot = {
                loadCalls.incrementAndGet()
                source
            },
            publish = { _, _, writer ->
                val call = publishCalls.incrementAndGet()
                writer(ByteArrayOutputStream())
                if (call == 1) {
                    publisherAfterWrite.countDown()
                    check(releasePublisher.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        "timed out waiting to release the publisher"
                    }
                }
                successfulOutcome(call)
            },
            fileNameFactory = {
                val call = fileNameCalls.incrementAndGet()
                "ANEB-Prototype-device-fallback-publisher-gate-$call.zip"
            },
        )
        val first = async(Dispatchers.Default) { exporter.export("campaign-owner") }

        try {
            assertTrue(
                "publisher did not reach the post-writer boundary",
                publisherAfterWrite.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
            assertFalse(first.isCompleted)
            val beforeBusy = listOf(loadCalls.get(), fileNameCalls.get(), publishCalls.get())
            assertEquals(listOf(1, 1, 1), beforeBusy)
            assertSame(
                PrototypeDeviceFallbackExporter.Outcome.Busy,
                exporter.export("campaign-candidate-7f4a"),
            )
            assertEquals(
                beforeBusy,
                listOf(loadCalls.get(), fileNameCalls.get(), publishCalls.get()),
            )
        } finally {
            releasePublisher.countDown()
        }

        requireSuccess(first.await())
        requireSuccess(exporter.export("campaign-after-publisher"))
        assertEquals(
            listOf(2, 2, 2),
            listOf(loadCalls.get(), fileNameCalls.get(), publishCalls.get()),
        )
    }

    @Test
    fun `missing snapshot is unavailable without naming or publishing and releases the gate`() = runBlocking {
        val source = exportSnapshot()
        var loadCalls = 0
        var fileNameCalls = 0
        var publishCalls = 0
        val exporter = PrototypeDeviceFallbackExporter(
            loadSnapshot = {
                loadCalls += 1
                if (loadCalls == 1) null else source
            },
            publish = { _, _, writer ->
                publishCalls += 1
                successfulPublish(writer, suffix = publishCalls)
            },
            fileNameFactory = {
                fileNameCalls += 1
                "ANEB-Prototype-device-fallback-missing-$fileNameCalls.zip"
            },
        )

        assertSame(PrototypeDeviceFallbackExporter.Outcome.Unavailable, exporter.export("missing"))
        assertEquals(1, loadCalls)
        assertEquals(0, fileNameCalls)
        assertEquals(0, publishCalls)
        requireSuccess(exporter.export("available"))
        assertEquals(2, loadCalls)
        assertEquals(1, fileNameCalls)
        assertEquals(1, publishCalls)
    }

    @Test
    fun `publisher failures never expose uri and each release the gate`() = runBlocking {
        val scripted = ArrayDeque(
            listOf(
                Exporter.ExportOutcome(false, null, 11, "publish false"),
                Exporter.ExportOutcome(true, null, 12, null),
                Exporter.ExportOutcome(false, "content://must-not-leak", 13, "inconsistent"),
            ),
        )
        var loadCalls = 0
        var publishCalls = 0
        val exporter = PrototypeDeviceFallbackExporter(
            loadSnapshot = {
                loadCalls += 1
                exportSnapshot()
            },
            publish = { _, _, writer ->
                publishCalls += 1
                writer(ByteArrayOutputStream())
                scripted.pollFirst() ?: successfulOutcome(suffix = publishCalls)
            },
            fileNameFactory = { "ANEB-Prototype-device-fallback-failure.zip" },
        )

        listOf(11, 12, 13).forEach { bytes ->
            assertSame(
                PrototypeDeviceFallbackExporter.Outcome.Failed,
                exporter.export("campaign-publisher-$bytes"),
            )
        }
        requireSuccess(exporter.export("campaign-after-failures"))
        assertEquals(4, loadCalls)
        assertEquals(4, publishCalls)
    }

    @Test
    fun `ordinary loader and publisher exceptions become failed and release the gate`() = runBlocking {
        val source = exportSnapshot()
        val loaderFailure = IOException("loader unavailable")
        val publisherFailure = IOException("publisher unavailable")
        var loadCalls = 0
        var publishCalls = 0
        val exporter = PrototypeDeviceFallbackExporter(
            loadSnapshot = {
                loadCalls += 1
                if (loadCalls == 1) throw loaderFailure
                source
            },
            publish = { _, _, writer ->
                publishCalls += 1
                if (publishCalls == 1) throw publisherFailure
                successfulPublish(writer, suffix = publishCalls)
            },
            fileNameFactory = { "ANEB-Prototype-device-fallback-exception.zip" },
        )

        assertSame(
            PrototypeDeviceFallbackExporter.Outcome.Failed,
            exporter.export("campaign-loader-failure"),
        )
        assertSame(
            PrototypeDeviceFallbackExporter.Outcome.Failed,
            exporter.export("campaign-publisher-exception"),
        )
        requireSuccess(exporter.export("campaign-after-exceptions"))
        assertEquals(3, loadCalls)
        assertEquals(2, publishCalls)
    }

    @Test
    fun `publisher and ordinary failures collapse to one opaque outcome`() = runBlocking {
        val source = exportSnapshot()
        val sensitiveUri = "content://private/export?token=secret-token"
        val sensitivePath = "C:\\private\\ANEB\\campaign.zip"
        val sensitiveBytes = 424_242
        val sensitiveError = "$sensitiveUri $sensitivePath"
        var loadCalls = 0
        var publishCalls = 0
        val exporter = PrototypeDeviceFallbackExporter(
            loadSnapshot = {
                loadCalls += 1
                if (loadCalls == 2) throw IOException(sensitiveError)
                source
            },
            publish = { _, _, writer ->
                publishCalls += 1
                writer(ByteArrayOutputStream())
                Exporter.ExportOutcome(
                    ok = false,
                    uri = sensitiveUri,
                    bytes = sensitiveBytes,
                    error = sensitiveError,
                )
            },
            fileNameFactory = { "ANEB-Prototype-device-fallback-private-failure.zip" },
        )

        val malformed = exporter.export("campaign-private-publisher-failure")
        val exceptional = exporter.export("campaign-private-loader-failure")
        assertTrue(malformed is PrototypeDeviceFallbackExporter.Outcome.Failed)
        assertTrue(exceptional is PrototypeDeviceFallbackExporter.Outcome.Failed)
        val rendered = "$malformed|$exceptional"
        listOf(
            sensitiveUri,
            "secret-token",
            sensitivePath,
            sensitiveBytes.toString(),
        ).forEach { secret -> assertFalse(rendered.contains(secret)) }
        assertSame(malformed, exceptional)
        assertEquals(2, loadCalls)
        assertEquals(1, publishCalls)
    }

    @Test
    fun `failed outcome remains a fieldless singleton frame`() {
        val instance = PrototypeDeviceFallbackExporter.Outcome.Failed
        val type = instance::class.java
        val fields = type.declaredFields
        val allowedCompilerFields = setOf("INSTANCE", "\$stable")

        assertTrue(fields.all { field -> field.name in allowedCompilerFields })
        assertTrue(
            fields.all { field ->
                Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers)
            },
        )
        val singletonField = fields.single { field -> field.name == "INSTANCE" }
        assertEquals(type, singletonField.type)
        assertSame(instance, singletonField.get(null))
        fields.singleOrNull { field -> field.name == "\$stable" }?.let { stable ->
            assertEquals(Int::class.javaPrimitiveType, stable.type)
        }
        val constructor = type.declaredConstructors.single()
        assertTrue(Modifier.isPrivate(constructor.modifiers))
        assertEquals(0, constructor.parameterCount)
    }

    @Test
    fun `publisher errors propagate unchanged and release the gate`() = runBlocking {
        val source = exportSnapshot()
        val fatal = AssertionError("publisher invariant failed")
        var publishCalls = 0
        val exporter = PrototypeDeviceFallbackExporter(
            loadSnapshot = { source },
            publish = { _, _, writer ->
                publishCalls += 1
                if (publishCalls == 1) throw fatal
                successfulPublish(writer, suffix = publishCalls)
            },
            fileNameFactory = { "ANEB-Prototype-device-fallback-error.zip" },
        )

        val thrown = runCatching { exporter.export("campaign-error") }.exceptionOrNull()
        assertSame(fatal, thrown)
        requireSuccess(exporter.export("campaign-after-error"))
        assertEquals(2, publishCalls)
    }

    @Test
    fun `publisher error after writer propagates unchanged before commit and releases the gate`() = runBlocking {
        val source = exportSnapshot()
        val fatal = AssertionError("publisher invariant failed after writer")
        val failAfterWrite: () -> Unit = { throw fatal }
        val firstWriterCompleted = AtomicBoolean(false)
        val firstPublisherCommitted = AtomicBoolean(false)
        var loadCalls = 0
        var fileNameCalls = 0
        var publishCalls = 0
        val exporter = PrototypeDeviceFallbackExporter(
            loadSnapshot = {
                loadCalls += 1
                source
            },
            publish = { _, _, writer ->
                publishCalls += 1
                if (publishCalls == 1) {
                    writer(ByteArrayOutputStream())
                    firstWriterCompleted.set(true)
                    failAfterWrite()
                    firstPublisherCommitted.set(true)
                    successfulOutcome(suffix = publishCalls)
                } else {
                    successfulPublish(writer, suffix = publishCalls)
                }
            },
            fileNameFactory = {
                fileNameCalls += 1
                "ANEB-Prototype-device-fallback-post-writer-error.zip"
            },
        )

        val thrown = runCatching {
            exporter.export("campaign-post-writer-error")
        }.exceptionOrNull()
        assertSame(fatal, thrown)
        assertTrue(firstWriterCompleted.get())
        assertFalse(firstPublisherCommitted.get())
        assertEquals(1, loadCalls)
        assertEquals(1, fileNameCalls)
        assertEquals(1, publishCalls)

        requireSuccess(exporter.export("campaign-after-post-writer-error"))
        assertEquals(2, loadCalls)
        assertEquals(2, fileNameCalls)
        assertEquals(2, publishCalls)
    }

    @Test
    fun `loader errors propagate unchanged before naming or publishing and release the gate`() = runBlocking {
        val source = exportSnapshot()
        val fatal = AssertionError("loader invariant failed")
        var loadCalls = 0
        var fileNameCalls = 0
        var publishCalls = 0
        val exporter = PrototypeDeviceFallbackExporter(
            loadSnapshot = {
                loadCalls += 1
                if (loadCalls == 1) throw fatal
                source
            },
            publish = { _, _, writer ->
                publishCalls += 1
                successfulPublish(writer, suffix = publishCalls)
            },
            fileNameFactory = {
                fileNameCalls += 1
                "ANEB-Prototype-device-fallback-loader-error.zip"
            },
        )

        val thrown = runCatching { exporter.export("campaign-loader-error") }.exceptionOrNull()
        assertSame(fatal, thrown)
        assertEquals(1, loadCalls)
        assertEquals(0, fileNameCalls)
        assertEquals(0, publishCalls)

        requireSuccess(exporter.export("campaign-after-loader-error"))
        assertEquals(2, loadCalls)
        assertEquals(1, fileNameCalls)
        assertEquals(1, publishCalls)
    }

    @Test
    fun `writer errors propagate unchanged before publisher commit and release the gate`() = runBlocking {
        val source = exportSnapshot()
        val fatal = AssertionError("writer invariant failed")
        val writerEntered = AtomicBoolean(false)
        val firstPublisherCommitted = AtomicBoolean(false)
        var loadCalls = 0
        var fileNameCalls = 0
        var publishCalls = 0
        val exporter = PrototypeDeviceFallbackExporter(
            loadSnapshot = {
                loadCalls += 1
                source
            },
            publish = { _, _, writer ->
                publishCalls += 1
                if (publishCalls == 1) {
                    writer(ThrowOnceOutputStream(fatal) { writerEntered.set(true) })
                    firstPublisherCommitted.set(true)
                    successfulOutcome(suffix = publishCalls)
                } else {
                    successfulPublish(writer, suffix = publishCalls)
                }
            },
            fileNameFactory = {
                fileNameCalls += 1
                "ANEB-Prototype-device-fallback-writer-error.zip"
            },
        )

        val thrown = runCatching { exporter.export("campaign-writer-error") }.exceptionOrNull()
        assertSame(fatal, thrown)
        assertTrue(writerEntered.get())
        assertFalse(firstPublisherCommitted.get())
        assertEquals(1, loadCalls)
        assertEquals(1, fileNameCalls)
        assertEquals(1, publishCalls)

        requireSuccess(exporter.export("campaign-after-writer-error"))
        assertEquals(2, loadCalls)
        assertEquals(2, fileNameCalls)
        assertEquals(2, publishCalls)
    }

    @Test
    fun `cancellation after noncooperative load propagates unchanged before publish and releases the gate`() =
        runBlocking {
            val source = exportSnapshot()
            val cancelled = CancellationException("cancelled after load")
            var loadCalls = 0
            var publishCalls = 0
            val exporter = PrototypeDeviceFallbackExporter(
                loadSnapshot = {
                    loadCalls += 1
                    if (loadCalls == 1) {
                        requireNotNull(currentCoroutineContext()[Job]).cancel(cancelled)
                    }
                    source
                },
                publish = { _, _, writer ->
                    publishCalls += 1
                    successfulPublish(writer, suffix = publishCalls)
                },
                fileNameFactory = { "ANEB-Prototype-device-fallback-cancel-load.zip" },
            )

            val observedFailure = CompletableDeferred<Throwable?>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    exporter.export("campaign-cancel-load")
                    observedFailure.complete(null)
                } catch (failure: Throwable) {
                    observedFailure.complete(failure)
                    throw failure
                }
            }
            assertSame(cancelled, observedFailure.await())
            runCatching { first.await() }
            assertEquals(0, publishCalls)

            requireSuccess(exporter.export("campaign-after-cancel-load"))
            assertEquals(2, loadCalls)
            assertEquals(1, publishCalls)
        }

    @Test
    fun `active job loader cancellation propagates unchanged before naming or publishing and releases the gate`() =
        runBlocking {
            val source = exportSnapshot()
            val cancelled = CancellationException("loader cancelled directly")
            val callerJob = requireNotNull(currentCoroutineContext()[Job])
            var loadCalls = 0
            var fileNameCalls = 0
            var publishCalls = 0
            val exporter = PrototypeDeviceFallbackExporter(
                loadSnapshot = {
                    loadCalls += 1
                    if (loadCalls == 1) {
                        assertTrue(requireNotNull(currentCoroutineContext()[Job]).isActive)
                        throw cancelled
                    }
                    source
                },
                publish = { _, _, writer ->
                    publishCalls += 1
                    successfulPublish(writer, suffix = publishCalls)
                },
                fileNameFactory = {
                    fileNameCalls += 1
                    "ANEB-Prototype-device-fallback-direct-loader-cancel.zip"
                },
            )

            val thrown = runCatching {
                exporter.export("campaign-direct-loader-cancel")
            }.exceptionOrNull()
            assertSame(cancelled, thrown)
            assertTrue(callerJob.isActive)
            assertEquals(1, loadCalls)
            assertEquals(0, fileNameCalls)
            assertEquals(0, publishCalls)

            requireSuccess(exporter.export("campaign-after-direct-loader-cancel"))
            assertEquals(2, loadCalls)
            assertEquals(1, fileNameCalls)
            assertEquals(1, publishCalls)
        }

    @Test
    fun `active job writer cancellation propagates unchanged before publisher commit and releases the gate`() =
        runBlocking {
            val source = exportSnapshot()
            val cancelled = CancellationException("writer cancelled directly")
            val callerJob = requireNotNull(currentCoroutineContext()[Job])
            val writerEntered = AtomicBoolean(false)
            val firstPublisherCommitted = AtomicBoolean(false)
            var loadCalls = 0
            var fileNameCalls = 0
            var publishCalls = 0
            val exporter = PrototypeDeviceFallbackExporter(
                loadSnapshot = {
                    loadCalls += 1
                    source
                },
                publish = { _, _, writer ->
                    publishCalls += 1
                    if (publishCalls == 1) {
                        writer(
                            ThrowOnceOutputStream(cancelled) {
                                assertTrue(callerJob.isActive)
                                writerEntered.set(true)
                            },
                        )
                        firstPublisherCommitted.set(true)
                        successfulOutcome(suffix = publishCalls)
                    } else {
                        successfulPublish(writer, suffix = publishCalls)
                    }
                },
                fileNameFactory = {
                    fileNameCalls += 1
                    "ANEB-Prototype-device-fallback-direct-writer-cancel.zip"
                },
            )

            val thrown = runCatching {
                exporter.export("campaign-direct-writer-cancel")
            }.exceptionOrNull()
            assertSame(cancelled, thrown)
            assertTrue(callerJob.isActive)
            assertTrue(writerEntered.get())
            assertFalse(firstPublisherCommitted.get())
            assertEquals(1, loadCalls)
            assertEquals(1, fileNameCalls)
            assertEquals(1, publishCalls)

            requireSuccess(exporter.export("campaign-after-direct-writer-cancel"))
            assertEquals(2, loadCalls)
            assertEquals(2, fileNameCalls)
            assertEquals(2, publishCalls)
        }

    @Test
    fun `active job publisher cancellation propagates unchanged before writer invocation and releases the gate`() =
        runBlocking {
            val source = exportSnapshot()
            val cancelled = CancellationException("publisher cancelled directly")
            val callerJob = requireNotNull(currentCoroutineContext()[Job])
            var loadCalls = 0
            var fileNameCalls = 0
            var publishCalls = 0
            var writerCalls = 0
            val exporter = PrototypeDeviceFallbackExporter(
                loadSnapshot = {
                    loadCalls += 1
                    source
                },
                publish = { _, _, writer ->
                    publishCalls += 1
                    if (publishCalls == 1) {
                        assertTrue(callerJob.isActive)
                        throw cancelled
                    }
                    writerCalls += 1
                    successfulPublish(writer, suffix = publishCalls)
                },
                fileNameFactory = {
                    fileNameCalls += 1
                    "ANEB-Prototype-device-fallback-direct-publisher-cancel.zip"
                },
            )

            val thrown = runCatching {
                exporter.export("campaign-direct-publisher-cancel")
            }.exceptionOrNull()
            assertSame(cancelled, thrown)
            assertTrue(callerJob.isActive)
            assertEquals(1, loadCalls)
            assertEquals(1, fileNameCalls)
            assertEquals(1, publishCalls)
            assertEquals(0, writerCalls)

            requireSuccess(exporter.export("campaign-after-direct-publisher-cancel"))
            assertEquals(2, loadCalls)
            assertEquals(2, fileNameCalls)
            assertEquals(2, publishCalls)
            assertEquals(1, writerCalls)
        }

    @Test
    fun `active job publisher cancellation after writer propagates unchanged before commit and releases the gate`() =
        runBlocking {
            val source = exportSnapshot()
            val cancelled = CancellationException("publisher cancelled after writer")
            val failAfterWrite: () -> Unit = { throw cancelled }
            val callerJob = requireNotNull(currentCoroutineContext()[Job])
            val firstWriterCompleted = AtomicBoolean(false)
            val firstPublisherCommitted = AtomicBoolean(false)
            var loadCalls = 0
            var fileNameCalls = 0
            var publishCalls = 0
            val exporter = PrototypeDeviceFallbackExporter(
                loadSnapshot = {
                    loadCalls += 1
                    source
                },
                publish = { _, _, writer ->
                    publishCalls += 1
                    if (publishCalls == 1) {
                        assertTrue(callerJob.isActive)
                        writer(ByteArrayOutputStream())
                        firstWriterCompleted.set(true)
                        failAfterWrite()
                        firstPublisherCommitted.set(true)
                        successfulOutcome(suffix = publishCalls)
                    } else {
                        successfulPublish(writer, suffix = publishCalls)
                    }
                },
                fileNameFactory = {
                    fileNameCalls += 1
                    "ANEB-Prototype-device-fallback-post-writer-cancel.zip"
                },
            )

            val thrown = runCatching {
                exporter.export("campaign-post-writer-cancel")
            }.exceptionOrNull()
            assertSame(cancelled, thrown)
            assertTrue(callerJob.isActive)
            assertTrue(firstWriterCompleted.get())
            assertFalse(firstPublisherCommitted.get())
            assertEquals(1, loadCalls)
            assertEquals(1, fileNameCalls)
            assertEquals(1, publishCalls)

            requireSuccess(exporter.export("campaign-after-post-writer-cancel"))
            assertEquals(2, loadCalls)
            assertEquals(2, fileNameCalls)
            assertEquals(2, publishCalls)
        }

    @Test
    fun `cancellation during synchronous bundle write stops publisher before commit and releases the gate`() =
        runBlocking {
            val source = exportSnapshot()
            val cancelled = CancellationException("cancelled during bundle write")
            val writeEntered = CompletableDeferred<Unit>()
            val releaseWrite = CountDownLatch(1)
            val firstPublisherReachedSuccess = AtomicBoolean(false)
            var publishCalls = 0
            val exporter = PrototypeDeviceFallbackExporter(
                loadSnapshot = { source },
                publish = { _, _, writer ->
                    publishCalls += 1
                    if (publishCalls == 1) {
                        writer(BlockingFirstWriteOutputStream(writeEntered, releaseWrite))
                        firstPublisherReachedSuccess.set(true)
                    } else {
                        writer(ByteArrayOutputStream())
                    }
                    successfulOutcome(suffix = publishCalls)
                },
                fileNameFactory = { "ANEB-Prototype-device-fallback-cancel-write.zip" },
            )

            val observedFailure = CompletableDeferred<Throwable?>()
            val first = async(Dispatchers.Default) {
                try {
                    exporter.export("campaign-cancel-write")
                    observedFailure.complete(null)
                } catch (failure: Throwable) {
                    observedFailure.complete(failure)
                    throw failure
                }
            }
            writeEntered.await()
            first.cancel(cancelled)
            releaseWrite.countDown()

            assertSame(cancelled, observedFailure.await())
            runCatching { first.await() }
            assertFalse(firstPublisherReachedSuccess.get())
            requireSuccess(exporter.export("campaign-after-cancel-write"))
            assertEquals(2, publishCalls)
        }

    @Test
    fun `cancellation after publisher commit propagates unchanged without reporting success and releases the gate`() =
        runBlocking {
            val source = exportSnapshot()
            val cancelled = CancellationException("cancelled after publisher commit")
            val firstPublishCommitted = AtomicBoolean(false)
            var callerJob: Job? = null
            var publishCalls = 0
            val exporter = PrototypeDeviceFallbackExporter(
                loadSnapshot = {
                    callerJob = currentCoroutineContext()[Job]
                    source
                },
                publish = { _, _, writer ->
                    publishCalls += 1
                    writer(ByteArrayOutputStream())
                    if (publishCalls == 1) {
                        firstPublishCommitted.set(true)
                        requireNotNull(callerJob).cancel(cancelled)
                    }
                    successfulOutcome(suffix = publishCalls)
                },
                fileNameFactory = { "ANEB-Prototype-device-fallback-cancel-published.zip" },
            )

            val observedFailure = CompletableDeferred<Throwable?>()
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    exporter.export("campaign-cancel-published")
                    observedFailure.complete(null)
                } catch (failure: Throwable) {
                    observedFailure.complete(failure)
                    throw failure
                }
            }

            assertSame(cancelled, observedFailure.await())
            runCatching { first.await() }
            assertTrue(firstPublishCommitted.get())
            requireSuccess(exporter.export("campaign-after-cancel-published"))
            assertEquals(2, publishCalls)
        }

    private fun requireSuccess(
        outcome: PrototypeDeviceFallbackExporter.Outcome?,
    ): PrototypeDeviceFallbackExporter.Outcome.Success {
        val required = requireNotNull(outcome)
        assertTrue(required is PrototypeDeviceFallbackExporter.Outcome.Success)
        return required as PrototypeDeviceFallbackExporter.Outcome.Success
    }

    private fun successfulPublish(
        writer: (OutputStream) -> Unit,
        suffix: Int,
    ): Exporter.ExportOutcome {
        writer(ByteArrayOutputStream())
        return successfulOutcome(suffix)
    }

    private fun successfulOutcome(suffix: Int): Exporter.ExportOutcome = Exporter.ExportOutcome(
        ok = true,
        uri = "content://media/external/downloads/$suffix",
        bytes = 1_000 + suffix,
        error = null,
    )

    private class BlockingFirstWriteOutputStream(
        private val entered: CompletableDeferred<Unit>,
        private val release: CountDownLatch,
    ) : ByteArrayOutputStream() {
        private val blocked = AtomicBoolean(false)

        override fun write(value: Int) {
            awaitReleaseOnce()
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            awaitReleaseOnce()
            super.write(bytes, offset, length)
        }

        private fun awaitReleaseOnce() {
            if (blocked.compareAndSet(false, true)) {
                entered.complete(Unit)
                check(release.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    "timed out waiting to release the synchronous bundle write"
                }
            }
        }
    }

    private class ThrowOnceOutputStream(
        private val failure: Throwable,
        private val onFirstWrite: () -> Unit,
    ) : OutputStream() {
        private val thrown = AtomicBoolean(false)

        override fun write(value: Int) {
            throwOnce()
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            throwOnce()
        }

        private fun throwOnce() {
            if (thrown.compareAndSet(false, true)) {
                onFirstWrite()
                throw failure
            }
        }
    }

    private fun exportSnapshot(): PrototypeCampaignRoomRepository.ExportSnapshot {
        val campaignId = "campaign-sensitive-export-id"
        val config = PrototypeCampaignPersistenceFixture.campaignConfig(campaignId)
        val eventTexts = lexicalEvidence(campaignId)
        val runs = listOf(
            PrototypeCampaignRoomRepository.StoredRun(
                runIndex = 1,
                runId = "$campaignId-run-01",
                conditionId = "baseline_v0.1",
                status = PrototypeQuickCampaignRunner.RunStatus.INTERRUPTED,
                taskSuccess = false,
                scoreEligible = false,
                eventsExpected = 120,
                eventsReceived = 0,
                failureReason = "stream_interrupted",
                terminalReceiptValid = null,
                metrics = null,
                evidenceEvents = eventTexts.map { text ->
                    Json.parseToJsonElement(text).jsonObject
                },
            ),
            notStartedRun(2, "$campaignId-run-02", "slow_v0.1"),
            notStartedRun(3, "$campaignId-run-03", "unstable_v0.1"),
        )
        val summary = PrototypeQuickCampaignRunner.canonicalCampaignSummary(
            campaignId = campaignId,
            results = runs.map { run ->
                PrototypeQuickCampaignRunner.SummaryRun(
                    conditionId = run.conditionId,
                    status = run.status,
                    taskSuccess = run.taskSuccess,
                    scoreEligible = run.scoreEligible,
                    metrics = run.metrics,
                )
            },
        )
        val campaign = PrototypeCampaignRoomRepository.StoredCampaign(
            campaignId = campaignId,
            nodeBaseUrl = config.nodeTicket.nodeBaseUrl,
            runUrl = config.nodeTicket.runUrl,
            capabilityUrl = config.nodeTicket.capabilityUrl,
            rawCapabilityBody = config.nodeTicket.rawCapabilityBody,
            capabilityIdentity = config.nodeTicket.identity,
            summary = summary,
            runs = runs,
        )
        return PrototypeCampaignRoomRepository.ExportSnapshot(
            campaign = campaign,
            rawCapabilityBody = campaign.rawCapabilityBody,
            lexicalEvidence = eventTexts.mapIndexed { index, eventJson ->
                PrototypeCampaignRoomRepository.LexicalEvidence(
                    runIndex = 1,
                    runId = runs.first().runId,
                    eventOrdinal = index,
                    eventJson = eventJson,
                )
            },
        )
    }

    private fun notStartedRun(
        runIndex: Int,
        runId: String,
        conditionId: String,
    ) = PrototypeCampaignRoomRepository.StoredRun(
        runIndex = runIndex,
        runId = runId,
        conditionId = conditionId,
        status = PrototypeQuickCampaignRunner.RunStatus.NOT_STARTED,
        taskSuccess = false,
        scoreEligible = false,
        eventsExpected = 120,
        eventsReceived = 0,
        failureReason = "not_started",
        terminalReceiptValid = null,
        metrics = null,
        evidenceEvents = emptyList(),
    )

    private fun lexicalEvidence(campaignId: String): List<String> {
        val runId = "$campaignId-run-01"
        val sharedMembers = """
            "schema_version":"aneb-prototype-evidence-0.1",
            "campaign_id":"$campaignId",
            "run_id":"$runId",
            "campaign_mode":"quick",
            "run_index":1,
            "condition_id":"baseline_v0.1",
            "condition_version":"0.1",
            "nominal_interval_ms":50,
            "profile_manifest_sha256":"$TEST_PROFILE_MANIFEST_SHA256",
            "schedule_hash":"${evidenceCondition("baseline_v0.1").scheduleHash}",
        """.trimIndent()
        return listOf(
            """{
              $sharedMembers
              "event_type":"run_started",
              "client_monotonic_ns":9007199254740993,
              "clock_source":"android.os.SystemClock.elapsedRealtimeNanos",
              "clock_unit":"ns",
              "clock_epoch":"device_boot",
              "clock_domain_id":"fallback-domain-1",
              "source":"android",
              "details":{"note":"节点 · lexical one"}
            }""".trimIndent(),
            """{"details":{"failure_reason":"stream_interrupted","note":"lexical two"},
              "source":"android","clock_domain_id":"fallback-domain-1","clock_epoch":"device_boot",
              "clock_unit":"ns","clock_source":"android.os.SystemClock.elapsedRealtimeNanos",
              "client_monotonic_ns":9007199254741993,"event_type":"run_failed",$sharedMembers
              "ignored_whitespace_marker":"保留原始字节"}""".trimIndent()
                .replace(",\n              \"ignored_whitespace_marker\":\"保留原始字节\"", ""),
        )
    }

    private fun readEntries(zipBytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 5_000L
    }
}

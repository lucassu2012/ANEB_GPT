package com.aneb.probe.ui

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.aneb.probe.data.RadioSampleEntity
import com.aneb.probe.data.ScenarioResultEntity
import com.aneb.probe.data.TestRun
import com.aneb.probe.ui.PairedComparisonFormat.ComparableRunInput
import com.aneb.probe.ui.PairedComparisonFormat.PairedComparisonResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairedComparisonShareCardTest {

    private fun sampleCompatibleResult(): PairedComparisonResult.Compatible {
        fun makeRun(id: String, score: Double, transport: String) = TestRun(
            runId = id,
            startedAtEpochMs = 1_752_000_000_000L,
            serverBase = ProbeSettings.DEFAULT_SERVER_URL,
            mode = "quick",
            scenarioOrder = "s1_chat,s2_coding_agent,s3_multimodal",
            transport = transport,
            kpiSet = "agent-qoe-kpi-v0.2",
            aqsVersion = "aqs-v0.1",
            profileVersions = "s1_chat:1.0.0",
            schemaVersion = "1.0",
            profileSource = "server",
            appVersionName = "0.2.0",
            appVersionCode = 20L,
            guardMetadata = null,
            aqsScore = score,
            aqsLowConfidence = false,
            aqsVetoApplied = false,
            aqsNotComputableReason = null,
            status = "completed",
            reportStatus = "http=200",
        )

        fun makeScenario(runId: String, profileId: String, netTransport: String, t1: Double, u1: Double) =
            ScenarioResultEntity(
                runId = runId,
                profileId = profileId,
                profileVersion = "1.0.0",
                repeatIndex = 0,
                orderIndex = 0,
                startedAtNanos = 1L,
                endedAtNanos = 2L,
                validity = "valid",
                invalidReasons = "",
                t1TtftMs = t1, t1Grade = null,
                t2ItlP95Ms = 60.0, t2Grade = null,
                t2ItlP95InclCoalescedMs = 62.0,
                t3StallRate = 0.01, t3Grade = null,
                t3StallRateInclResume = 0.01,
                t4SevereStallRate = 0.0, t4Grade = null,
                t5ResumeP95Ms = null,
                n1RttP50Ms = 24.0, n1Grade = null,
                n2JitterMs = 4.0, n2Grade = null,
                u1GoodputMbps = u1, u1Grade = null,
                u1GoodputExclSlowStartMbps = u1,
                u2ToolLoopP95Ms = 120.0, u2Grade = null,
                seqGapCount = 0,
                seqDupCount = 0,
                lowConfidenceKpis = "",
                offsetStartUs = 10L, offsetStartErrUs = 1L,
                offsetEndUs = 20L, offsetEndErrUs = 1L,
                offsetDriftPpm = 1.0, offsetSuspect = false,
                netTransport = netTransport,
                netCapabilities = null,
                netInterfaceName = null,
                serverObservedAddr = null,
                parseDurUsTotal = 50L,
                perEventParseUs = 1.0,
            )

        val wifi = ComparableRunInput.Token(
            run = makeRun("w1", 90.0, "wifi"),
            scenarios = listOf(
                makeScenario("w1", "s1_chat", "wifi", 120.0, 50.0),
                makeScenario("w1", "s2_coding_agent", "wifi", 140.0, 50.0),
                makeScenario("w1", "s3_multimodal", "wifi", 140.0, 50.0),
            ),
        )
        val nr = ComparableRunInput.Token(
            run = makeRun("n1", 78.0, "cellular"),
            scenarios = listOf(
                makeScenario("n1", "s1_chat", "cellular", 220.0, 28.0),
                makeScenario("n1", "s2_coding_agent", "cellular", 230.0, 28.0),
                makeScenario("n1", "s3_multimodal", "cellular", 230.0, 28.0),
            ),
            radioSamples = listOf(
                RadioSampleEntity(
                    runId = "n1",
                    tsNanos = 1L,
                    cellTsNanos = 1L,
                    stale = false,
                    subId = 1,
                    subSwitched = false,
                    networkType = "NR",
                    overrideType = null,
                    nrState = "connected",
                    rat = "NR",
                    pci = 1,
                    tac = 1,
                    arfcn = 1,
                    rsrp = -85,
                    rsrq = -8,
                    sinr = 14,
                    operatorName = "Test",
                ),
            ),
        )
        return PairedComparisonFormat.compare(wifi, nr) as PairedComparisonResult.Compatible
    }

    @Test
    fun `toShareModel maps up to 6 rows without raw server URL or PII and renderBitmap outputs 1080x1350 PNG`() {
        val model = PairedComparisonShareCard.toShareModel(sampleCompatibleResult())
        assertEquals(6, model.rows.size)
        assertFalse(model.leftMetaLine.contains("https://"))
        assertFalse(model.rightMetaLine.contains("https://"))
        assertTrue(model.leftBadge.contains("Wi-Fi"))
        assertTrue(model.rightBadge.contains("5G NR"))

        val bmp = PairedComparisonShareCard.renderBitmap(model)
        try {
            assertEquals(1080, bmp.width)
            assertEquals(1350, bmp.height)
            assertEquals(Bitmap.Config.ARGB_8888, bmp.config)
            // Verify non-uniform pixel content (header/cards/text actually rendered)
            assertNotEquals(bmp.getPixel(0, 0), bmp.getPixel(540, 250))

            val bytes = ByteArrayOutputStream().also { out ->
                assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, out))
            }.toByteArray()
            assertTrue(bytes.size > 1000)
            // PNG magic bytes: 89 50 4E 47
            assertEquals(0x89.toByte(), bytes[0])
            assertEquals(0x50.toByte(), bytes[1])
            assertEquals(0x4E.toByte(), bytes[2])
            assertEquals(0x47.toByte(), bytes[3])

            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull(decoded)
            assertEquals(1080, decoded.width)
            assertEquals(1350, decoded.height)
            decoded.recycle()
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun `drawTo writes expected comparison strings via seam`() {
        val model = PairedComparisonShareCard.toShareModel(sampleCompatibleResult())
        val drawnTexts = mutableListOf<String>()
        val recordingCanvas = object : Canvas() {
            override fun drawText(text: String, x: Float, y: Float, paint: android.graphics.Paint) {
                drawnTexts += text
            }
        }
        PairedComparisonShareCard.drawTo(recordingCanvas, model)
        assertTrue(drawnTexts.any { it.contains("ANEB PROBE") })
        assertTrue(drawnTexts.any { it.contains("PAIRED COMPARISON") })
        assertTrue(drawnTexts.any { it.contains("A · Wi-Fi") })
        assertTrue(drawnTexts.any { it.contains("B · 5G NR") })
        assertTrue(drawnTexts.any { it.contains("由 ANEB Probe 在本机生成") })
    }

    @Test
    fun `saveBitmap publishes IS_PENDING 1 to 0 in Pictures ANEB and deletes pending row on failure`() {
        val bmp = PairedComparisonShareCard.renderBitmap(PairedComparisonShareCard.toShareModel(sampleCompatibleResult()))
        try {
            val okGateway = FakePendingImageGateway()
            val okOutcome = PairedComparisonShareCard.saveBitmap(okGateway, bmp, "aneb_compare_test.png")
            assertTrue(okOutcome.ok)
            assertEquals(okGateway.itemUri, okOutcome.uri)
            assertEquals(listOf("insert", "open", "update"), okGateway.ops)
            assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, okGateway.insertCollection)
            assertEquals("${Environment.DIRECTORY_PICTURES}/ANEB", okGateway.insertValues.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
            assertEquals("image/png", okGateway.insertValues.getAsString(MediaStore.Images.Media.MIME_TYPE))
            assertEquals(1, okGateway.insertValues.getAsInteger(MediaStore.Images.Media.IS_PENDING).toInt())
            assertEquals(0, okGateway.updateValues.getAsInteger(MediaStore.Images.Media.IS_PENDING).toInt())
            assertTrue(okGateway.output.size() > 1000)

            val failGateway = FakePendingImageGateway(updateFailure = IOException("disk full"))
            val failOutcome = PairedComparisonShareCard.saveBitmap(failGateway, bmp, "fail.png")
            assertFalse(failOutcome.ok)
            assertNull(failOutcome.uri)
            assertEquals(listOf("insert", "open", "update", "delete"), failGateway.ops)
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun `launchShare sends ACTION_SEND image png with EXTRA_STREAM and handles missing target`() {
        val uri = Uri.parse("content://media/external/images/media/99")
        val okContext = RecordingContext(RuntimeEnvironment.getApplication())
        assertTrue(PairedComparisonShareCard.launchShare(okContext, uri))

        val chooser = okContext.startedIntents.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)

        val failContext = RecordingContext(
            RuntimeEnvironment.getApplication(),
            failure = ActivityNotFoundException("no target"),
        )
        assertFalse(PairedComparisonShareCard.launchShare(failContext, uri))
    }

    @Test
    fun `wrapped 2-line Token headline clears table divider below baseline plus descent`() {
        val baseModel = PairedComparisonShareCard.toShareModel(sampleCompatibleResult())
        val wrappedHeadline =
            "Wi-Fi vs 5G NR · AQS 体验分 90.0 分 → 78.0 分（-12.0 分 (-13.3%) · 额外超长对比摘要测试）"
        val model = baseModel.copy(headline = wrappedHeadline)

        val bmp = Bitmap.createBitmap(
            PairedComparisonShareCard.WIDTH,
            PairedComparisonShareCard.HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        val texts = mutableListOf<DrawnText>()
        val lines = mutableListOf<DrawnLine>()
        val canvas = object : Canvas(bmp) {
            override fun drawText(text: String, x: Float, y: Float, paint: android.graphics.Paint) {
                val fm = paint.fontMetrics
                texts += DrawnText(
                    text = text,
                    x = x,
                    y = y,
                    align = paint.textAlign,
                    width = paint.measureText(text),
                    descent = fm.descent,
                )
                super.drawText(text, x, y, paint)
            }

            override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: android.graphics.Paint) {
                lines += DrawnLine(startX, startY, stopX, stopY)
                super.drawLine(startX, startY, stopX, stopY, paint)
            }
        }

        try {
            PairedComparisonShareCard.drawTo(canvas, model)

            val headlineLines = texts.filter { it.y in 380f..450f }.sortedBy { it.y }
            assertEquals("Expected headline to wrap onto 2 lines: $headlineLines", 2, headlineLines.size)
            val line2 = headlineLines[1]
            val line2Bottom = line2.y + line2.descent

            val topDivider = lines.firstOrNull {
                it.startX == 72f && it.stopX == 1008f && it.startY == it.stopY && it.startY in 410f..490f
            }
            assertNotNull("Top table divider line missing: $lines", topDivider)
            assertTrue(
                "dividerY=${topDivider!!.startY} must strictly clear line2Bottom=$line2Bottom",
                topDivider.startY > line2Bottom,
            )
            assertTrue(
                "Expected >= 12px clearance between line2 baseline (${line2.y}) and divider (${topDivider.startY})",
                topDivider.startY >= line2.y + 12f,
            )
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun `table columns have strictly disjoint intervals and bottom row clears bottomLineY`() {
        val model = PairedComparisonShareCard.Model(
            categoryLabel = "AI 体验",
            leftBadge = "A · Wi-Fi",
            leftMetaLine = "07-09 08:00 · Node-E01",
            rightBadge = "B · 5G NR",
            rightMetaLine = "07-09 08:10 · Node-E01",
            headline = "Wi-Fi vs 5G NR · AQS 体验分 88.5 分 → 76.0 分（-12.5 分 (-14.1%) · 双行宽度测试）",
            rows = listOf(
                PairedComparisonShareCard.Model.Row("工具循环 P95 (E2E)", "12345.6 ms", "1000.00 Mbps", "+12345.6 ms (+999.9%)", PairedComparisonShareCard.DefaultPalette.REGRESSED_ARGB),
                PairedComparisonShareCard.Model.Row("ITL P95 (TPOT)", "1234.5 ms", "2345.6 ms", "+1111.1 ms (+90.0%)", PairedComparisonShareCard.DefaultPalette.REGRESSED_ARGB),
                PairedComparisonShareCard.Model.Row("下行排队增量", "1000.00 Mbps", "999.99 Mbps", "-0.01 Mbps (-0.0%)", PairedComparisonShareCard.DefaultPalette.NEUTRAL_ARGB),
                PairedComparisonShareCard.Model.Row("TTFT 首字延迟", "150.0 ms", "260.0 ms", "+110.0 ms (+73.3%)", PairedComparisonShareCard.DefaultPalette.REGRESSED_ARGB),
                PairedComparisonShareCard.Model.Row("上行吞吐", "42.50 Mbps", "21.00 Mbps", "-21.50 Mbps (-50.6%)", PairedComparisonShareCard.DefaultPalette.REGRESSED_ARGB),
                PairedComparisonShareCard.Model.Row("卡顿率", "0.50%", "2.00%", "+1.50 pp", PairedComparisonShareCard.DefaultPalette.REGRESSED_ARGB),
            ),
            footerNote = "应用层路径对比 · 非运营商全网评级",
        )

        val bmp = Bitmap.createBitmap(
            PairedComparisonShareCard.WIDTH,
            PairedComparisonShareCard.HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        val texts = mutableListOf<DrawnText>()
        val lines = mutableListOf<DrawnLine>()
        val canvas = object : Canvas(bmp) {
            override fun drawText(text: String, x: Float, y: Float, paint: android.graphics.Paint) {
                val fm = paint.fontMetrics
                texts += DrawnText(
                    text = text,
                    x = x,
                    y = y,
                    align = paint.textAlign,
                    width = paint.measureText(text),
                    descent = fm.descent,
                )
                super.drawText(text, x, y, paint)
            }

            override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: android.graphics.Paint) {
                lines += DrawnLine(startX, startY, stopX, stopY)
                super.drawLine(startX, startY, stopX, stopY, paint)
            }
        }

        try {
            PairedComparisonShareCard.drawTo(canvas, model)

            val bottomLine = lines.firstOrNull {
                it.startX == 72f && it.stopX == 1008f && it.startY == it.stopY && it.startY > 1100f
            }
            assertNotNull("Bottom divider line missing: $lines", bottomLine)
            val bottomLineY = bottomLine!!.startY

            val rowGroups = texts
                .filter { it.y in 530f..(bottomLineY - 1f) }
                .groupBy { it.y }
            assertEquals("Expected 6 KPI rows: $rowGroups", 6, rowGroups.size)

            rowGroups.forEach { (y, rowTexts) ->
                assertEquals("Expected 4 cells at y=$y: $rowTexts", 4, rowTexts.size)
                val metric = rowTexts[0]
                val colA = rowTexts[1]
                val colB = rowTexts[2]
                val colDelta = rowTexts[3]

                assertTrue(
                    "y=$y metricRight=${metric.right} must be < colALeft=${colA.left}",
                    metric.right < colA.left,
                )
                assertTrue(
                    "y=$y colARight=${colA.right} must be < colBLeft=${colB.left}",
                    colA.right < colB.left,
                )
                assertTrue(
                    "y=$y colBRight=${colB.right} must be < colDeltaLeft=${colDelta.left}",
                    colB.right < colDelta.left,
                )
                assertTrue(
                    "Row at y=$y (descent=${metric.descent}) must clear bottomLineY=$bottomLineY",
                    y + metric.descent < bottomLineY && y < bottomLineY - 24f,
                )
            }
        } finally {
            bmp.recycle()
        }
    }

    private data class DrawnText(
        val text: String,
        val x: Float,
        val y: Float,
        val align: android.graphics.Paint.Align,
        val width: Float,
        val descent: Float,
    ) {
        val left: Float get() = if (align == android.graphics.Paint.Align.RIGHT) x - width else x
        val right: Float get() = if (align == android.graphics.Paint.Align.RIGHT) x else x + width
    }

    private data class DrawnLine(
        val startX: Float,
        val startY: Float,
        val stopX: Float,
        val stopY: Float,
    )

    private class FakePendingImageGateway(
        private val updateFailure: IOException? = null,
    ) : PendingImageGateway {
        val ops = mutableListOf<String>()
        val itemUri: Uri = Uri.parse("content://media/external/images/media/101")
        val output = ByteArrayOutputStream()
        lateinit var insertCollection: Uri
        lateinit var insertValues: ContentValues
        lateinit var updateValues: ContentValues

        override fun insert(collection: Uri, values: ContentValues): Uri? {
            ops += "insert"
            insertCollection = collection
            insertValues = ContentValues(values)
            return itemUri
        }

        override fun openOutputStream(uri: Uri): OutputStream? {
            ops += "open"
            return output
        }

        override fun update(uri: Uri, values: ContentValues): Int {
            ops += "update"
            updateValues = ContentValues(values)
            updateFailure?.let { throw it }
            return 1
        }

        override fun delete(uri: Uri): Int {
            ops += "delete"
            return 1
        }
    }

    private class RecordingContext(
        base: Context,
        private val failure: RuntimeException? = null,
    ) : ContextWrapper(base) {
        val startedIntents = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            startedIntents += intent
            failure?.let { throw it }
        }
    }
}

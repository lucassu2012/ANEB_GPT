package com.aneb.probe.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.graphics.createBitmap
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException

internal interface PendingImageGateway {
    fun insert(collection: Uri, values: ContentValues): Uri?
    fun openOutputStream(uri: Uri): OutputStream?
    fun update(uri: Uri, values: ContentValues): Int
    fun delete(uri: Uri): Int
}

/**
 * 历史配对对比分享卡（F13）：`1080x1350` 离屏 Canvas PNG 渲染 + `Pictures/ANEB` `IS_PENDING`
 * MediaStore 写入 + `ACTION_SEND` `image/png` 分享。
 *
 * 零 Compose 依赖；纯展示投影（绝不重算 KPI），零 PII / GPS / 原始服务器 URL。
 */
object PairedComparisonShareCard {

    const val WIDTH: Int = 1080
    const val HEIGHT: Int = 1350

    object DefaultPalette {
        const val BG_TOP_ARGB: Int = 0xFF121A32.toInt()
        const val BG_BOTTOM_ARGB: Int = 0xFF080B18.toInt()
        const val CARD_ARGB: Int = 0xFF141C36.toInt()
        const val BORDER_ARGB: Int = 0xFF26334B.toInt()
        const val INK_ARGB: Int = 0xFFF3F6FA.toInt()
        const val MUTED_ARGB: Int = 0xFFA3ADBF.toInt()
        const val FAINT_ARGB: Int = 0xFF738094.toInt()
        const val BRAND_ARGB: Int = 0xFF43E1E6.toInt()
        const val IMPROVED_ARGB: Int = 0xFF59E493.toInt()
        const val REGRESSED_ARGB: Int = 0xFFEB718D.toInt()
        const val NEUTRAL_ARGB: Int = 0xFFA3ADBF.toInt()
        const val UNAVAILABLE_ARGB: Int = 0xFF7B8BA4.toInt()
    }

    data class Model(
        val categoryLabel: String,
        val leftBadge: String,
        val leftMetaLine: String,
        val rightBadge: String,
        val rightMetaLine: String,
        val headline: String,
        val rows: List<Row>,
        val footerNote: String,
    ) {
        data class Row(
            val label: String,
            val valueA: String,
            val valueB: String,
            val delta: String,
            val deltaColorArgb: Int,
        )
    }

    data class SaveOutcome(
        val ok: Boolean,
        val uri: Uri?,
        val error: String?,
    )

    fun toShareModel(result: PairedComparisonFormat.PairedComparisonResult.Compatible): Model {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
        val leftDate = fmt.format(Date(result.left.startedAtEpochMs))
        val rightDate = fmt.format(Date(result.right.startedAtEpochMs))
        return Model(
            categoryLabel = result.category.label,
            leftBadge = "A · ${result.left.transportLabel}",
            leftMetaLine = "$leftDate · ${result.left.nodeLabel}",
            rightBadge = "B · ${result.right.transportLabel}",
            rightMetaLine = "$rightDate · ${result.right.nodeLabel}",
            headline = result.headline,
            rows = result.rows.take(6).map { row ->
                Model.Row(
                    label = row.label,
                    valueA = row.formattedA,
                    valueB = row.formattedB,
                    delta = row.formattedDelta,
                    deltaColorArgb = when (row.tone) {
                        PairedComparisonFormat.DeltaTone.IMPROVED -> DefaultPalette.IMPROVED_ARGB
                        PairedComparisonFormat.DeltaTone.REGRESSED -> DefaultPalette.REGRESSED_ARGB
                        PairedComparisonFormat.DeltaTone.NEUTRAL -> DefaultPalette.NEUTRAL_ARGB
                        PairedComparisonFormat.DeltaTone.UNAVAILABLE -> DefaultPalette.UNAVAILABLE_ARGB
                    },
                )
            },
            footerNote = "应用层路径对比 · 非运营商全网评级",
        )
    }

    fun renderBitmap(model: Model): Bitmap {
        val bmp = createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        drawTo(Canvas(bmp), model)
        return bmp
    }

    internal fun drawTo(canvas: Canvas, model: Model) {
        val w = WIDTH.toFloat()
        val h = HEIGHT.toFloat()
        val margin = 72f

        canvas.drawColor(DefaultPalette.BG_BOTTOM_ARGB)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                w,
                h,
                DefaultPalette.BG_TOP_ARGB,
                DefaultPalette.BG_BOTTOM_ARGB,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.84f,
                h * 0.14f,
                w * 0.42f,
                Color.argb(46, 67, 225, 230),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(w * 0.84f, h * 0.14f, w * 0.42f, glowPaint)

        val boldType = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val regularType = Typeface.SANS_SERIF
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Header
        paint.typeface = boldType
        paint.textSize = 36f
        paint.letterSpacing = 0.08f
        paint.color = DefaultPalette.MUTED_ARGB
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("ANEB PROBE", margin, 102f, paint)

        paint.typeface = regularType
        paint.textSize = 24f
        paint.letterSpacing = 0.12f
        paint.color = DefaultPalette.FAINT_ARGB
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("PAIRED COMPARISON", w - margin, 102f, paint)

        paint.textAlign = Paint.Align.LEFT
        paint.letterSpacing = 0f
        paint.typeface = boldType
        paint.textSize = 26f
        paint.color = DefaultPalette.BRAND_ARGB
        canvas.drawText(model.categoryLabel, margin, 152f, paint)

        // Side-by-side A / B headers
        val boxTop = 186f
        val boxGap = 24f
        val boxW = (w - margin * 2f - boxGap) / 2f
        val boxH = 144f
        val leftRect = RectF(margin, boxTop, margin + boxW, boxTop + boxH)
        val rightRect = RectF(margin + boxW + boxGap, boxTop, w - margin, boxTop + boxH)

        drawHeaderBox(canvas, leftRect, model.leftBadge, model.leftMetaLine, DefaultPalette.BRAND_ARGB, boldType, regularType)
        drawHeaderBox(canvas, rightRect, model.rightBadge, model.rightMetaLine, DefaultPalette.IMPROVED_ARGB, boldType, regularType)

        // Headline banner
        val headlineTop = 360f
        paint.style = Paint.Style.FILL
        paint.typeface = boldType
        paint.textSize = 30f
        paint.color = DefaultPalette.INK_ARGB
        val nextY = drawWrapped(canvas, paint, model.headline, margin, headlineTop + 34f, w - margin * 2f, 44f, maxLines = 2)

        // Table divider & column headers
        val dividerY = maxOf(428f, nextY + 24f)
        val tableHeaderY = dividerY + 40f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = DefaultPalette.BORDER_ARGB
        canvas.drawLine(margin, dividerY, w - margin, dividerY, paint)

        val colMetricX = margin
        val colAX = margin + 390f
        val colBX = margin + 610f
        val colDeltaX = w - margin

        paint.style = Paint.Style.FILL
        paint.typeface = boldType
        paint.textSize = 22f
        paint.color = DefaultPalette.FAINT_ARGB
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("指标", colMetricX, tableHeaderY, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("A", colAX, tableHeaderY, paint)
        canvas.drawText("B", colBX, tableHeaderY, paint)
        canvas.drawText("Δ (B - A)", colDeltaX, tableHeaderY, paint)

        paint.style = Paint.Style.STROKE
        paint.color = DefaultPalette.BORDER_ARGB
        canvas.drawLine(margin, tableHeaderY + 18f, w - margin, tableHeaderY + 18f, paint)

        // Up to 6 tabular KPI rows
        val rows = model.rows.take(6)
        val rowStartY = tableHeaderY + 80f
        val rowStep = 100f
        rows.forEachIndexed { index, row ->
            val y = rowStartY + index * rowStep
            if (index > 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                paint.color = Color.argb(120, 38, 51, 75)
                canvas.drawLine(margin, y - 54f, w - margin, y - 54f, paint)
            }
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = boldType
            paint.textSize = 28f
            paint.color = DefaultPalette.INK_ARGB
            canvas.drawText(ellipsize(row.label, paint, 190f), colMetricX, y, paint)

            paint.textAlign = Paint.Align.RIGHT
            paint.typeface = regularType
            paint.textSize = 27f
            paint.color = DefaultPalette.MUTED_ARGB
            canvas.drawText(ellipsize(row.valueA, paint, 180f), colAX, y, paint)
            canvas.drawText(ellipsize(row.valueB, paint, 180f), colBX, y, paint)

            paint.typeface = boldType
            paint.textSize = 26f
            paint.color = row.deltaColorArgb
            canvas.drawText(ellipsize(row.delta, paint, 286f), colDeltaX, y, paint)
        }

        val bottomLineY = 1138f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = DefaultPalette.BORDER_ARGB
        canvas.drawLine(margin, bottomLineY, w - margin, bottomLineY, paint)

        // Footer
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = regularType
        paint.textSize = 26f
        paint.color = DefaultPalette.FAINT_ARGB
        canvas.drawText("由 ANEB Probe 在本机生成", margin, 1196f, paint)
        canvas.drawText(model.footerNote, margin, 1240f, paint)

        paint.textAlign = Paint.Align.RIGHT
        paint.typeface = boldType
        paint.textSize = 30f
        paint.color = DefaultPalette.MUTED_ARGB
        canvas.drawText("AN", w - margin, 1240f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawHeaderBox(
        canvas: Canvas,
        rect: RectF,
        badge: String,
        metaLine: String,
        accentArgb: Int,
        boldType: Typeface,
        regularType: Typeface,
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = DefaultPalette.CARD_ARGB
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = DefaultPalette.BORDER_ARGB
        }
        canvas.drawRoundRect(rect, 22f, 22f, fill)
        canvas.drawRoundRect(rect, 22f, 22f, stroke)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.LEFT
            typeface = boldType
            textSize = 32f
            color = accentArgb
        }
        val maxTextW = rect.width() - 48f
        canvas.drawText(ellipsize(badge, textPaint, maxTextW), rect.left + 24f, rect.top + 56f, textPaint)

        textPaint.typeface = regularType
        textPaint.textSize = 22f
        textPaint.color = DefaultPalette.MUTED_ARGB
        canvas.drawText(ellipsize(metaLine, textPaint, maxTextW), rect.left + 24f, rect.top + 104f, textPaint)
    }

    private fun drawWrapped(
        canvas: Canvas,
        paint: Paint,
        text: String,
        x: Float,
        startY: Float,
        maxWidth: Float,
        lineHeight: Float,
        maxLines: Int,
    ): Float {
        var line = StringBuilder()
        var cy = startY
        var lineCount = 0
        for (ch in text) {
            line.append(ch)
            if (paint.measureText(line.toString()) > maxWidth) {
                lineCount += 1
                val s = line.toString()
                if (lineCount >= maxLines) {
                    canvas.drawText(ellipsize(s, paint, maxWidth), x, cy, paint)
                    return cy
                }
                canvas.drawText(s.dropLast(1), x, cy, paint)
                line = StringBuilder(s.takeLast(1))
                cy += lineHeight
            }
        }
        if (line.isNotEmpty()) {
            canvas.drawText(line.toString(), x, cy, paint)
        }
        return cy
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end -= 1
        }
        return text.substring(0, end) + "…"
    }

    fun renderAndSave(context: Context, model: Model): SaveOutcome {
        val fileName = "aneb_compare_${System.currentTimeMillis()}.png"
        val bitmap = renderBitmap(model)
        return try {
            val outcome = saveBitmap(
                gateway = ContentResolverPendingImageGateway(context.contentResolver),
                bitmap = bitmap,
                fileName = fileName,
            )
            Log.i(
                "AnebProbe",
                "COMPARE_SHARE_SAVE ok=${outcome.ok} uri=${outcome.uri ?: "null"} err=${outcome.error ?: "none"}",
            )
            outcome
        } finally {
            bitmap.recycle()
        }
    }

    internal fun saveBitmap(
        gateway: PendingImageGateway,
        bitmap: Bitmap,
        fileName: String,
    ): SaveOutcome {
        var insertedUri: Uri? = null
        var published = false
        var primaryFailure: Throwable? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, PNG_MIME_TYPE)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ANEB")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = gateway.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw PendingImageFailure("mediastore_insert_null")
            insertedUri = uri
            val stream = gateway.openOutputStream(uri)
                ?: throw PendingImageFailure("open_output_stream_null")
            stream.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw PendingImageFailure("png_compress_failed")
                }
            }
            val publishValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            val publishCount = gateway.update(uri, publishValues)
            if (publishCount != 1) {
                throw PendingImageFailure("mediastore_publish_count_$publishCount")
            }
            published = true
            SaveOutcome(ok = true, uri = uri, error = null)
        } catch (cancellation: CancellationException) {
            primaryFailure = cancellation
            throw cancellation
        } catch (e: Exception) {
            primaryFailure = e
            SaveOutcome(
                ok = false,
                uri = null,
                error = (e as? PendingImageFailure)?.stableError ?: (e.message ?: e.javaClass.simpleName),
            )
        } finally {
            val uri = insertedUri
            if (uri != null && !published) {
                try {
                    gateway.delete(uri)
                } catch (cleanupFailure: Exception) {
                    primaryFailure?.takeUnless { it === cleanupFailure }?.addSuppressed(cleanupFailure)
                }
            }
        }
    }

    fun launchShare(context: Context, uri: Uri): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = PNG_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("ANEB Comparison", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享对比图").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private const val PNG_MIME_TYPE = "image/png"

    private class PendingImageFailure(
        val stableError: String,
    ) : IOException(stableError)

    private class ContentResolverPendingImageGateway(
        private val resolver: ContentResolver,
    ) : PendingImageGateway {
        override fun insert(collection: Uri, values: ContentValues): Uri? =
            resolver.insert(collection, values)

        override fun openOutputStream(uri: Uri): OutputStream? =
            resolver.openOutputStream(uri)

        override fun update(uri: Uri, values: ContentValues): Int =
            resolver.update(uri, values, null, null)

        override fun delete(uri: Uri): Int =
            resolver.delete(uri, null, null)
    }
}

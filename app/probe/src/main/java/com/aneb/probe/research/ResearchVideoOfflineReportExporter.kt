package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*

/** Local video report over saved imported values, never a media reader or measurement. Call on IO.
 * The caller owns output and must close it before publishing the completed export.
 */
class ResearchVideoOfflineReportExporter(
    private val records: ResearchRecordStore,
    private val analyses: ResearchAnalysisStore,
) {
    fun writeTo(sourceId: String, analysisId: String, output: OutputStream) {
        val source = records.open(sourceId)
        val analysis = analyses.open(source, analysisId)
        require(source.isVideo && analysis.isVideo) { "视频报告仅支持已保存的视频原文及匹配分析" }
        require(sameJsonValue(source.root, analysis.root["input_record"])) { "视频分析的完整原文副本不匹配；本次未改动原件" }
        val slots = source.records.map { it.attemptId to it.status }
        val rows = analysis.root["attempts"] as? JsonArray
        require(rows?.map { (it as? JsonObject)?.let { row -> row.text("slot") to row.text("status") } } == slots) {
            "视频分析的槽顺序或状态不匹配；本次未改动原件"
        }
        val expectedCounts = mapOf("planned" to slots.size, "executed" to slots.count { it.second == "EXECUTED" }, "not_run" to slots.count { it.second == "NOT_RUN" })
        val counts = analysis.root["counts"] as? JsonObject
        require(counts?.keys == expectedCounts.keys && expectedCounts.all { (key, expected) ->
            val value = counts?.get(key) as? JsonPrimitive
            value != null && !value.isString && value.content.matches(Regex("-?(0|[1-9][0-9]*)")) && value.content.toLongOrNull() == expected.toLong()
        }) { "视频分析的批次计数不匹配；本次未改动原件" }
        val entries = linkedMapOf(
            "source.json" to bytes { records.export(sourceId, it) },
            "analysis.json" to bytes { analyses.export(source, analysisId, it) },
        )
        entries["index.html"] = index(source, analysis, entries).toByteArray(Charsets.UTF_8)
        entries["SHA256SUMS.txt"] = entries.entries.joinToString("") { (name, content) ->
            sha256(content) + "  " + name + "\n"
        }.toByteArray(Charsets.UTF_8)
        // Finish the ZIP encoder; the existing Downloads publisher owns the real stream close.
        val encoderOutput = object : FilterOutputStream(output) {
            override fun close() = flush()
        }
        ZipOutputStream(encoderOutput, Charsets.UTF_8).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }

    private fun index(
        source: ResearchDocument,
        analysis: ResearchAnalysis,
        originals: Map<String, ByteArray>,
    ): String = buildString {
        append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">")
        append("<title>ANEB 私有离线视频研究报告</title>")
        append("<style>body{max-width:960px;margin:32px auto;padding:0 20px;font-family:system-ui,sans-serif;line-height:1.6;color:#17202b}pre{white-space:pre-wrap;overflow-wrap:anywhere}section{border-top:1px solid #ccd5df;padding:16px 0}code{overflow-wrap:anywhere}</style></head><body>")
        append("<h1>ANEB 私有离线视频研究报告 · " + escape(source.root.text("app") ?: "UNKNOWN") + "</h1>")
        append("<p>整批导出全部已保存槽，不应用界面筛选；不附媒体、不打开引用、不上传或分享。</p>")
        append("<p>" + escape(source.sourceLabel) + "</p><p>" + escape(source.sourceNotice) + "</p>")
        append("<p>输入声明非真实性认证；以下为导入的派生分析及原标注，未独立核验，不重新计算指标。</p>")
        append("<section><h2>来源绑定与原件</h2>")
        listOf("原文" to source.id, "分析" to analysis.id).forEach { (label, id) ->
            append("<p>" + label + " SHA-256：<code>" + escape(id) + "</code></p>")
        }
        originals.forEach { (name, content) ->
            append("<p><a href=\"" + name + "\">" + name + "</a> · " + content.size +
                " 字节 · SHA-256 <code>" + sha256(content) + "</code></p>")
        }
        append("<p><a href=\"SHA256SUMS.txt\">完整 SHA-256 清单</a>覆盖首页及两份原件，不包含清单自身；不是签名或真实性认证。</p></section>")
        append("<section><h2>导入视频分析 · 未独立核验</h2><pre>" + escape(analysis.summaryLines.joinToString("\n")) + "</pre></section>")
        source.records.forEach { attempt ->
            append("<section><h2>" + escape(attempt.attemptId) + " · " + escape(attempt.statusLabel) + "</h2>")
            append("<p>原 status：" + escape(attempt.status ?: "UNKNOWN") + "；目标播放声明：" +
                escape(attempt.raw.text("visible_target_playback") ?: "UNKNOWN") + "；窗口完整声明：" +
                escape(attempt.raw.text("window_complete") ?: "UNKNOWN") + "</p>")
            append("<pre>" + escape(analysis.attemptLines(attempt.attemptId).joinToString("\n")) + "</pre>")
            append("<h3>原始视频标注 · 仅文本，不打开媒体</h3><pre>" + escape(attempt.raw.toString()) + "</pre></section>")
        }
        append("<section><h2>范围与边界</h2><p>NOT_RUN 不计播放失败；已执行不代表成功。窗口完整只反映输入标注，不是软件验证连续播放。缓冲/暂停未计算，不补零或点值、不计算成功率或视频 RPI，不作网络归因。</p></section>")
        append("</body></html>")
    }

    /** Match existing CLI JSON loading: integers stay exact; fractional/exponent tokens use binary64. */
    private fun sameJsonValue(left: JsonElement?, right: JsonElement?): Boolean = when {
        left is JsonObject && right is JsonObject -> left.keys == right.keys && left.all { (key, value) -> sameJsonValue(value, right[key]) }
        left is JsonArray && right is JsonArray -> left.size == right.size && left.indices.all { sameJsonValue(left[it], right[it]) }
        left is JsonPrimitive && right is JsonPrimitive -> {
            if (left.isString || right.isString) left.isString == right.isString && left.content == right.content
            else {
                val a = cliNumber(left)
                val b = cliNumber(right)
                if (a != null && b != null) a.compareTo(b) == 0 else left == right
            }
        }
        else -> left == right
    }

    private fun cliNumber(value: JsonPrimitive): BigDecimal? {
        val token = value.content
        if (token.indexOfAny(charArrayOf('.', 'e', 'E')) < 0) return token.toBigDecimalOrNull()
        val floating = token.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        // Exact value of that binary64 number, so a large integer is not rounded for comparison.
        return BigDecimal(floating)
    }

    private fun bytes(writer: (OutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also { writer(it) }.toByteArray()

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

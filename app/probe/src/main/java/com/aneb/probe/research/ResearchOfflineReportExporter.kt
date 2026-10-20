package com.aneb.probe.research

import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Local ZIP writer for an explicitly selected saved text research set. Call on IO.
 * The caller owns output and must close it before publishing the completed export.
 */
class ResearchOfflineReportExporter(
    private val records: ResearchRecordStore,
    private val analyses: ResearchAnalysisStore,
    private val conclusions: ResearchConclusionStore,
) {
    fun writeTo(sourceId: String, analysisId: String, conclusionId: String, output: OutputStream) {
        val source = records.open(sourceId)
        val analysis = analyses.open(source, analysisId)
        val conclusion = conclusions.open(source, analysis, conclusionId)
        val entries = linkedMapOf(
            "source.json" to bytes { records.export(sourceId, it) },
            "analysis.json" to bytes { analyses.export(source, analysisId, it) },
            "conclusion.md" to bytes { conclusions.export(source, analysis, conclusionId, it) },
        )
        entries["index.html"] = index(source, analysis, conclusion, entries).toByteArray(Charsets.UTF_8)
        entries["SHA256SUMS.txt"] = entries.entries.joinToString("") { (name, content) ->
            sha256(content) + "  " + name + "\n"
        }.toByteArray(Charsets.UTF_8)
        // Finish/close the ZIP encoder, but let the existing Downloads publisher close output.
        val ownedEncoderOutput = object : FilterOutputStream(output) {
            override fun close() = flush()
        }
        ZipOutputStream(ownedEncoderOutput, Charsets.UTF_8).use { zip ->
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
        conclusion: ResearchConclusion,
        originals: Map<String, ByteArray>,
    ): String = buildString {
        append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">")
        append("<title>ANEB 私有离线研究报告</title>")
        append("<style>body{max-width:960px;margin:32px auto;padding:0 20px;font-family:system-ui,sans-serif;line-height:1.6;color:#17202b}pre{white-space:pre-wrap;overflow-wrap:anywhere}section{border-top:1px solid #ccd5df;padding:16px 0}code{overflow-wrap:anywhere}</style></head><body>")
        append("<h1>ANEB 私有离线研究报告 · " + escape(conclusion.appName) + "</h1>")
        append("<p>整批导出；不应用界面筛选，不附媒体，不重新分析或计算科学指标。</p>")
        append("<p>" + escape(source.sourceLabel) + "</p><p>" + escape(source.sourceNotice) + "</p>")
        append("<p>输入声明非真实性认证；以下为已保存分析与人工原文，不作网络归因。</p>")
        append("<section><h2>来源绑定与原件</h2>")
        listOf("原文" to source.id, "分析" to analysis.id, "人工结论" to conclusion.id).forEach { (label, id) ->
            append("<p>" + label + " SHA-256：<code>" + escape(id) + "</code></p>")
        }
        originals.forEach { (name, content) ->
            append("<p><a href=\"" + name + "\">" + name + "</a> · " + content.size +
                " 字节 · SHA-256 <code>" + sha256(content) + "</code></p>")
        }
        append("<p><a href=\"SHA256SUMS.txt\">完整 SHA-256 清单</a>覆盖首页和三份原件，不包含清单自身。</p></section>")
        append("<section><h2>导入分析 · 未独立核验</h2><pre>" + escape(analysis.summaryLines.joinToString("\n")) + "</pre></section>")
        append("<section><h2>导入的分组显示 · 未重新分组</h2><pre>" + escape(analysis.groupLines.joinToString("\n")) + "</pre></section>")
        source.records.forEach { attempt ->
            val app = attempt.raw["app"] as? JsonObject
            val outcome = attempt.raw["outcome"] as? JsonObject
            append("<section><h2>" + escape(attempt.attemptId) + "</h2>")
            append("<p>App：" + escape(app?.text("name") ?: "UNKNOWN") + "；版本：" +
                escape(app?.text("version") ?: "UNKNOWN") + "；模式：" +
                escape(app?.text("model_mode") ?: "UNKNOWN") + "；条件：" +
                escape(attempt.raw.text("condition") ?: "UNKNOWN") + "</p>")
            append("<p>来源状态：" + escape(attempt.status ?: "UNKNOWN") + " · " + escape(attempt.statusLabel) + "</p>")
            attempt.statusNotice?.let { append("<p>" + escape(it) + "</p>") }
            append("<p>源标注 executed：" + escape(originalValue(outcome?.get("executed"))) +
                "；visible_completion：" + escape(originalValue(outcome?.get("visible_completion"))) +
                "；instruction_following：" + escape(originalValue(outcome?.get("instruction_following"))) + "</p>")
            append("<p>可见完成与指令满足是不同原标注，也不等于有效完成时长或 App 成功率；本报告不重新判定。</p>")
            append("<pre>" + escape(analysis.attemptLines(attempt.attemptId).joinToString("\n")) + "</pre></section>")
        }
        append("<section><h2>人工结论 · 原文与边界</h2><pre>" + escape(conclusion.text) + "</pre></section>")
        append("</body></html>")
    }

    private fun bytes(writer: (OutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also { writer(it) }.toByteArray()

    private fun originalValue(value: JsonElement?): String = when (value) {
        null, JsonNull -> "NA（未记录或不适用）"
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

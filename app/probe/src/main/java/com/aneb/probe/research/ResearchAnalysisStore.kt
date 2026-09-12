package com.aneb.probe.research

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Imported 3a output, not Android-computed or independently verified measurements. */
data class ResearchAnalysis(val id: String, val sourceId: String, val root: JsonObject) {
    val records: Map<String, JsonObject> get() = root["records"]!!.jsonArray.associate {
        val row = it.jsonObject
        row.text("attempt_id")!! to row
    }
    val exportFileName: String get() = "ANEB-R1-analysis-${root.text("record_kind")}-${id}.json"
}

data class ResearchAnalysisEntry(val id: String, val analysis: ResearchAnalysis?, val error: String?)

/** Separate hash-addressed copies per original; no raw records, media, or Room are modified. */
class ResearchAnalysisStore(private val directory: File) {
    fun save(source: ResearchDocument, bytes: ByteArray): ResearchAnalysis {
        val snapshot = bytes.copyOf()
        val analysis = decode(source, snapshot)
        val target = file(source.id, analysis.id)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        if (target.exists()) {
            check(target.readBytes().contentEquals(snapshot)) { "已有分析副本损坏，未覆盖" }
            return analysis
        }
        val pending = File.createTempFile("analysis-", ".pending", target.parentFile)
        try {
            FileOutputStream(pending).use { it.write(snapshot); it.fd.sync() }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { pending.delete() }
        return analysis
    }

    fun open(source: ResearchDocument, id: String): ResearchAnalysis = decode(source, read(source.id, id))

    fun list(source: ResearchDocument): List<ResearchAnalysisEntry> {
        val parent = file(source.id, source.id).parentFile!!
        if (!parent.exists()) return emptyList()
        return checkNotNull(parent.listFiles()).filter { it.extension == "json" }
            .sortedByDescending { it.lastModified() }.map {
                try { ResearchAnalysisEntry(it.nameWithoutExtension, open(source, it.nameWithoutExtension), null) }
                catch (_: Exception) { ResearchAnalysisEntry(it.nameWithoutExtension, null, "分析副本无法读取；原文与副本仍保留") }
            }
    }

    fun export(source: ResearchDocument, id: String, output: OutputStream) {
        val bytes = read(source.id, id)
        decode(source, bytes)
        output.write(bytes)
    }

    private fun read(sourceId: String, id: String): ByteArray {
        val target = file(sourceId, id)
        require(target.length() <= ResearchRecordStore.MAX_BYTES)
        return target.readBytes().also { require(hash(it) == id) { "分析副本完整性检查失败" } }
    }

    private fun file(sourceId: String, id: String): File {
        require(listOf(sourceId, id).all { it.matches(Regex("[0-9a-f]{64}")) })
        return File(File(directory, sourceId), "$id.json")
    }

    companion object {
        fun decode(source: ResearchDocument, bytes: ByteArray): ResearchAnalysis {
            try {
                require(bytes.size <= ResearchRecordStore.MAX_BYTES)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                // Local resource budget only; calculations and research rules remain owned by 3a.
                var depth = 0
                var quoted = false
                var escaped = false
                for (c in text) {
                    if (quoted) {
                        if (escaped) escaped = false
                        else if (c == '\\') escaped = true
                        else if (c == '"') quoted = false
                    } else when (c) {
                        '"' -> quoted = true
                        '{', '[' -> { depth++; require(depth <= 64) }
                        '}', ']' -> depth--
                    }
                }
                val root = Json.parseToJsonElement(text) as? JsonObject ?: error("分析需要对象")
                require(root.text("source_sha256") == source.id) { "原文 SHA-256 不匹配" }
                require(root.text("record_kind") == source.recordKind) { "来源类型不匹配" }
                require(root.text("input_revision") == "alignment-1") { "不支持的分析输入版本" }
                val rows = root["records"] as? JsonArray ?: error("缺少分析记录")
                val originals = source.records.associateBy { it.attemptId }
                val seen = mutableSetOf<String>()
                rows.forEach { element ->
                    val row = element as? JsonObject ?: error("分析记录需要对象")
                    val id = row.text("attempt_id") ?: error("缺少尝试 ID")
                    require(seen.add(id)) { "分析尝试 ID 重复" }
                    require(id in originals) { "分析包含额外尝试" }
                    val context = row["input_record"] as? JsonObject ?: error("缺少原始记录上下文")
                    // Bind identities, not JSON numeric spellings rewritten by the producer.
                    require(context.text("attempt_id") == id && context.text("record_kind") == source.recordKind) {
                        "分析携带的原始记录身份不匹配"
                    }
                }
                require(seen == originals.keys) { "分析缺少尝试" }
                require(root["counts"] is JsonObject && root["groups"] is JsonArray) { "缺少分析汇总" }
                return ResearchAnalysis(hash(bytes), source.id, root)
            } catch (e: Exception) {
                throw ResearchImportException("无法关联分析：请核对原文 SHA-256、来源类型及完整且唯一的 attempt_id；仅支持 alignment-1 分析（最多 1 MiB）。整份未保存，原文不变。")
            }
        }

        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

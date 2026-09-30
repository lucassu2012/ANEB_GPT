package com.aneb.probe.research

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** A local, human-authored note, bound to imported bytes; not an ANEB measurement or authenticity claim. */
data class ResearchConclusion(
    val id: String,
    val sourceId: String,
    val analysisId: String,
    val appName: String,
    val text: String,
)

/** Listing metadata only; the selected card is validated and read separately. */
data class ResearchConclusionEntry(val id: String, val error: String?)

/** Private Markdown copies. The original record and imported analysis are never rewritten. */
class ResearchConclusionStore(private val directory: File) {
    /** UI eligibility only; the import still revalidates the selected bytes before saving. */
    fun canAttach(source: ResearchDocument, analysis: ResearchAnalysis): Boolean = try {
        matchedAppName(source, analysis)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Validate an import before confirmation; neither the card nor its directory is created. */
    fun preview(source: ResearchDocument, analysis: ResearchAnalysis, bytes: ByteArray): ResearchConclusion =
        decode(source, analysis, bytes.copyOf())

    fun save(source: ResearchDocument, analysis: ResearchAnalysis, bytes: ByteArray): ResearchConclusion {
        val snapshot = bytes.copyOf()
        val card = decode(source, analysis, snapshot)
        val target = file(source.id, analysis.id, card.id)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法创建结论卡目录" }
        if (target.exists()) {
            check(target.readBytes().contentEquals(snapshot)) { "已有结论卡损坏，未覆盖" }
            return card
        }
        val pending = File.createTempFile("conclusion-", ".pending", target.parentFile)
        try {
            FileOutputStream(pending).use { it.write(snapshot); it.fd.sync() }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { pending.delete() }
        return card
    }

    fun open(source: ResearchDocument, analysis: ResearchAnalysis, id: String): ResearchConclusion {
        val target = file(source.id, analysis.id, id)
        require(target.length() <= MAX_BYTES) { "结论卡超过 1 MiB" }
        val bytes = target.readBytes()
        require(hash(bytes) == id) { "结论卡完整性检查失败" }
        return decode(source, analysis, bytes)
    }

    fun list(source: ResearchDocument, analysis: ResearchAnalysis): List<ResearchConclusionEntry> {
        val parent = file(source.id, analysis.id, analysis.id).parentFile!!
        if (!parent.exists()) return emptyList()
        return checkNotNull(parent.listFiles()) { "无法读取结论卡目录" }
            .filter { it.extension == "md" }.sortedByDescending { it.lastModified() }.map { candidate ->
                val id = candidate.nameWithoutExtension
                ResearchConclusionEntry(id, if (id.matches(lowercaseSha) && candidate.isFile) null else "无效的结论卡条目；本次未删除文件")
            }
    }

    private fun decode(source: ResearchDocument, analysis: ResearchAnalysis, bytes: ByteArray): ResearchConclusion {
        require(bytes.size <= MAX_BYTES) { "结论卡超过 1 MiB" }
        val appName = matchedAppName(source, analysis)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(analysisShaTokens.findAll(text).any { it.value.equals(analysis.id, ignoreCase = true) }) {
            "结论卡未包含所选分析的完整 SHA-256"
        }
        return ResearchConclusion(hash(bytes), source.id, analysis.id, appName, text)
    }

    private fun matchedAppName(source: ResearchDocument, analysis: ResearchAnalysis): String {
        require(!source.isVideo && !analysis.isVideo && analysis.sourceId == source.id) { "结论卡仅支持对应的文本原文与分析" }
        require(source.recordKind == analysis.root.text("record_kind")) { "来源类型不匹配" }
        val names = source.records.map { (it.raw["app"] as? JsonObject)?.text("name") }
        val appName = names.firstOrNull()
        require(!appName.isNullOrBlank() && names.all { it == appName }) { "仅支持单 App 来源" }
        val rows = analysis.root["records"] as? JsonArray
        require(!rows.isNullOrEmpty() && rows.size == source.records.size && rows.all { element ->
            val context = (element as? JsonObject)?.get("input_record") as? JsonObject
            (context?.get("app") as? JsonObject)?.text("name") == appName
        }) { "分析与单 App 来源不匹配" }
        return appName
    }

    private fun file(sourceId: String, analysisId: String, id: String): File {
        require(listOf(sourceId, analysisId, id).all { it.matches(lowercaseSha) }) { "无效结论卡标识" }
        return File(File(File(directory, sourceId), analysisId), "$id.md")
    }

    companion object {
        private const val MAX_BYTES = 1024 * 1024
        private val lowercaseSha = Regex("[0-9a-f]{64}")
        private val analysisShaTokens = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])")
        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

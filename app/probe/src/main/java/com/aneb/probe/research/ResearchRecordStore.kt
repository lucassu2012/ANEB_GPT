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

/** R1 display projection only. Original input remains the evidence; no derived metrics. */
data class ResearchAttempt(val raw: JsonObject) {
    val attemptId: String get() = raw.text("attempt_id") ?: "UNKNOWN"
    val status: String? get() = (raw["outcome"] as? JsonObject)?.text("status")
    val statusLabel: String get() = when (status) {
        "completed" -> "记录为完成"
        "failed" -> "失败"
        "cancelled" -> "已取消"
        "incomplete" -> "未完成"
        "not_run" -> "未执行"
        else -> "未知：${status ?: "NA"}"
    }
    val sourceWarnings: List<String> get() = buildList {
        if (raw.text("record_kind") == "OBSERVED") {
            val evidence = raw["evidence"] as? JsonObject
            if (evidence?.text("local_ref").isNullOrBlank()) {
                add("缺少本地来源引用，请人工核对来源；原记录仍保留。")
            }
            if (evidence?.text("sha256").isNullOrBlank()) {
                add("媒体哈希未记录或不适用；请查看缺失原因，不补造哈希，未执行项无需伪造录像。")
            }
        }
    }
}

data class ResearchDocument(val id: String, val root: JsonObject, val records: List<ResearchAttempt>) {
    val recordKind: String get() = root.text("record_kind") ?: "UNKNOWN"
    val sourceLabel: String get() = when (recordKind) {
        "SAMPLE" -> "SAMPLE · 虚构样例（非实测）"
        "OBSERVED" -> "OBSERVED · 输入声明为实际观察（未核验）"
        else -> "未确认来源"
    }
    val sourceNotice: String get() = if (recordKind == "OBSERVED") {
        "实际观察仅为输入声明，不表示成功或验收通过；本工具未打开媒体、未核验本地来源或媒体哈希，不能据此归因为网络问题。"
    } else {
        "虚构样例仅用于演示，不计作实际观察或实测结果。"
    }
    val exportFileName: String get() = "ANEB-R1-$recordKind-$id.json"
}
data class ResearchEntry(val id: String, val document: ResearchDocument?, val error: String?)

/** Safe, actionable input error: no raw record contents or external paths in its message. */
class ResearchImportException(message: String) : IllegalArgumentException(message)

internal fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** App-private raw documents, independent of Room/AQS/Prototype evidence. Call on IO dispatcher. */
class ResearchRecordStore(private val directory: File) {
    fun save(bytes: ByteArray): ResearchDocument {
        val snapshot = bytes.copyOf()
        val document = decode(snapshot)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建研究记录目录" }
        val target = file(document.id)
        if (target.exists()) {
            check(target.readBytes().contentEquals(snapshot)) { "已有记录损坏，未覆盖" }
            return document
        }
        val pending = File.createTempFile("import-", ".pending", directory)
        try {
            FileOutputStream(pending).use { it.write(snapshot); it.fd.sync() }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            pending.delete()
        }
        return document
    }

    fun open(id: String): ResearchDocument = decode(read(id))

    fun list(): List<ResearchEntry> {
        if (!directory.exists()) return emptyList()
        val files = checkNotNull(directory.listFiles()) { "无法读取研究记录目录" }
        return files.filter { it.extension == "json" }.sortedByDescending { it.lastModified() }.map {
            try { ResearchEntry(it.nameWithoutExtension, open(it.nameWithoutExtension), null) }
            catch (_: Exception) { ResearchEntry(it.nameWithoutExtension, null, "记录无法读取；原文件保留") }
        }
    }

    fun export(id: String, output: OutputStream) {
        val bytes = read(id)
        decode(bytes)
        output.write(bytes)
    }

    private fun read(id: String): ByteArray {
        val source = file(id)
        require(source.length() <= MAX_BYTES) { "研究记录超过 1 MiB" }
        val bytes = source.readBytes()
        require(hash(bytes) == id) { "研究记录完整性检查失败" }
        return bytes
    }

    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f]{64}"))) { "无效研究记录标识" }
        return File(directory, "$id.json")
    }

    companion object {
        const val MAX_BYTES = 1024 * 1024

        fun decode(bytes: ByteArray): ResearchDocument {
            require(bytes.size <= MAX_BYTES) { "研究记录超过 1 MiB" }
            try {
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                // Local import resource budget, not a research/schema claim.
                var depth = 0
                var quoted = false
                var escaped = false
                for (character in text) {
                    if (quoted) {
                        if (escaped) escaped = false
                        else if (character == '\\') escaped = true
                        else if (character == '"') quoted = false
                    } else when (character) {
                        '"' -> quoted = true
                        '{', '[' -> { depth++; require(depth <= 64) { "JSON 嵌套过深" } }
                        '}', ']' -> depth--
                    }
                }
                val root = Json.parseToJsonElement(text) as? JsonObject
                    ?: throw IllegalArgumentException("需要批量 JSON 对象")
                val kind = root.text("record_kind")
                if (kind != "SAMPLE" && kind != "OBSERVED") {
                    throw ResearchImportException("来源类型未确认：仅支持 SAMPLE 或 OBSERVED，不自动转换。请核对原输入文件。")
                }
                require(!root.text("method_id").isNullOrBlank()) { "缺少 method_id" }
                val rows = root["records"] as? JsonArray ?: throw IllegalArgumentException("缺少 records 数组")
                require(rows.isNotEmpty()) { "records 为空" }
                val seenIds = mutableMapOf<String, Int>()
                val records = rows.mapIndexed { index, it ->
                    val raw = it as? JsonObject ?: throw IllegalArgumentException("记录不是对象")
                    if (raw.text("record_kind") != kind) {
                        throw ResearchImportException("第 ${index + 1} 条来源类型与批次不一致或缺失；SAMPLE 和 OBSERVED 必须分别导入纯批次。整批未保存，请核对原输入。")
                    }
                    val attemptId = raw.text("attempt_id")
                    if (attemptId.isNullOrBlank()) throw ResearchImportException("第 ${index + 1} 条缺少 attempt_id；整批未保存。")
                    val previous = seenIds[attemptId]
                    if (previous != null) throw ResearchImportException("第 ${index + 1} 条与第 $previous 条 attempt_id 重复；整批未保存，不自动去重或编号。")
                    seenIds[attemptId] = index + 1
                    ResearchAttempt(raw)
                }
                return ResearchDocument(hash(bytes), root, records)
            } catch (e: ResearchImportException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("无法导入：仅支持 UTF-8 R1 SAMPLE 或 OBSERVED 纯批次，且尝试 ID 不重复", e)
            }
        }

        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

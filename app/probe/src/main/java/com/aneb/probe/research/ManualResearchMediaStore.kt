package com.aneb.probe.research

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** App-private attachments for explicitly selected originals of saved manual research. */
class ManualResearchMediaStore(
    private val directory: File,
    private val records: ResearchRecordStore,
) {
    fun save(sourceId: String, attemptId: String, input: InputStream): File {
        val declaration = declaration(sourceId, attemptId)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建人工研究原片目录" }
        val target = file(sourceId, attemptId)
        val pending = File.createTempFile("manual-media-", ".pending", directory)
        try {
            FileOutputStream(pending).use { output ->
                verify(input, declaration, output)
                output.fd.sync()
            }
            if (target.exists()) return open(sourceId, attemptId)
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            pending.delete()
        }
        return open(sourceId, attemptId)
    }

    fun open(sourceId: String, attemptId: String): File {
        val declaration = declaration(sourceId, attemptId)
        return file(sourceId, attemptId).also { local ->
            require(local.isFile && !Files.isSymbolicLink(local.toPath())) { "本机原片缺失" }
            local.inputStream().use { verify(it, declaration) }
        }
    }

    private data class Declaration(val sha256: String, val bytes: Long?)

    private fun declaration(sourceId: String, attemptId: String): Declaration {
        val source = records.open(sourceId)
        require(!source.isVideo && source.recordKind == "OBSERVED") { "仅支持已保存的人工 OBSERVED 记录" }
        val attempt = source.records.singleOrNull { it.attemptId == attemptId }
            ?: throw IllegalArgumentException("没有对应的人工尝试记录")
        val evidence = attempt.raw["evidence"] as? JsonObject
            ?: throw IllegalArgumentException("原记录缺少媒体声明")
        val sha = evidence.text("sha256")
        require(sha != null && sha.matches(Regex("[0-9a-fA-F]{64}"))) { "原记录未声明完整媒体 SHA-256；不能关联原片" }
        val size = evidence["bytes"]?.takeUnless { it == JsonNull }?.let {
            val number = it as? JsonPrimitive
            require(number != null && !number.isString && number.content.matches(Regex("0|[1-9][0-9]*"))) { "原记录媒体大小声明无效" }
            requireNotNull(number.longOrNull).also { count -> require(count in 0..MAX_BYTES) { "原片超过 64 MiB 本机预算" } }
        }
        return Declaration(sha.lowercase(), size)
    }

    private fun file(sourceId: String, attemptId: String): File =
        File(directory, "$sourceId-${hex(MessageDigest.getInstance("SHA-256").digest(attemptId.toByteArray(Charsets.UTF_8)))}.mp4")

    private fun verify(input: InputStream, declaration: Declaration, output: OutputStream? = null) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            if (size == 0) continue
            count += size
            require(count <= MAX_BYTES && (declaration.bytes == null || count <= declaration.bytes)) { "原片大小超出声明或本机预算" }
            output?.write(buffer, 0, size)
            digest.update(buffer, 0, size)
        }
        require((declaration.bytes == null || count == declaration.bytes) && hex(digest.digest()) == declaration.sha256) {
            "原片大小或 SHA-256 不匹配；未确认关联"
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}

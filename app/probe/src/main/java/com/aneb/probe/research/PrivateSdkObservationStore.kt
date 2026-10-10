package com.aneb.probe.research

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A private input copy, not a measurement, mode verdict, or action permission. */
class PrivateSdkObservationDocument(
    val id: String,
    val root: JsonObject,
    bytes: ByteArray,
) {
    private val snapshot = bytes.copyOf()
    val byteCount: Int get() = snapshot.size
    fun originalBytes(): ByteArray = snapshot.copyOf()
}

/** Local private observation copies, kept outside R1, analysis, Room, and formal evidence. */
class PrivateSdkObservationStore(private val directory: File) {
    fun preview(bytes: ByteArray): PrivateSdkObservationDocument = decode(bytes.copyOf())

    fun list(): List<PrivateSdkObservationEntry> {
        if (!directory.exists()) return emptyList()
        val files = checkNotNull(directory.listFiles()) { "无法读取私有观察列表" }
        return files.filter { it.extension == "json" }.sortedByDescending { it.lastModified() }.map {
            try { PrivateSdkObservationEntry(it.nameWithoutExtension, open(it.nameWithoutExtension).byteCount, null) }
            catch (_: Exception) { PrivateSdkObservationEntry(it.nameWithoutExtension, null, "副本无法读取；原文件保留") }
        }
    }

    fun save(bytes: ByteArray): PrivateSdkObservationDocument {
        val document = decode(bytes.copyOf())
        check(directory.isDirectory || directory.mkdirs()) { "无法创建私有观察目录" }
        val target = file(document.id)
        val snapshot = document.originalBytes()
        if (target.exists()) {
            require(target.length() <= ResearchRecordStore.MAX_BYTES) { "已有私有副本超过 1 MiB；未覆盖" }
            check(target.readBytes().contentEquals(snapshot)) { "已有私有副本损坏，未覆盖" }
            return document
        }
        val pending = File.createTempFile("sdk-", ".pending", directory)
        try {
            FileOutputStream(pending).use { it.write(snapshot); it.fd.sync() }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { pending.delete() }
        return document
    }

    fun open(id: String): PrivateSdkObservationDocument {
        val source = file(id)
        require(source.length() <= ResearchRecordStore.MAX_BYTES) { "私有观察超过 1 MiB" }
        val bytes = source.readBytes()
        require(hash(bytes) == id) { "私有副本完整性检查失败" }
        return decode(bytes)
    }

    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f]{64}"))) { "无效私有观察标识" }
        return File(directory, "$id.json")
    }

    private fun decode(bytes: ByteArray): PrivateSdkObservationDocument {
        require(bytes.size <= ResearchRecordStore.MAX_BYTES) { "私有观察超过 1 MiB" }
        try {
            val root = Json.parseToJsonElement(validatedResearchInputText(bytes)) as? JsonObject
                ?: throw IllegalArgumentException()
            require(root.text("classification") == "PRIVATE_ENGINEERING_SDK_LITERAL_OBSERVATION_NOT_FORMAL_RESULT")
            val objects = root["objects"] as? JsonObject ?: throw IllegalArgumentException()
            require(listOf("deep_label", "deep_parent", "search_label", "search_parent").all { objects[it] is JsonObject })
            require(listOf("literal_fields", "sdk_clock", "limits").all { root[it] is JsonObject })
            return PrivateSdkObservationDocument(hash(bytes), root, bytes)
        } catch (_: Exception) {
            throw IllegalArgumentException("无法读取：需要 UTF-8 私有 SDK 观察对象及四个 objects、literal_fields、sdk_clock、limits；原文件未修改")
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** List metadata only: opening reads a fresh document instead of retaining every JSON tree. */
data class PrivateSdkObservationEntry(val id: String, val byteCount: Int?, val error: String?)

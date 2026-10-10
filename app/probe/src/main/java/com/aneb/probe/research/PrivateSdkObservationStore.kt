package com.aneb.probe.research

import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A private input copy, not a measurement, mode verdict, or action permission. */
class PrivateSdkObservationDocument(
    val id: String,
    val root: JsonObject,
    bytes: ByteArray,
    val image: PrivateSdkObservationImage? = null,
    val requiresImage: Boolean = false,
) {
    private val snapshot = bytes.copyOf()
    val byteCount: Int get() = snapshot.size
    fun originalBytes(): ByteArray = snapshot.copyOf()
}

/** Local private observation copies, kept outside R1, analysis, Room, and formal evidence. */
class PrivateSdkObservationStore(private val directory: File) {
    fun preview(bytes: ByteArray): PrivateSdkObservationDocument = decode(bytes.copyOf())

    fun attachPng(document: PrivateSdkObservationDocument, bytes: ByteArray): PrivateSdkObservationDocument {
        require(bytes.size in 8..PrivateSdkObservationImage.MAX_BYTES) { "PNG 超过 8 MiB 或为空" }
        require(bytes.take(8).toByteArray().contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))) { "需要 PNG 原文件" }
        val source = (document.root["visible_witness"] as? JsonObject)?.get("original_source") as? JsonObject
            ?: throw IllegalArgumentException("输入未声明原图大小与 SHA")
        val declaredSize = source["bytes"] as? JsonPrimitive
        val declaredSha = source["sha256"] as? JsonPrimitive
        val actualSha = hash(bytes)
        require(declaredSize != null && !declaredSize.isString && declaredSize.content == bytes.size.toString()) { "图片大小与声明不符" }
        require(declaredSha?.isString == true &&
            (declaredSha.content == actualSha || declaredSha.content == actualSha.uppercase())) { "图片 SHA 与声明不符" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType == "image/png" && bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 4_000_000L) { "PNG 解码尺寸超过边界" }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "PNG 无法解码" }
        try { require(bitmap.width == bounds.outWidth && bitmap.height == bounds.outHeight) { "PNG 解码尺寸不一致" } }
        finally { bitmap.recycle() }
        return PrivateSdkObservationDocument(document.id, document.root, document.originalBytes(),
            PrivateSdkObservationImage(bytes, actualSha, bounds.outWidth, bounds.outHeight), document.requiresImage)
    }

    fun list(): List<PrivateSdkObservationEntry> {
        if (!directory.exists()) return emptyList()
        val files = checkNotNull(directory.listFiles()) { "无法读取私有观察列表" }
        return files.filter { it.extension == "json" || (it.isDirectory && it.extension == "bundle") }
            .sortedByDescending { it.lastModified() }.distinctBy { it.nameWithoutExtension }.map {
            try { PrivateSdkObservationEntry(it.nameWithoutExtension, open(it.nameWithoutExtension).byteCount, null) }
            catch (_: Exception) { PrivateSdkObservationEntry(it.nameWithoutExtension, null, "副本无法读取；原文件保留") }
        }
    }

    fun save(bytes: ByteArray): PrivateSdkObservationDocument {
        val document = decode(bytes.copyOf())
        require(!document.requiresImage) { "历史图片观察须先匹配 PNG，再确认保存" }
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

    /** One visible commit for the pair; staging directories are never listed as observations. */
    fun save(document: PrivateSdkObservationDocument): PrivateSdkObservationDocument {
        val image = document.image ?: return save(document.originalBytes()).let { open(it.id) }
        val checked = attachPng(decode(document.originalBytes()), image.originalBytes())
        check(directory.isDirectory || directory.mkdirs()) { "无法创建私有观察目录" }
        val target = file(checked.id, "bundle")
        if (target.exists()) {
            val existing = open(checked.id)
            check(existing.originalBytes().contentEquals(checked.originalBytes()) &&
                existing.image?.originalBytes()?.contentEquals(image.originalBytes()) == true) { "已有图片关联损坏，未覆盖" }
            return existing
        }
        val pending = Files.createTempDirectory(directory.toPath(), "sdk-pair-").toFile()
        try {
            writeSynced(File(pending, "original.json"), checked.originalBytes())
            writeSynced(File(pending, "original.png"), checked.image!!.originalBytes())
            writeSynced(File(pending, "manifest.json"), relationship(checked).toString().toByteArray(Charsets.UTF_8))
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            // Only this call's known staging children; no recursive cleanup or old-copy deletion.
            listOf("original.json", "original.png", "manifest.json").forEach { File(pending, it).delete() }
            pending.delete()
        }
        return open(checked.id)
    }

    fun open(id: String): PrivateSdkObservationDocument {
        val bundle = file(id, "bundle")
        if (bundle.exists()) {
            require(bundle.isDirectory) { "图片关联不是目录" }
            val json = readBounded(File(bundle, "original.json"), ResearchRecordStore.MAX_BYTES)
            require(hash(json) == id) { "私有 JSON 完整性检查失败" }
            val document = attachPng(decode(json), readBounded(File(bundle, "original.png"), PrivateSdkObservationImage.MAX_BYTES))
            val manifest = Json.parseToJsonElement(validatedResearchInputText(readBounded(File(bundle, "manifest.json"), 4096)))
            require(manifest == relationship(document)) { "图片关联清单不一致" }
            return document
        }
        val source = file(id)
        val bytes = readBounded(source, ResearchRecordStore.MAX_BYTES)
        require(hash(bytes) == id) { "私有副本完整性检查失败" }
        return decode(bytes)
    }

    private fun file(id: String, extension: String = "json"): File {
        require(id.matches(Regex("[0-9a-f]{64}"))) { "无效私有观察标识" }
        return File(directory, "$id.$extension")
    }

    private fun relationship(document: PrivateSdkObservationDocument): JsonObject = buildJsonObject {
        val image = checkNotNull(document.image)
        put("private_relation_version", 1)
        put("json_sha256", document.id)
        put("json_bytes", document.byteCount)
        put("png_sha256", image.sha256)
        put("png_bytes", image.byteCount)
        put("width", image.width)
        put("height", image.height)
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }

    private fun readBounded(file: File, limit: Int): ByteArray {
        require(file.isFile && file.length() <= limit) { "私有副本缺失或超限" }
        return FileInputStream(file).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= limit) { "私有副本超限" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun decode(bytes: ByteArray): PrivateSdkObservationDocument {
        require(bytes.size <= ResearchRecordStore.MAX_BYTES) { "私有观察超过 1 MiB" }
        try {
            val root = Json.parseToJsonElement(validatedResearchInputText(bytes)) as? JsonObject
                ?: throw IllegalArgumentException()
            val legacyObjects = root["objects"] as? JsonObject
            val sdk = root["sdk"] as? JsonObject
            val visibleObjects = sdk?.get("raw_getter_objects") as? JsonObject
            val witness = root["visible_witness"] as? JsonObject
            val requiredObjects = listOf("deep_label", "deep_parent", "search_label", "search_parent")
            val legacy = root.text("classification") == "PRIVATE_ENGINEERING_SDK_LITERAL_OBSERVATION_NOT_FORMAL_RESULT" &&
                requiredObjects.all { legacyObjects?.get(it) is JsonObject } &&
                listOf("literal_fields", "sdk_clock", "limits").all { root[it] is JsonObject }
            val visible = requiredObjects.all { visibleObjects?.get(it) is JsonObject } &&
                witness?.get("original_source") is JsonObject && root["time_boundaries"] is JsonObject
            require(legacy || visible)
            return PrivateSdkObservationDocument(hash(bytes), root, bytes, requiresImage = !legacy)
        } catch (_: Exception) {
            throw IllegalArgumentException("无法读取：需要 UTF-8 私有 SDK 历史观察格式；原文件未修改")
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** List metadata only: opening reads a fresh document instead of retaining every JSON tree. */
data class PrivateSdkObservationEntry(val id: String, val byteCount: Int?, val error: String?)

class PrivateSdkObservationImage(bytes: ByteArray, val sha256: String, val width: Int, val height: Int) {
    private val snapshot = bytes.copyOf()
    val byteCount: Int get() = snapshot.size
    fun originalBytes(): ByteArray = snapshot.copyOf()
    companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
}

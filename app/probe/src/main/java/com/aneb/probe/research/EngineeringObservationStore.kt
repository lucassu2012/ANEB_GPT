package com.aneb.probe.research

import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Explicit external directory boundary; paths are relative to the selected HOST directory. */
fun interface EngineeringObservationSource { fun open(relativePath: String): InputStream }

data class EngineeringObservationAsset(val path: String, val bytes: Long, val sha256: String)
data class EngineeringObservationAttempt(val runId: String, val condition: String, val state: String,
    val sendCalls: JsonPrimitive, val ownedCleanup: Boolean, val assets: List<EngineeringObservationAsset>)
class EngineeringObservationDocument(val id: String, pairBytes: ByteArray,
    val state: String, val attempts: List<EngineeringObservationAttempt>) {
    private val snapshot = pairBytes.copyOf()
    fun originalPairBytes(): ByteArray = snapshot.copyOf()
    val metricsNotice: String get() = "NA／未计算 · 私有工程原件，不是网络性能结论"
}
data class EngineeringObservationEntry(val id: String, val error: String? = null)
data class EngineeringObservationReaderState(val document: EngineeringObservationDocument? = null,
    val saved: Boolean = false, val entries: List<EngineeringObservationEntry> = emptyList(),
    val notice: String = "尚未选择工程采集目录")

/** Streaming, explicitly confirmed copies of HOST originals; independent of formal evidence/Room. */
class EngineeringObservationStore(private val directory: File) {
    fun preview(source: EngineeringObservationSource): EngineeringObservationDocument =
        decode(source.open("PAIR_RESULT.json").use { bounded(it, MAX_PAIR_BYTES) })

    fun save(document: EngineeringObservationDocument, source: EngineeringObservationSource): EngineeringObservationDocument {
        val current = preview(source)
        require(current.originalPairBytes().contentEquals(document.originalPairBytes())) { "预览后原清单已改变；未保存" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建工程副本目录" }
        val target = bundle(current.id)
        val pending = Files.createTempDirectory(directory.toPath(), "engineering-").toFile()
        val created = mutableListOf<File>()
        try {
            val pair = File(pending, "PAIR_RESULT.json")
            created += pair
            FileOutputStream(pair).use { it.write(current.originalPairBytes()); it.fd.sync() }
            for (attempt in current.attempts) for (asset in attempt.assets) {
                val output = localFile(pending, asset.path)
                check(output.parentFile.isDirectory || output.parentFile.mkdirs()) { "无法创建原件目录" }
                created += output
                source.open(asset.path).use { input ->
                    FileOutputStream(output).use { out ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        var count = 0L
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            if (size == 0) continue
                            count += size
                            require(count <= asset.bytes) { "原件大小超出清单；未保存" }
                            out.write(buffer, 0, size); digest.update(buffer, 0, size)
                        }
                        require(count == asset.bytes && hex(digest.digest()) == asset.sha256) { "原件大小或 SHA 不匹配；未保存" }
                        out.fd.sync()
                    }
                }
            }
            validateOriginals(pending, current)
            // Reuse is allowed only after validating the source selected for this confirmation.
            if (target.exists()) return open(current.id)
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            // Only this operation's known unique staging files, never an existing saved bundle.
            if (pending.exists()) {
                created.asReversed().forEach { it.delete() }
                current.attempts.forEach { a ->
                    File(pending, "${a.runId}/originals").delete()
                    File(pending, a.runId).delete()
                }
                pending.delete()
            }
        }
        return open(current.id)
    }

    fun list(): List<EngineeringObservationEntry> = if (!directory.exists()) emptyList() else {
        checkNotNull(directory.listFiles()).filter { it.isDirectory && it.name.matches(Regex("[0-9a-f]{64}\\.pair")) }
            .sortedByDescending { it.lastModified() }.map { f ->
                val id = f.name.removeSuffix(".pair")
                val error = try { readDocument(id); null } catch (_: Exception) { "清单无法读取；原副本保留" }
                EngineeringObservationEntry(id, error)
            }
    }

    fun open(id: String): EngineeringObservationDocument = readDocument(id).also { validateOriginals(bundle(id), it) }

    fun originalFile(document: EngineeringObservationDocument, path: String): File {
        val asset = document.attempts.flatMap { it.assets }.singleOrNull { it.path == path }
            ?: throw IllegalArgumentException("该文件不在本次原件清单中")
        return localFile(bundle(document.id), path).also { verifyFile(it, asset) }
    }

    private fun readDocument(id: String): EngineeringObservationDocument {
        val source = File(bundle(id), "PAIR_RESULT.json")
        require(source.isFile && !Files.isSymbolicLink(source.toPath())) { "本机清单缺失" }
        val bytes = source.inputStream().use { bounded(it, MAX_PAIR_BYTES) }
        require(sha(bytes) == id) { "本机清单完整性检查失败" }
        return decode(bytes)
    }

    private fun validateOriginals(root: File, document: EngineeringObservationDocument) {
        for (attempt in document.attempts) {
            attempt.assets.forEach { verifyFile(localFile(root, it.path), it) }
            val terminal = attempt.assets.singleOrNull { it.path.endsWith("/TERMINAL.json") }
            if (terminal != null) {
                val original = localFile(root, terminal.path).inputStream().use { bounded(it, MAX_PAIR_BYTES) }
                val obj = Json.parseToJsonElement(validatedResearchInputText(original)) as? JsonObject
                    ?: throw IllegalArgumentException("终态原件不是 JSON 对象")
                // HOST may stop before incorporating this terminal; its cleanup also includes
                // HOST-owned closure/postflight, unlike Native cleanup. Keep both layers literal.
                require(obj.text("run_id") == attempt.runId && obj.text("network_condition") == attempt.condition) { "终态不属于对应轮" }
                require(obj.text("claim_scope") == "private_engineering_observation" && obj["metrics"] == JsonNull &&
                    integer(obj["formal_new_slots"]) == 0L && obj.text("rpi") == "not_computed") { "不是私有未评分工程终态" }
            }
        }
    }

    private fun verifyFile(file: File, asset: EngineeringObservationAsset) {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == asset.bytes) { "本机原件缺失或大小不符" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var count = 0L
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                if (n == 0) continue
                count += n; require(count <= asset.bytes)
                digest.update(buffer, 0, n)
            }
            require(count == asset.bytes && hex(digest.digest()) == asset.sha256) { "本机原件 SHA 不符；没有显示旧成功" }
        }
    }

    private fun bundle(id: String): File {
        require(id.matches(Regex("[0-9a-f]{64}"))) { "无效工程副本标识" }
        return File(directory, "$id.pair").also { require(!Files.isSymbolicLink(it.toPath())) }
    }

    private fun localFile(root: File, path: String): File {
        val result = File(root, path)
        require(result.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "原件路径越界" }
        var parent = result.parentFile
        while (parent != null && parent != root) { require(!Files.isSymbolicLink(parent.toPath())); parent = parent.parentFile }
        return result
    }

    private fun decode(bytes: ByteArray): EngineeringObservationDocument {
        val root = Json.parseToJsonElement(validatedResearchInputText(bytes)) as? JsonObject
            ?: throw IllegalArgumentException("需要 HOST PAIR_RESULT.json 原对象")
        require(root.text("claim_scope") == "private_engineering_observation" &&
            integer(root["formal_new_slots"]) == 0L && root.text("rpi") == "not_computed") { "仅支持未评分的私有工程批次" }
        val state = requireNotNull(root.text("state"))
        require(state in setOf("PAIR_OBSERVATION_CLOSED", "PAIR_STOPPED")) { "无法识别批次状态；不转换为成功" }
        val rows = root["attempts"] as? JsonArray ?: throw IllegalArgumentException("缺少轮次清单")
        require(rows.size in 1..2)
        val seen = mutableSetOf<String>()
        var total = 0L
        val attempts = rows.map { element ->
            val row = element as? JsonObject ?: throw IllegalArgumentException("轮次格式错误")
            val run = requireNotNull(row.text("run_id"))
            val condition = requireNotNull(row.text("network_condition"))
            require(run.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) && condition in setOf("W1", "C1"))
            val attemptState = requireNotNull(row.text("state"))
            val sends = row["send_calls"] as? JsonPrimitive ?: throw IllegalArgumentException("缺少 SEND 原值")
            require((sends.isString && sends.content == "UNKNOWN") ||
                (!sends.isString && sends.content in setOf("0", "1"))) { "无法识别 SEND 原值；不归零" }
            val cleanup = boolean(row["owned_cleanup"])
            val assets = (row["artifacts"] as? JsonArray ?: throw IllegalArgumentException("缺少原件清单")).map { entry ->
                val obj = entry as? JsonObject ?: throw IllegalArgumentException("原件声明格式错误")
                val path = requireNotNull(obj.text("path"))
                val parts = path.split('/')
                require(parts.size == 3 && parts[0] == run && parts[1] == "originals" &&
                    parts[2].matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")) && seen.add(path)) { "原件路径重复、越界或不属于对应轮" }
                require(seen.size <= MAX_FILES) { "超过 6000 个原件" }
                val size = integer(obj["bytes"])
                val hash = requireNotNull(obj.text("sha256"))
                require(size in 0..MAX_ASSET_BYTES && hash.matches(Regex("[0-9a-f]{64}"))) { "原件大小或 SHA 格式错误" }
                total += size; require(total <= MAX_TOTAL_BYTES) { "原件总量超过 128 MiB" }
                EngineeringObservationAsset(path, size, hash)
            }
            if (attemptState == "OBSERVATION_CLOSED") {
                require(sends == JsonPrimitive(1) && cleanup)
                require(setOf("TERMINAL.json", "SEND_INTENT.json", "FINAL_MEDIA.json", "after-send.png", "capture-end.png", "video.mp4")
                    .all { name -> assets.any { it.path == "$run/originals/$name" } }) { "已闭合轮缺少必要原件" }
            }
            EngineeringObservationAttempt(run, condition, attemptState, sends, cleanup, assets)
        }
        require(attempts.map { it.runId }.distinct().size == attempts.size &&
            attempts.map { it.condition }.distinct().size == attempts.size) { "轮次身份重复" }
        if (state == "PAIR_OBSERVATION_CLOSED") require(attempts.size == 2 && attempts.all { it.state == "OBSERVATION_CLOSED" })
        return EngineeringObservationDocument(sha(bytes), bytes.copyOf(), state, attempts)
    }

    companion object {
        const val MAX_PAIR_BYTES = 2 * 1024 * 1024
        const val MAX_FILES = 6000
        const val MAX_ASSET_BYTES = 64L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
        private fun integer(value: JsonElement?): Long {
            val p = value as? JsonPrimitive
            require(p != null && !p.isString && p.content.matches(Regex("0|[1-9][0-9]*")))
            return requireNotNull(p.longOrNull)
        }
        private fun boolean(value: JsonElement?): Boolean {
            val p = value as? JsonPrimitive
            require(p != null && !p.isString)
            return requireNotNull(p.booleanOrNull)
        }
        private fun bounded(input: InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                if (n == 0) continue
                require(out.size() + n <= limit) { "清单或原文字节超限" }
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun sha(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}

/** Same public flow consumed by the native page and behavior regression, without retained asset bytes. */
class EngineeringObservationReaderController(private val store: EngineeringObservationStore) {
    var state = EngineeringObservationReaderState()
        private set
    private var selectedSource: EngineeringObservationSource? = null
    fun clear(notice: String = "尚未选择工程采集目录"): EngineeringObservationReaderState {
        selectedSource = null
        state = EngineeringObservationReaderState(notice = notice)
        return state
    }
    fun preview(source: EngineeringObservationSource?): EngineeringObservationReaderState {
        clear()
        if (source == null) return clear("已取消选择；没有保存或显示旧预览")
        return action("无法预览：需要原 HOST 目录、完整清单和读取权限；原文件未修改") {
            val document = store.preview(source)
            selectedSource = source
            state = state.copy(document = document, notice = "仅清单预览；尚未复制或核对原件。请确认后保存。")
        }
    }
    fun confirmSave(): EngineeringObservationReaderState {
        val document = state.document ?: return clear("请先选择并预览工程采集目录")
        val source = selectedSource ?: return clear("外部来源已释放；请重新选择")
        return action("保存失败：原件缺失、SHA 不符、来源改变或存储不可用；未覆盖旧副本") {
            val saved = store.save(document, source)
            selectedSource = null
            state = state.copy(document = saved, saved = true, notice = "全部清单原件已复制并核对 SHA；这不是观察真实性或网络评级认证。")
        }
    }
    fun showList(): EngineeringObservationReaderState {
        clear()
        return action("无法读取本机工程列表；副本保留") { state = state.copy(entries = store.list(), notice = "本机工程副本；点击后重新读盘核对全部原件。") }
    }
    fun open(id: String): EngineeringObservationReaderState {
        clear()
        return action("无法重开：清单或原件缺失／损坏；未显示旧记录，副本保留") {
            state = state.copy(document = store.open(id), saved = true, notice = "已从本机重读并核对全部清单原件；外部目录不再需要。")
        }
    }
    fun originalFile(path: String): File = store.originalFile(checkNotNull(state.document.takeIf { state.saved }), path)
    private inline fun action(message: String, block: () -> Unit): EngineeringObservationReaderState {
        try { block() }
        catch (e: CancellationException) { clear("操作已取消；未显示旧成功"); throw e }
        catch (_: Exception) { clear(message) }
        return state
    }
}

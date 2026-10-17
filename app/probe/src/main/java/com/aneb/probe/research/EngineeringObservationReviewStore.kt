package com.aneb.probe.research

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.*

data class EngineeringReviewSelection(val runId: String, val path: String)
data class EngineeringReviewInput(val appName: String = "", val appVersion: String = "",
    val visibleMode: String = "", val facts: String = "", val limitations: String = "",
    val evidence: List<EngineeringReviewSelection> = emptyList())
data class EngineeringReview(val id: String, val pairId: String, val input: EngineeringReviewInput)
data class EngineeringReviewEntry(val id: String, val error: String? = null)
data class EngineeringReviewState(val document: EngineeringObservationDocument? = null,
    val input: EngineeringReviewInput = EngineeringReviewInput(), val review: EngineeringReview? = null,
    val entries: List<EngineeringReviewEntry> = emptyList(), val editing: Boolean = false,
    val failed: Boolean = false, val notice: String = "尚未打开本机批次")

/** Independent human declarations. Never modifies the pair, originals, or measurement data. */
class EngineeringObservationReviewStore(private val directory: File, private val originals: EngineeringObservationStore) {
    fun save(pairId: String, input: EngineeringReviewInput, confirmed: Boolean): EngineeringReview {
        require(confirmed) { "请明确确认人工声明及证据引用后再保存" }
        val document = originals.open(pairId) // A preview alone is not eligible.
        validateInput(input)
        val bytes = encode(document, input).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val id = sha(bytes)
        writeNew(file(pairId, id), bytes)
        return open(pairId, id)
    }

    fun open(pairId: String, id: String): EngineeringReview {
        val document = originals.open(pairId)
        val target = file(pairId, id)
        require(target.isFile && !Files.isSymbolicLink(target.toPath()) && target.length() <= MAX_BYTES) { "复核缺失或超出本机预算" }
        val bytes = target.readBytes()
        require(sha(bytes) == id) { "复核内容已损坏；没有显示旧成功" }
        val root = Json.parseToJsonElement(validatedResearchInputText(bytes)).jsonObject
        val input = readInput(root)
        validateInput(input)
        require(root == encode(document, input)) { "复核与所选批次／轮次／证据不匹配" }
        return EngineeringReview(id, pairId, input)
    }

    fun originalFile(review: EngineeringReview, selection: EngineeringReviewSelection): File {
        val saved = open(review.pairId, review.id)
        require(selection in saved.input.evidence) { "该原件不是本复核引用" }
        return originals.originalFile(originals.open(saved.pairId), selection.path)
    }

    fun list(pairId: String): List<EngineeringReviewEntry> {
        val parent = file(pairId, pairId).parentFile!!
        if (!parent.exists()) return emptyList()
        return checkNotNull(parent.listFiles()).filter { it.name.matches(Regex("[0-9a-f]{64}\\.json")) }
            .sortedByDescending { it.lastModified() }.map { candidate ->
                EngineeringReviewEntry(candidate.nameWithoutExtension,
                    if (candidate.isFile && !Files.isSymbolicLink(candidate.toPath())) null else "无效本机复核；原条目保留")
            }
    }

    fun retainDraft(document: EngineeringObservationDocument, input: EngineeringReviewInput) {
        // Unsubmitted local input uses the already verified immutable manifest, not batch I/O.
        // Opening a pair and submitting/exporting a review still validate actual originals.
        validateDraft(input)
        // A draft permits incomplete text/evidence, but never arbitrary or cross-run paths.
        val bytes = encode(document, input).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        replaceDraft(draftFile(document.id), bytes)
    }

    fun draft(document: EngineeringObservationDocument): EngineeringReviewInput? {
        val target = draftFile(document.id)
        if (!target.exists()) return null
        require(target.isFile && !Files.isSymbolicLink(target.toPath()) && target.length() <= MAX_BYTES) { "本机草稿缺失或损坏；未替换" }
        val root = Json.parseToJsonElement(validatedResearchInputText(target.readBytes())).jsonObject
        val input = readInput(root)
        validateDraft(input)
        require(root == encode(document, input)) { "草稿不属于本批次" }
        return input
    }

    fun discardDraft(pairId: String) {
        val target = draftFile(pairId)
        require(!Files.isSymbolicLink(target.toPath()))
        check(!target.exists() || target.delete()) { "无法移除本次草稿；没有改动已保存复核" }
    }

    fun exportBytes(pairId: String, id: String, privacyConfirmed: Boolean): ByteArray {
        require(privacyConfirmed) { "请先确认人工输入、App 信息及证据路径可能包含隐私" }
        open(pairId, id) // Revalidate this saved review and current originals, never export a draft.
        return file(pairId, id).readBytes().also { require(sha(it) == id) { "导出前复核文件已改变" } }
    }

    private fun encode(document: EngineeringObservationDocument, input: EngineeringReviewInput) = buildJsonObject {
        put("review_kind", "private_engineering_human_review_v1")
        put("pair_sha256", document.id)
        put("pair_state", document.state)
        put("notice", NOTICE)
        put("formal_new_slots", 0); put("metrics", JsonNull); put("rpi", "not_computed")
        put("app_name", input.appName); put("app_version", input.appVersion); put("visible_mode", input.visibleMode)
        put("visible_facts", input.facts); put("limitations", input.limitations)
        putJsonArray("attempts") { document.attempts.forEach { a -> add(buildJsonObject {
            put("run_id", a.runId); put("condition", a.condition); put("state", a.state)
            put("send_calls", a.sendCalls); put("owned_cleanup", a.ownedCleanup)
        }) } }
        putJsonArray("evidence") { input.evidence.forEach { selection ->
            val attempt = document.attempts.singleOrNull { it.runId == selection.runId }
                ?: throw IllegalArgumentException("证据轮次不属于此批次")
            val asset = attempt.assets.singleOrNull { it.path == selection.path }
                ?: throw IllegalArgumentException("证据不属于所选轮的真实清单")
            add(buildJsonObject {
                put("run_id", attempt.runId); put("condition", attempt.condition)
                put("path", asset.path); put("bytes", asset.bytes); put("sha256", asset.sha256)
            })
        } }
    }

    private fun readInput(root: JsonObject) = EngineeringReviewInput(
        root.getValue("app_name").jsonPrimitive.content, root.getValue("app_version").jsonPrimitive.content,
        root.getValue("visible_mode").jsonPrimitive.content, root.getValue("visible_facts").jsonPrimitive.content,
        root.getValue("limitations").jsonPrimitive.content,
        root.getValue("evidence").jsonArray.map { element -> element.jsonObject.let {
            EngineeringReviewSelection(it.getValue("run_id").jsonPrimitive.content, it.getValue("path").jsonPrimitive.content)
        } })

    private fun validateInput(input: EngineeringReviewInput) {
        validateDraft(input)
        require(input.facts.isNotBlank() && input.limitations.isNotBlank()) { "请填写实际可见事实和未确认／适用边界" }
        require(input.evidence.size in 1..6) { "请选择 1–6 个本轮证据；选择不代表工具已认证观看" }
    }

    private fun validateDraft(input: EngineeringReviewInput) {
        require(listOf(input.appName, input.appVersion, input.visibleMode).all { it.length <= 200 }) { "App／版本／模式说明每项最多 200 字符" }
        require(input.facts.length <= 4000 && input.limitations.length <= 4000) { "事实和未确认／适用边界每项最多 4000 字符" }
        require(input.evidence.size <= 6 && input.evidence.distinct().size == input.evidence.size) { "最多 6 个不重复证据" }
    }

    private fun draftFile(pairId: String) = File(file(pairId, pairId).parentFile, "draft.json")

    private fun replaceDraft(target: File, bytes: ByteArray) {
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法创建草稿目录" }
        require(!Files.isSymbolicLink(target.toPath()))
        val pending = File.createTempFile("review-draft-", ".pending", target.parentFile)
        try {
            FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }

    private fun file(pairId: String, id: String): File {
        require(listOf(pairId, id).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(!Files.isSymbolicLink(directory.toPath()))
        val parent = File(directory, pairId)
        require(!Files.isSymbolicLink(parent.toPath()))
        return File(parent, "$id.json")
    }

    private fun writeNew(target: File, bytes: ByteArray) {
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "无法创建人工复核目录" }
        if (target.exists()) {
            require(!Files.isSymbolicLink(target.toPath()) && target.readBytes().contentEquals(bytes)) { "已有复核损坏；未覆盖" }
            return
        }
        val pending = File.createTempFile("review-", ".pending", target.parentFile)
        try {
            FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { pending.delete() }
    }

    companion object {
        const val NOTICE = "人工复核是输入声明，未经工具真实性认证；私有工程用途；正式新增 0，指标 NA／未计算。"
        private const val MAX_BYTES = 64 * 1024
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Same public local workflow used by the Compose page; errors retain current input, not stale success. */
class EngineeringObservationReviewController(private val originals: EngineeringObservationStore,
    private val reviews: EngineeringObservationReviewStore) {
    @Volatile var state = EngineeringReviewState()
        private set
    private data class PendingDraft(val document: EngineeringObservationDocument, val input: EngineeringReviewInput)
    // Only bounded draft encoding/writes/delete use this lock; never originals.open/export/save.
    private val draftLock = Any()
    private var pendingDraft: PendingDraft? = null
    @Synchronized fun openPair(pairId: String): EngineeringReviewState {
        synchronized(draftLock) { state = EngineeringReviewState(); pendingDraft = null }
        return action("无法打开本机批次／草稿；未显示旧复核，旧文件保留") {
            val document = originals.open(pairId)
            state = state.copy(document = document, entries = reviews.list(pairId))
            val draft = reviews.draft(document)
            state = state.copy(input = draft ?: EngineeringReviewInput(), editing = draft != null,
                notice = if (draft == null) "先读已保存复核，或新建人工复核；尚未保存新声明" else "已恢复本机未提交草稿；这不是已保存复核")
        }
    }
    @Synchronized fun edit(input: EngineeringReviewInput): EngineeringReviewState {
        val document = checkNotNull(state.document) { "请先打开本机批次" }
        val snapshot = input.copy(evidence = input.evidence.toList())
        synchronized(draftLock) {
            pendingDraft = PendingDraft(document, snapshot)
            state = state.copy(input = snapshot, review = null, editing = true, failed = false, notice = "未提交输入；返回／后台时保留本机草稿")
        }
        return state
    }
    @Synchronized fun retainDraft(): EngineeringReviewState = retainDraftForLifecycle()

    /** No controller monitor or batch reads: safe from an in-flight full open/submit/export lock. */
    fun retainDraftForLifecycle(): EngineeringReviewState = synchronized(draftLock) {
        val pending = pendingDraft ?: return@synchronized state
        try {
            reviews.retainDraft(pending.document, pending.input)
            pendingDraft = null
            // Do not replace a concurrently completed saved review with a draft success notice.
            if (state.review == null && state.input == pending.input) state = state.copy(failed = false, notice = "已保留本机草稿；尚未提交人工复核")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {
            state = state.copy(failed = true, notice = "草稿保存失败；输入仍在当前页，请重试或明确放弃，不要退出")
        }
        state
    }
    @Synchronized fun retainForNavigation(): Boolean {
        if (synchronized(draftLock) { pendingDraft == null }) return true
        return !retainDraft().failed
    }
    @Synchronized fun confirmSave(confirmed: Boolean): EngineeringReviewState {
        state = state.copy(review = null)
        return action("保存失败：检查填写、证据和本机存储；输入及已有复核保留") {
            val pairId = checkNotNull(state.document).id
            val saved = reviews.save(pairId, state.input, confirmed)
            val entries = reviews.list(pairId)
            synchronized(draftLock) {
                state = state.copy(review = saved, editing = false, entries = entries, notice = "人工声明已另存本机版本；未经真实性认证")
                pendingDraft = null
                try { reviews.discardDraft(pairId) }
                catch (_: Exception) { state = state.copy(notice = "复核已保存；旧草稿未能移除，仍保留；不是新的已提交版本") }
            }
        }
    }
    @Synchronized fun openReview(id: String): EngineeringReviewState {
        state = state.copy(review = null)
        return action("无法关联复核：批次／轮次不符或原件已损坏；未显示旧成功") {
            state = state.copy(review = reviews.open(checkNotNull(state.document).id, id), editing = false,
                notice = "已重读人工复核与对应原件；字节关联不是真实性认证")
        }
    }
    @Synchronized fun discardDraft(): EngineeringReviewState = action("无法放弃本次草稿；输入与已保存复核保留") {
        synchronized(draftLock) {
            reviews.discardDraft(checkNotNull(state.document).id)
            pendingDraft = null
            state = state.copy(input = EngineeringReviewInput(), editing = false, notice = "已明确放弃未提交草稿；已保存复核不变")
        }
    }
    @Synchronized fun originalFile(selection: EngineeringReviewSelection): File =
        reviews.originalFile(checkNotNull(state.review), selection)

    @Synchronized fun exportBytes(privacyConfirmed: Boolean): ByteArray? {
        var output: ByteArray? = null
        action("无法准备导出：请先保存／打开有效复核并确认隐私；输入和本机文件保留") {
            val review = checkNotNull(state.review)
            output = reviews.exportBytes(review.pairId, review.id, privacyConfirmed)
            state = state.copy(notice = "复核 JSON 已核验；等待选择导出位置，尚未确认输出")
        }
        return output
    }
    @Synchronized fun exportOutcome(success: Boolean?): EngineeringReviewState {
        state = state.copy(failed = success == false, notice = when (success) {
            true -> "已写入复核 JSON 与引用元数据；未附原件、未上传"
            false -> "导出未完成；所选位置可能有空／部分文档，请检查。输入和本机复核保留。"
            null -> "已取消导出；未确认新文件输出，本机复核保留"
        })
        return state
    }
    private inline fun action(message: String, block: () -> Unit): EngineeringReviewState {
        state = state.copy(failed = false)
        try { block() }
        catch (e: kotlinx.coroutines.CancellationException) { state = state.copy(failed = true, notice = "操作取消；输入和旧复核保留"); throw e }
        catch (_: Exception) { state = state.copy(failed = true, notice = message) }
        return state
    }
}

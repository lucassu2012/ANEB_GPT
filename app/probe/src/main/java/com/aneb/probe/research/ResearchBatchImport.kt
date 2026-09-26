package com.aneb.probe.research

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

enum class ResearchBatchImportItemKind { RAW, ANALYSIS, UNKNOWN }
enum class ResearchBatchImportItemStatus { READY, INVALID, UNMATCHED_SOURCE, DUPLICATE }
enum class ResearchBatchImportSaveStatus { SAVED, NOT_SAVED }

/** One selected file. Bytes are snapshotted so the preview cannot drift before explicit confirmation. */
class ResearchBatchImportFile(
    val fileName: String,
    bytes: ByteArray,
    val readError: String? = null,
) {
    val byteCount: Int = bytes.size
    private val snapshot = if (byteCount <= ResearchRecordStore.MAX_BYTES) bytes.copyOf() else byteArrayOf()

    internal fun bytes(): ByteArray = snapshot.copyOf()
}

data class ResearchBatchImportItemPreview(
    val index: Int,
    val fileName: String,
    val kind: ResearchBatchImportItemKind,
    val status: ResearchBatchImportItemStatus,
    val sourceId: String? = null,
    val analysisId: String? = null,
    val message: String? = null,
)

data class ResearchBatchImportPairPreview(
    val sourceId: String,
    val rawFileName: String?,
    val analysisFileNames: List<String>,
)

data class ResearchBatchImportSaveItem(
    val index: Int,
    val fileName: String,
    val status: ResearchBatchImportSaveStatus,
    val sourceId: String? = null,
    val analysisId: String? = null,
    val message: String? = null,
)

data class ResearchBatchImportSaveReceipt(val items: List<ResearchBatchImportSaveItem>) {
    val savedCount: Int get() = items.count { it.status == ResearchBatchImportSaveStatus.SAVED }
}

/**
 * Preview then explicitly save selected raw records and their separate analysis JSON copies.
 * Pairing uses the existing source SHA-256 and Store decoders; this does not recalculate analysis.
 */
class ResearchBatchImportPreview internal constructor(
    val items: List<ResearchBatchImportItemPreview>,
    val pairs: List<ResearchBatchImportPairPreview>,
    private val prepared: List<PreparedResearchImport>,
    private val records: ResearchRecordStore,
    private val analyses: ResearchAnalysisStore,
) {
    @Volatile private var savedReceipt: ResearchBatchImportSaveReceipt? = null

    /** Call only after a user confirmation. Each item reports its own outcome; no rollback is implied. */
    @Synchronized
    fun confirmSave(): ResearchBatchImportSaveReceipt {
        savedReceipt?.let { return it }
        val results = mutableMapOf<Int, ResearchBatchImportSaveItem>()
        val ready = prepared.filter { it.preview.status == ResearchBatchImportItemStatus.READY }

        // Save originals first regardless of picker order so analysis copies never create an orphan.
        ready.filter { it.preview.kind == ResearchBatchImportItemKind.RAW }.forEach { item ->
            try {
                val saved = records.save(item.bytes.copyOf())
                results[item.preview.index] = ResearchBatchImportSaveItem(
                    item.preview.index, item.preview.fileName, ResearchBatchImportSaveStatus.SAVED, sourceId = saved.id,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                results[item.preview.index] = notSaved(item, "原文未保存；请检查本机可用存储后再处理该项。")
            }
        }

        ready.filter { it.preview.kind == ResearchBatchImportItemKind.ANALYSIS }.forEach { item ->
            val selectedSourceIndex = item.selectedSourceIndex
            if (selectedSourceIndex != null && results[selectedSourceIndex]?.status != ResearchBatchImportSaveStatus.SAVED) {
                results[item.preview.index] = notSaved(item, "关联原文未保存；此分析未保存。")
                return@forEach
            }
            try {
                val sourceId = checkNotNull(item.sourceId)
                // Re-open by its content hash at save time; Store validation rechecks attempt binding.
                val source = records.open(sourceId)
                val saved = analyses.save(source, item.bytes.copyOf())
                results[item.preview.index] = ResearchBatchImportSaveItem(
                    item.preview.index, item.preview.fileName, ResearchBatchImportSaveStatus.SAVED,
                    sourceId = source.id, analysisId = saved.id,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                results[item.preview.index] = notSaved(item, "分析未保存；原文及其他已保存项仍保留。")
            }
        }

        prepared.filter { it.preview.status != ResearchBatchImportItemStatus.READY }.forEach { item ->
            results[item.preview.index] = notSaved(item, item.preview.message ?: "此文件未保存。")
        }
        return ResearchBatchImportSaveReceipt(items = results.values.sortedBy { it.index }).also { savedReceipt = it }
    }

    private fun notSaved(item: PreparedResearchImport, message: String) = ResearchBatchImportSaveItem(
        index = item.preview.index,
        fileName = item.preview.fileName,
        status = ResearchBatchImportSaveStatus.NOT_SAVED,
        sourceId = item.preview.sourceId,
        analysisId = item.preview.analysisId,
        message = message,
    )
}

internal data class PreparedResearchImport(
    val preview: ResearchBatchImportItemPreview,
    val bytes: ByteArray,
    val sourceId: String? = null,
    val analysisId: String? = null,
    val source: ResearchDocument? = null,
    val selectedSourceIndex: Int? = null,
)

object ResearchBatchImport {
    /** Resource guard only: twenty files and at most 10 MiB total per selection. */
    const val MAX_FILES = 20
    const val MAX_TOTAL_BYTES = 10 * 1024 * 1024

    fun preview(
        files: List<ResearchBatchImportFile>,
        records: ResearchRecordStore,
        analyses: ResearchAnalysisStore,
    ): ResearchBatchImportPreview {
        if (files.size > MAX_FILES || files.sumOf { it.byteCount.toLong() } > MAX_TOTAL_BYTES) {
            val prepared = files.mapIndexed { index, file ->
                val item = ResearchBatchImportItemPreview(
                    index, file.fileName, ResearchBatchImportItemKind.UNKNOWN,
                    ResearchBatchImportItemStatus.INVALID,
                    message = "本次选择超过 ${MAX_FILES} 个文件或 ${MAX_TOTAL_BYTES / (1024 * 1024)} MiB；请分批选择，未写入。",
                )
                PreparedResearchImport(item, byteArrayOf())
            }
            return ResearchBatchImportPreview(prepared.map { it.preview }, emptyList(), prepared, records, analyses)
        }

        val preliminary = files.mapIndexed { index, file ->
            val bytes = file.bytes()
            when {
                file.readError != null -> PreparedResearchImport(
                    item(index, file.fileName, ResearchBatchImportItemKind.UNKNOWN, ResearchBatchImportItemStatus.INVALID,
                        message = when (file.readError) {
                            "too_large" -> "单个文件超过 1 MiB；未导入。"
                            "too_many" -> "本次选择超过文件数限制；未导入。"
                            "batch_limit" -> "累计读取达到 10 MiB；此文件及后续未读取文件未导入。"
                            else -> "无法读取所选文件；未导入。"
                        }), bytes,
                )
                file.byteCount > ResearchRecordStore.MAX_BYTES -> PreparedResearchImport(
                    item(index, file.fileName, ResearchBatchImportItemKind.UNKNOWN, ResearchBatchImportItemStatus.INVALID,
                        message = "单个文件超过 1 MiB；未导入。"), bytes,
                )
                else -> classify(index, file.fileName, bytes)
            }
        }

        val rawGroups = preliminary.filter { it.preview.kind == ResearchBatchImportItemKind.RAW && it.source != null }
            .groupBy { it.source!!.id }
        val duplicateRawIds = rawGroups.filterValues { it.size > 1 }.keys
        val rawUnique = preliminary.map { item ->
            if (item.source?.id in duplicateRawIds) item.copy(
                preview = item.preview.copy(status = ResearchBatchImportItemStatus.DUPLICATE, message = "本次选择中有重复原文；为避免歧义，这些原文未保存。"),
            ) else item
        }
        val sourcesById = rawUnique.filter {
            it.preview.kind == ResearchBatchImportItemKind.RAW && it.preview.status == ResearchBatchImportItemStatus.READY
        }.associateBy { checkNotNull(it.sourceId) }

        val resolvedAnalyses = rawUnique.map { item ->
            if (item.preview.kind != ResearchBatchImportItemKind.ANALYSIS || item.preview.status != ResearchBatchImportItemStatus.READY) {
                item
            } else {
                val sourceId = item.sourceId
                if (sourceId == null || !sourceId.matches(SHA256)) {
                    item.copy(preview = item.preview.copy(status = ResearchBatchImportItemStatus.INVALID, message = "分析缺少有效的原文 SHA-256；未保存。"))
                } else if (sourceId in duplicateRawIds) {
                    item.copy(preview = item.preview.copy(status = ResearchBatchImportItemStatus.UNMATCHED_SOURCE,
                        message = "所选原文重复，无法唯一关联；此分析未保存。"))
                } else {
                    val selected = sourcesById[sourceId]
                    val source = selected?.source ?: runCatching { records.open(sourceId) }.getOrNull()
                    if (source == null) {
                        item.copy(preview = item.preview.copy(status = ResearchBatchImportItemStatus.UNMATCHED_SOURCE,
                            message = "找不到或无法读取 source_sha256 指向的原文；此分析未保存。"))
                    } else try {
                        val decoded = ResearchAnalysisStore.decode(source, item.bytes)
                        item.copy(
                            preview = item.preview.copy(sourceId = source.id, analysisId = decoded.id),
                            sourceId = source.id,
                            analysisId = decoded.id,
                            source = source,
                            selectedSourceIndex = selected?.preview?.index,
                        )
                    } catch (_: Exception) {
                        item.copy(preview = item.preview.copy(status = ResearchBatchImportItemStatus.INVALID,
                            message = "分析无法按原文 SHA-256 与完整 attempt 身份关联；此分析未保存。"))
                    }
                }
            }
        }

        val duplicateAnalysisKeys = resolvedAnalyses.filter {
            it.preview.kind == ResearchBatchImportItemKind.ANALYSIS && it.analysisId != null &&
                it.preview.status == ResearchBatchImportItemStatus.READY
        }.groupBy { it.sourceId to it.analysisId }.filterValues { it.size > 1 }.keys
        val finalPrepared = resolvedAnalyses.map { item ->
            if ((item.sourceId to item.analysisId) in duplicateAnalysisKeys) item.copy(
                preview = item.preview.copy(status = ResearchBatchImportItemStatus.DUPLICATE,
                    message = "本次选择中有重复分析副本；重复项未保存。"),
            ) else item
        }
        val pairMap = linkedMapOf<String, PairBuilder>()
        finalPrepared.filter { it.preview.kind == ResearchBatchImportItemKind.RAW && it.preview.status == ResearchBatchImportItemStatus.READY }
            .forEach { item -> pairMap[item.sourceId!!] = PairBuilder(item.sourceId, item.preview.fileName) }
        finalPrepared.filter { it.preview.kind == ResearchBatchImportItemKind.ANALYSIS && it.preview.status == ResearchBatchImportItemStatus.READY }
            .forEach { item ->
                val pair = pairMap.getOrPut(checkNotNull(item.sourceId)) { PairBuilder(checkNotNull(item.sourceId), null) }
                pair.analysisNames += item.preview.fileName
            }
        return ResearchBatchImportPreview(
            items = finalPrepared.map { it.preview },
            pairs = pairMap.values.map { ResearchBatchImportPairPreview(it.sourceId, it.rawFileName, it.analysisNames.toList()) },
            prepared = finalPrepared,
            records = records,
            analyses = analyses,
        )
    }

    private data class PairBuilder(val sourceId: String, val rawFileName: String?, val analysisNames: MutableList<String> = mutableListOf())

    private fun classify(index: Int, fileName: String, bytes: ByteArray): PreparedResearchImport {
        val root = try { Json.parseToJsonElement(validatedResearchInputText(bytes)) as? JsonObject }
        catch (e: Exception) {
            val message = if (e.message?.contains("JSON 嵌套过深") == true) {
                "JSON 嵌套超过本机 64 层资源限制；未导入。"
            } else "文件不是可读取的 UTF-8 JSON 对象；未导入。"
            return PreparedResearchImport(
                item(index, fileName, ResearchBatchImportItemKind.UNKNOWN, ResearchBatchImportItemStatus.INVALID, message = message), bytes,
            )
        }
        if (root == null) return PreparedResearchImport(
            item(index, fileName, ResearchBatchImportItemKind.UNKNOWN, ResearchBatchImportItemStatus.INVALID,
                message = "文件需要 JSON 对象；未导入。"), bytes,
        )
        if (root.containsKey("source_sha256")) {
            val sourceId = root.text("source_sha256")
            return PreparedResearchImport(
                item(index, fileName, ResearchBatchImportItemKind.ANALYSIS, ResearchBatchImportItemStatus.READY,
                    sourceId = sourceId, message = null),
                bytes,
                sourceId = sourceId,
            )
        }
        return try {
            val document = ResearchRecordStore.decode(bytes)
            PreparedResearchImport(
                item(index, fileName, ResearchBatchImportItemKind.RAW, ResearchBatchImportItemStatus.READY,
                    sourceId = document.id),
                bytes,
                sourceId = document.id,
                source = document,
            )
        } catch (_: Exception) {
            val resemblesAnalysis = root.containsKey("input_revision") || root.containsKey("input_record") || root.containsKey("counts")
            PreparedResearchImport(
                item(
                    index,
                    fileName,
                    if (resemblesAnalysis) ResearchBatchImportItemKind.ANALYSIS else ResearchBatchImportItemKind.RAW,
                    ResearchBatchImportItemStatus.INVALID,
                    message = if (resemblesAnalysis) "分析缺少有效的原文 SHA-256；未保存。"
                        else "原文不是支持的 R1 / research-video JSON；未导入。",
                ),
                bytes,
            )
        }
    }

    private fun item(
        index: Int,
        fileName: String,
        kind: ResearchBatchImportItemKind,
        status: ResearchBatchImportItemStatus,
        sourceId: String? = null,
        analysisId: String? = null,
        message: String? = null,
    ) = ResearchBatchImportItemPreview(index, fileName, kind, status, sourceId, analysisId, message)

    private val SHA256 = Regex("[0-9a-f]{64}")
}

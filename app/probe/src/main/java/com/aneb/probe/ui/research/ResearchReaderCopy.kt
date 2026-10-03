package com.aneb.probe.ui.research

import com.aneb.probe.research.ResearchBatchImportItemKind
import com.aneb.probe.research.ResearchBatchImportSaveReceipt

/** User-facing reader wording only; no record interpretation or state changes. */
internal object ResearchReaderCopy {
    const val title = "研究记录与分析"
    const val introduction = "读取已有/手工记录，不启动测速、不重算指标。方法与操作仅为输入声明，缺项仍“未提供”；SAMPLE 样例与 OBSERVED 观察声明分开保存，不代表后台接收、真实模型或格式评分已核验。与 AQS 和 RPI 分开。"

    fun importKind(kind: ResearchBatchImportItemKind): String = when (kind) {
        ResearchBatchImportItemKind.RAW -> "研究记录（输入文件）"
        ResearchBatchImportItemKind.ANALYSIS -> "分析"
        ResearchBatchImportItemKind.UNKNOWN -> "未知 JSON"
    }

    fun saveSummary(receipt: ResearchBatchImportSaveReceipt): String =
        "保存处理成功 ${receipt.savedCount}/${receipt.items.size} 项"

    const val saveNotice = "已存输入可复用，不代表新增记录或观察。已保存项保留；未保存项不计成功，也不会回滚其他独立保存项。"

    fun outcomeSummary(executed: String, visibleCompletion: String, instructionFollowing: String): String =
        "原记录执行标注：$executed；可见完成：$visibleCompletion；遵循指令：$instructionFollowing"

    const val outcomeNotice = "可见完成、格式合规、真实工具调用分别判断；执行标注不代表这些结论。UNKNOWN/null/NA 不补 0。"

    const val analysisChoiceLabel = "选择本批分析"

    fun analysisGuide(isVideo: Boolean): String =
        "选择本批分析 JSON；按输入 SHA-256 与${if (isVideo) "视频 slot" else " attempt_id"}配对，不覆盖研究记录。重开后需重新选择；未选择不是没有回答，不默认选中，不借用其他批次。"

    const val recordExportLabel = "导出整批研究记录 JSON"
    const val analysisExportLabel = "导出所选分析 JSON"

    fun exportTitle(isAnalysis: Boolean): String =
        if (isAnalysis) "$analysisExportLabel？" else "$recordExportLabel？"

    fun exportConfirmation(sourceLabel: String?, recordSha: String?, analysisSha: String?): String {
        val type = if (analysisSha != null) "所选分析 JSON" else "整批研究记录 JSON"
        val fingerprint = if (analysisSha != null) {
            "分析 SHA-256：$analysisSha\n关联研究记录 SHA-256：${recordSha ?: "未提供"}"
        } else {
            "输入 SHA-256：${recordSha ?: "未提供"}\n关联研究记录：本批输入"
        }
        val bytes = if (analysisSha != null) "所选分析原始字节（含研究记录上下文）" else "整批研究记录原始字节"
        return "${sourceLabel ?: "未确认来源"}\n类型：$type\n$fingerprint\n将$bytes 保存到 Downloads/ANEB，可能包含操作文本和本地证据引用。不会打包录像，也不会自动上传或分享。分析未在本机重算或独立核验。请先确认内容适合导出。"
    }
}

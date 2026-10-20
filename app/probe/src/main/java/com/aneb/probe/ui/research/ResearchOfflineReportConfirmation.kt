package com.aneb.probe.ui.research

/** Immutable IDs for the already opened, validated card; never a file-system path. */
internal data class ResearchOfflineReportTarget(
    val sourceId: String,
    val analysisId: String,
    val conclusionId: String,
)

internal data class ResearchOfflineReportConfirmation(
    val pending: ResearchOfflineReportTarget? = null,
) {
    fun select(target: ResearchOfflineReportTarget): ResearchOfflineReportConfirmation = copy(pending = target)
    fun cancel(): ResearchOfflineReportConfirmation = copy(pending = null)

    fun confirm(
        current: ResearchOfflineReportTarget?,
        onExport: (ResearchOfflineReportTarget) -> Unit,
    ): ResearchOfflineReportConfirmation {
        val target = pending ?: return this
        if (target == current) onExport(target)
        return cancel()
    }
}

internal object ResearchOfflineReportCopy {
    const val title = "导出私有离线报告 ZIP"
    const val failure = "报告导出未完成，未显示成功；本机原文、分析和人工结论仍保留。请核对所选文件与可用空间。"
    fun confirmation(target: ResearchOfflineReportTarget): String =
        "仅保存到本机 Downloads/ANEB；本次导出不联网、不上传、不分享。ZIP 可能含私密研究文字、人工结论及本地引用路径。\n\n" +
            "导出整批已保存文本，不应用当前 App、来源、condition 或会话筛选。包含原文 JSON、匹配分析 JSON、人工结论 MD 原字节、UTF-8 离线首页和 SHA-256 清单；不含截图、录像或脚本，不重新计算指标。输入声明不是真实性认证。\n\n" +
            "原文 SHA-256：" + target.sourceId + "\n分析 SHA-256：" + target.analysisId +
            "\n结论 SHA-256：" + target.conclusionId
}

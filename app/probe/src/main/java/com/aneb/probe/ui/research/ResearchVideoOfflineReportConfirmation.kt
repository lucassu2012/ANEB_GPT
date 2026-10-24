package com.aneb.probe.ui.research

/** Saved IDs only, never a media path or fabricated conclusion card. */
internal data class ResearchVideoOfflineReportTarget(val sourceId: String, val analysisId: String)

internal data class ResearchVideoOfflineReportConfirmation(val pending: ResearchVideoOfflineReportTarget? = null) {
    fun select(target: ResearchVideoOfflineReportTarget): ResearchVideoOfflineReportConfirmation = copy(pending = target)
    fun cancel(): ResearchVideoOfflineReportConfirmation = copy(pending = null)

    fun confirm(current: ResearchVideoOfflineReportTarget?, onExport: (ResearchVideoOfflineReportTarget) -> Unit): ResearchVideoOfflineReportConfirmation {
        val target = pending ?: return this
        if (target == current) onExport(target)
        return cancel()
    }
}

internal object ResearchVideoOfflineReportCopy {
    const val title = "导出私有视频报告 ZIP"
    const val failure = "视频报告导出未完成，未显示成功；本机视频原文与分析仍保留。请核对所选副本与可用空间。"
    fun confirmation(target: ResearchVideoOfflineReportTarget): String =
        "仅保存到本机 Downloads/ANEB；本次不联网、不上传、不分享。ZIP 可能含私密研究文字与本地引用路径。\n\n" +
            "业务视频导出当前单次原始采集与匹配复核；旧视频仍导出整批已保存视频槽，不应用界面筛选。包含视频原文 JSON、匹配分析 JSON 原字节、UTF-8 离线首页与 SHA-256 清单；不需要人工结论，不含截图、录像或脚本，不打开媒体、不重新计算指标。\n\n" +
            "业务视频原始 STOPPED / 清理未确认与复核声明分开保留；导入复核不是本机媒体认证，不回填原始 null 或旧指标。\n\n" +
            "SAMPLE/OBSERVED 是输入声明，不是真实性认证；NOT_RUN 是未执行，不计播放失败。导出不是新播放观察、时钟核验或网络归因。\n\n" +
            "原文 SHA-256：" + target.sourceId + "\n分析 SHA-256：" + target.analysisId
}

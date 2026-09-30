package com.aneb.probe.research

/** A local navigation address, never a ranking or an independently verified result. */
data class ResearchConclusionTarget(val sourceId: String, val analysisId: String, val cardId: String)

data class ResearchConclusionOpened(
    val source: ResearchDocument,
    val analysis: ResearchAnalysis,
    val card: ResearchConclusion,
)

/** Bind a verified card to the exact batch entry the user clicked, not its normalized label. */
fun ResearchConclusionOpened.belongsTo(batch: ResearchAppProfileBatch): Boolean =
    batch.canShowObservedConclusionEntry && source.id == batch.sourceId &&
        analysis.sourceId == source.id && card.sourceId == source.id &&
        card.analysisId == analysis.id && card.appName == batch.singleAppRawName

/** Lists saved OBSERVED text candidates; opening always rechecks all three original copies. */
class ResearchConclusionIndex(
    private val sources: ResearchRecordStore,
    private val analyses: ResearchAnalysisStore,
    private val cards: ResearchConclusionStore,
) {
    fun list(sourceId: String): List<ResearchConclusionTarget> {
        val source = sources.open(sourceId)
        if (source.recordKind != "OBSERVED" || source.isVideo) return emptyList()
        return analyses.list(source).flatMap { entry ->
            val analysis = entry.analysis?.takeIf { cards.canAttach(source, it) }
                ?: return@flatMap emptyList()
            cards.list(source, analysis).filter { it.error == null }.map {
                ResearchConclusionTarget(source.id, analysis.id, it.id)
            }
        }.sortedWith(compareBy({ it.analysisId }, { it.cardId }))
    }

    fun open(target: ResearchConclusionTarget): ResearchConclusionOpened {
        val source = sources.open(target.sourceId)
        require(source.recordKind == "OBSERVED" && !source.isVideo) { "不是实际观察文本来源" }
        val analysis = analyses.open(source, target.analysisId)
        require(cards.canAttach(source, analysis)) { "分析与单 App 来源不匹配" }
        val card = cards.open(source, analysis, target.cardId)
        return ResearchConclusionOpened(source, analysis, card)
    }
}

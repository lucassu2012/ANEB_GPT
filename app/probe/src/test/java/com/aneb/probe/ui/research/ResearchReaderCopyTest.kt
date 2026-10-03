package com.aneb.probe.ui.research

import com.aneb.probe.research.ResearchBatchImportItemKind
import com.aneb.probe.research.ResearchBatchImportSaveItem
import com.aneb.probe.research.ResearchBatchImportSaveReceipt
import com.aneb.probe.research.ResearchBatchImportSaveStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchReaderCopyTest {
    @Test fun readerIntroductionExplainsExistingRecordsWithoutStartingMeasurement() {
        assertEquals("研究记录与分析", ResearchReaderCopy.title)
        val explanation = ResearchReaderCopy.introduction
        assertTrue(explanation, explanation.contains("已有/手工记录"))
        assertTrue(explanation, explanation.contains("不启动测速"))
        assertTrue(explanation, explanation.contains("不重算指标"))
        assertTrue(explanation, explanation.contains("输入声明"))
    }

    @Test fun importReceiptLabelsProcessingWithoutClaimingNewObservations() {
        val receipt = ResearchBatchImportSaveReceipt(listOf(
            ResearchBatchImportSaveItem(0, "sample.json", ResearchBatchImportSaveStatus.SAVED),
            ResearchBatchImportSaveItem(1, "unmatched.json", ResearchBatchImportSaveStatus.NOT_SAVED),
        ))
        assertEquals("研究记录（输入文件）", ResearchReaderCopy.importKind(ResearchBatchImportItemKind.RAW))
        assertEquals("保存处理成功 1/2 项", ResearchReaderCopy.saveSummary(receipt))
        assertTrue(ResearchReaderCopy.saveNotice, ResearchReaderCopy.saveNotice.contains("已存输入可复用"))
        assertTrue(ResearchReaderCopy.saveNotice, ResearchReaderCopy.saveNotice.contains("不代表新增记录或观察"))
    }

    @Test fun executionAnnotationDoesNotClaimCompletionOrToolVerification() {
        val missing = "NA（未记录或不适用）"
        assertEquals(
            "原记录执行标注：是；可见完成：不确定；遵循指令：$missing",
            ResearchReaderCopy.outcomeSummary("是", "不确定", missing),
        )
        assertTrue(ResearchReaderCopy.outcomeNotice, ResearchReaderCopy.outcomeNotice.contains("可见完成、格式合规、真实工具调用分别判断"))
        assertTrue(ResearchReaderCopy.outcomeNotice, ResearchReaderCopy.outcomeNotice.contains("UNKNOWN/null/NA 不补 0"))
    }

    @Test fun analysisChoiceBelongsToThisBatchAndMustBeSelectedAgain() {
        assertEquals("选择本批分析", ResearchReaderCopy.analysisChoiceLabel)
        val guide = ResearchReaderCopy.analysisGuide(isVideo = false)
        assertFalse(guide, guide.contains("3a"))
        assertTrue(guide, guide.contains("SHA-256") && guide.contains("attempt_id"))
        assertTrue(guide, guide.contains("重开后需重新选择"))
        assertTrue(guide, guide.contains("未选择不是没有回答"))
        assertTrue(guide, guide.contains("不默认选中") && guide.contains("不借用其他批次"))
    }

    @Test fun separateExportConfirmationsIdentifySelectedBytesAndAssociatedRecord() {
        val recordSha = "a".repeat(64)
        val analysisSha = "b".repeat(64)
        assertEquals("导出整批研究记录 JSON", ResearchReaderCopy.recordExportLabel)
        assertEquals("导出所选分析 JSON", ResearchReaderCopy.analysisExportLabel)
        val record = ResearchReaderCopy.exportConfirmation("SAMPLE", recordSha, null)
        val analysis = ResearchReaderCopy.exportConfirmation("SAMPLE", recordSha, analysisSha)
        assertTrue(record, record.contains("类型：整批研究记录 JSON") && record.contains(recordSha))
        assertFalse(record, record.contains(analysisSha))
        assertTrue(analysis, analysis.contains("类型：所选分析 JSON") && analysis.contains(analysisSha))
        assertTrue(analysis, analysis.contains("关联研究记录 SHA-256：$recordSha"))
        assertTrue(analysis, analysis.contains("不会打包录像") && analysis.contains("不会自动上传或分享"))
    }
}

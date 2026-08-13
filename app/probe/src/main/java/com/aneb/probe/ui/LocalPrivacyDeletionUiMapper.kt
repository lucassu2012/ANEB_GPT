package com.aneb.probe.ui

import com.aneb.probe.privacy.LocalPrivacyDeletionOperationState
import com.aneb.probe.privacy.LocalPrivacyDeletionResult
import com.aneb.probe.privacy.LocalPrivacyDomainDeletionResult
import com.aneb.probe.privacy.LocalPrivacyExact8Receipt

internal data class LocalPrivacyDeletionUiCopy(
    val statusText: String,
    val issueLines: List<String> = emptyList(),
)

internal object LocalPrivacyDeletionUiMapper {
    fun map(state: LocalPrivacyDeletionOperationState): LocalPrivacyDeletionUiCopy = when (state) {
        LocalPrivacyDeletionOperationState.Idle ->
            LocalPrivacyDeletionUiCopy("尚未开始清除本机数据")
        LocalPrivacyDeletionOperationState.Running ->
            LocalPrivacyDeletionUiCopy("正在清除本机数据")
        LocalPrivacyDeletionOperationState.Cancelled ->
            LocalPrivacyDeletionUiCopy("清除已取消，未确认本机数据已清除")
        LocalPrivacyDeletionOperationState.Failed ->
            LocalPrivacyDeletionUiCopy("暂时无法清除本机数据，请稍后重试")
        is LocalPrivacyDeletionOperationState.Finished -> mapResult(state.result)
    }

    private fun mapResult(result: LocalPrivacyDeletionResult): LocalPrivacyDeletionUiCopy =
        when (result) {
            LocalPrivacyDeletionResult.ConfirmationRequired ->
                LocalPrivacyDeletionUiCopy("请先确认清除本机数据")
            LocalPrivacyDeletionResult.BlockedByActiveRun ->
                LocalPrivacyDeletionUiCopy("测试进行中，结束后再清除本机数据")
            LocalPrivacyDeletionResult.BlockedByOwnedMutation ->
                LocalPrivacyDeletionUiCopy("本机数据正在更新，请稍后再试")
            LocalPrivacyDeletionResult.DeletionInProgress ->
                LocalPrivacyDeletionUiCopy("正在清除本机数据，请勿重复操作")
            is LocalPrivacyDeletionResult.Complete ->
                LocalPrivacyDeletionUiCopy("已确认 8/8 个本机范围为空")
            is LocalPrivacyDeletionResult.Incomplete -> mapIncomplete(result.receipt)
        }

    private fun mapIncomplete(receipt: LocalPrivacyExact8Receipt): LocalPrivacyDeletionUiCopy {
        val domains = listOf(
            "结果上传授权" to receipt.consent,
            "本机测试数据库" to receipt.room,
            "本机设置" to receipt.settings,
            "外部诊断日志" to receipt.externalNetlog,
            "内部诊断日志" to receipt.internalNetlog,
            "网关凭据" to receipt.gatewayVault,
            "主测试运行状态" to receipt.primaryRuntime,
            "专项测试运行状态" to receipt.specialRuntime,
        )
        val confirmedCount = domains.count { (_, domainResult) -> domainResult.isComplete }
        val issues = domains.mapNotNull { (label, domainResult) ->
            when (domainResult) {
                LocalPrivacyDomainDeletionResult.CLEARED,
                LocalPrivacyDomainDeletionResult.ALREADY_ABSENT,
                -> null
                LocalPrivacyDomainDeletionResult.RESIDUE_CONFIRMED ->
                    "$label：仍检测到残留"
                LocalPrivacyDomainDeletionResult.STATE_UNAVAILABLE ->
                    "$label：当前无法确认"
            }
        }
        return LocalPrivacyDeletionUiCopy(
            statusText = "已确认 $confirmedCount/8 个本机范围为空",
            issueLines = issues,
        )
    }
}

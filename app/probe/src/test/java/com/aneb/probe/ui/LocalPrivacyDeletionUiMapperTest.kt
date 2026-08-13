package com.aneb.probe.ui

import com.aneb.probe.privacy.LocalPrivacyDeletionOperationState
import com.aneb.probe.privacy.LocalPrivacyDeletionResult
import com.aneb.probe.privacy.LocalPrivacyDomainDeletionResult
import com.aneb.probe.privacy.LocalPrivacyExact8Receipt
import com.aneb.probe.privacy.LocalPrivacyOwnedDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalPrivacyDeletionUiMapperTest {
    @Test
    fun `incomplete deletion copy exposes only friendly domain issues`() {
        val receipt = LocalPrivacyExact8Receipt(
            consent = LocalPrivacyDomainDeletionResult.CLEARED,
            room = LocalPrivacyDomainDeletionResult.STATE_UNAVAILABLE,
            settings = LocalPrivacyDomainDeletionResult.ALREADY_ABSENT,
            externalNetlog = LocalPrivacyDomainDeletionResult.CLEARED,
            internalNetlog = LocalPrivacyDomainDeletionResult.CLEARED,
            gatewayVault = LocalPrivacyDomainDeletionResult.RESIDUE_CONFIRMED,
            primaryRuntime = LocalPrivacyDomainDeletionResult.ALREADY_ABSENT,
            specialRuntime = LocalPrivacyDomainDeletionResult.CLEARED,
        )

        val copy = LocalPrivacyDeletionUiMapper.map(
            LocalPrivacyDeletionOperationState.Finished(
                LocalPrivacyDeletionResult.Incomplete(receipt),
            ),
        )

        assertEquals("已确认 6/8 个本机范围为空", copy.statusText)
        assertEquals(
            listOf(
                "本机测试数据库：当前无法确认",
                "网关凭据：仍检测到残留",
            ),
            copy.issueLines,
        )
        val rendered = buildString {
            append(copy.statusText)
            copy.issueLines.forEach(::append)
        }
        val forbidden = buildList {
            addAll(LocalPrivacyOwnedDomain.entries.map { it.wireValue })
            addAll(LocalPrivacyDomainDeletionResult.entries.map { it.wireValue })
            addAll(listOf("Exception", "Throwable", "java."))
        }
        forbidden.forEach { token ->
            assertFalse("unsafe token rendered: $token", rendered.contains(token))
        }
    }
}

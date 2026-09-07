package com.aneb.probe.prototype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrototypeCaptureAuthorityTest {
    @Test
    fun campaignBuilderEmitsCanonicalCompactLanAuthority() {
        val actual = PrototypeCaptureAuthority.buildCampaign(
            PrototypeCaptureAuthority.CampaignCapture(
                campaignId = "campaign-001",
                campaignStartedAtUtc = "2026-09-01T01:02:03Z",
                campaignEndedAtUtc = "2026-09-01T01:03:04.500Z",
                sourceCommit = "0123456789abcdef0123456789abcdef01234567",
                androidVersionName = "0.2.0",
                androidVersionCode = 20,
                apkSha256 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                deviceManufacturer = "HUAWEI",
                deviceModel = "P40 Pro",
                deviceOsRelease = "12",
                deviceSdkInt = 31,
                transportKind = "lan",
            ),
        )

        assertEquals(
            """{"schema_version":"aneb-prototype-capture-authority-0.1","campaign_id":"campaign-001","campaign_started_at_utc":"2026-09-01T01:02:03Z","campaign_ended_at_utc":"2026-09-01T01:03:04.500Z","product":{"version":"prototype-0.1","source_commit":"0123456789abcdef0123456789abcdef01234567"},"android":{"version_name":"0.2.0","version_code":20,"apk_sha256":"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"},"device":{"manufacturer":"HUAWEI","model":"P40 Pro","os_release":"12","sdk_int":31},"transport":{"kind":"lan","acceptance_eligible":true}}""",
            actual,
        )
    }

    @Test
    fun campaignBuilderEmitsCanonicalAdbReverseAuthority() {
        val actual = PrototypeCaptureAuthority.buildCampaign(
            canonicalCampaignCapture().copy(transportKind = "adb_reverse"),
        )

        assertEquals(
            """{"schema_version":"aneb-prototype-capture-authority-0.1","campaign_id":"campaign-001","campaign_started_at_utc":"2026-09-01T01:02:03Z","campaign_ended_at_utc":"2026-09-01T01:03:04.500Z","product":{"version":"prototype-0.1","source_commit":"0123456789abcdef0123456789abcdef01234567"},"android":{"version_name":"0.2.0","version_code":20,"apk_sha256":"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"},"device":{"manufacturer":"HUAWEI","model":"P40 Pro","os_release":"12","sdk_int":31},"transport":{"kind":"adb_reverse","acceptance_eligible":false}}""",
            actual,
        )
    }

    @Test
    fun campaignBuilderRejectsNoncanonicalOrUnsafeCaptureAuthority() {
        val canonical = canonicalCampaignCapture()
        val invalidCaptures = listOf(
            canonical.copy(campaignId = ""),
            canonical.copy(campaignStartedAtUtc = "not-an-instant"),
            canonical.copy(campaignStartedAtUtc = "2026-09-01T01:02:03+00:00"),
            canonical.copy(campaignEndedAtUtc = "2026-09-01T01:02:02Z"),
            canonical.copy(sourceCommit = "A".repeat(40)),
            canonical.copy(sourceCommit = "a".repeat(39)),
            canonical.copy(androidVersionName = ""),
            canonical.copy(androidVersionName = "0.2.0\nforged"),
            canonical.copy(androidVersionCode = 0),
            canonical.copy(apkSha256 = "A".repeat(64)),
            canonical.copy(apkSha256 = "a".repeat(63)),
            canonical.copy(deviceManufacturer = ""),
            canonical.copy(deviceModel = "P40\u0000forged"),
            canonical.copy(deviceOsRelease = "\t"),
            canonical.copy(deviceSdkInt = 0),
            canonical.copy(transportKind = "unknown"),
        )

        invalidCaptures.forEach { capture ->
            assertThrows(IllegalArgumentException::class.java) {
                PrototypeCaptureAuthority.buildCampaign(capture)
            }
        }
    }

    @Test
    fun runBuilderEmitsCanonicalCompactAttemptAuthority() {
        val actual = PrototypeCaptureAuthority.buildRun(
            PrototypeCaptureAuthority.RunCapture(
                campaignId = "campaign-001",
                runId = "run-001",
                runIndex = 1,
                state = PrototypeCaptureAuthority.RunState.COMPLETE,
                clockDomainId = "domain-001",
                t0Nanos = 123_456_789L,
                attemptStartedAtUtc = "2026-09-01T01:02:03Z",
                attemptEndedAtUtc = "2026-09-01T01:02:09.250Z",
            ),
        )

        assertEquals(
            """{"schema_version":"aneb-prototype-run-authority-0.1","campaign_id":"campaign-001","run_id":"run-001","run_index":1,"clock_domain_id":"domain-001","t0_nanos":123456789,"attempt_started_at_utc":"2026-09-01T01:02:03Z","attempt_ended_at_utc":"2026-09-01T01:02:09.250Z"}""",
            actual,
        )
    }

    @Test
    fun runBuilderEmitsCanonicalNotStartedAuthority() {
        val actual = PrototypeCaptureAuthority.buildRun(
            PrototypeCaptureAuthority.RunCapture(
                campaignId = "campaign-001",
                runId = "run-003",
                runIndex = 3,
                state = PrototypeCaptureAuthority.RunState.NOT_STARTED,
                clockDomainId = "domain-003",
                t0Nanos = null,
                attemptStartedAtUtc = null,
                attemptEndedAtUtc = null,
            ),
        )

        assertEquals(
            """{"schema_version":"aneb-prototype-run-authority-0.1","campaign_id":"campaign-001","run_id":"run-003","run_index":3,"clock_domain_id":"domain-003","t0_nanos":null,"attempt_started_at_utc":null,"attempt_ended_at_utc":null}""",
            actual,
        )
    }

    @Test
    fun notStartedRunRejectsAttemptClockAuthorityOrMissingDomain() {
        val canonical = PrototypeCaptureAuthority.RunCapture(
            campaignId = "campaign-001",
            runId = "run-003",
            runIndex = 3,
            state = PrototypeCaptureAuthority.RunState.NOT_STARTED,
            clockDomainId = "domain-003",
            t0Nanos = null,
            attemptStartedAtUtc = null,
            attemptEndedAtUtc = null,
        )
        val invalidCaptures = listOf(
            canonical.copy(clockDomainId = ""),
            canonical.copy(t0Nanos = 0L),
            canonical.copy(attemptStartedAtUtc = "2026-09-01T01:02:03Z"),
            canonical.copy(attemptEndedAtUtc = "2026-09-01T01:02:04Z"),
        )

        invalidCaptures.forEach { capture ->
            assertThrows(IllegalArgumentException::class.java) {
                PrototypeCaptureAuthority.buildRun(capture)
            }
        }
    }

    @Test
    fun attemptedStatesRequireCanonicalCompleteClockAuthority() {
        val canonical = PrototypeCaptureAuthority.RunCapture(
            campaignId = "campaign-001",
            runId = "run-001",
            runIndex = 1,
            state = PrototypeCaptureAuthority.RunState.COMPLETE,
            clockDomainId = "domain-001",
            t0Nanos = 123_456_789L,
            attemptStartedAtUtc = "2026-09-01T01:02:03Z",
            attemptEndedAtUtc = "2026-09-01T01:02:09.250Z",
        )
        val expectedJson =
            """{"schema_version":"aneb-prototype-run-authority-0.1","campaign_id":"campaign-001","run_id":"run-001","run_index":1,"clock_domain_id":"domain-001","t0_nanos":123456789,"attempt_started_at_utc":"2026-09-01T01:02:03Z","attempt_ended_at_utc":"2026-09-01T01:02:09.250Z"}"""

        listOf(
            PrototypeCaptureAuthority.RunState.COMPLETE,
            PrototypeCaptureAuthority.RunState.INTERRUPTED,
            PrototypeCaptureAuthority.RunState.INVALID,
            PrototypeCaptureAuthority.RunState.CANCELLED,
        ).forEach { state ->
            assertEquals(expectedJson, PrototypeCaptureAuthority.buildRun(canonical.copy(state = state)))
        }

        val invalidCaptures = listOf(
            canonical.copy(campaignId = ""),
            canonical.copy(runId = ""),
            canonical.copy(runIndex = 0),
            canonical.copy(clockDomainId = " "),
            canonical.copy(t0Nanos = null),
            canonical.copy(t0Nanos = -1L),
            canonical.copy(attemptStartedAtUtc = null),
            canonical.copy(attemptEndedAtUtc = null),
            canonical.copy(attemptStartedAtUtc = "not-an-instant"),
            canonical.copy(attemptStartedAtUtc = "2026-09-01T01:02:03+00:00"),
            canonical.copy(attemptEndedAtUtc = "2026-09-01T01:02:09.000Z"),
            canonical.copy(attemptEndedAtUtc = "2026-09-01T01:02:02Z"),
        )

        invalidCaptures.forEach { capture ->
            assertThrows(IllegalArgumentException::class.java) {
                PrototypeCaptureAuthority.buildRun(capture)
            }
        }
    }

    private fun canonicalCampaignCapture() = PrototypeCaptureAuthority.CampaignCapture(
        campaignId = "campaign-001",
        campaignStartedAtUtc = "2026-09-01T01:02:03Z",
        campaignEndedAtUtc = "2026-09-01T01:03:04.500Z",
        sourceCommit = "0123456789abcdef0123456789abcdef01234567",
        androidVersionName = "0.2.0",
        androidVersionCode = 20,
        apkSha256 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
        deviceManufacturer = "HUAWEI",
        deviceModel = "P40 Pro",
        deviceOsRelease = "12",
        deviceSdkInt = 31,
        transportKind = "lan",
    )
}

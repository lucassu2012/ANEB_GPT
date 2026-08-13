package com.aneb.probe.privacy

import com.aneb.probe.engine.GatewayCredentialPrivacyDeletionResult
import com.aneb.probe.engine.ResultUploadPrivacyResetResult
import com.aneb.probe.engine.ServiceRetainedStateDeletionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPrivacyDeletionAssemblyTest {
    @Test
    fun `production assembly shares the process gate and maps every fixed domain once`() {
        runBlocking {
            val calls = LocalPrivacyOwnedDomain.entries.associateWith { 0 }.toMutableMap()
            val order = mutableListOf<LocalPrivacyOwnedDomain>()
            fun called(domain: LocalPrivacyOwnedDomain) {
                calls[domain] = checkNotNull(calls[domain]) + 1
                order += domain
            }

            val emptyRows = OwnedRoomTable.entries.associateWith { 0L }
            val emptyRoom = OwnedRoomInventory(rows = emptyRows, sequences = emptyMap())
            val coordinator = LocalPrivacyDeletionAssembly.create(
                LocalPrivacyDeletionDomainOperations(
                    consent = {
                        called(LocalPrivacyOwnedDomain.CONSENT)
                        ResultUploadPrivacyResetResult.Cleared
                    },
                    room = {
                        called(LocalPrivacyOwnedDomain.ROOM)
                        OwnedRoomDeletionResult.Deleted(
                            before = emptyRoom.copy(
                                rows = emptyRows + (OwnedRoomTable.TEST_RUN to 1L),
                            ),
                            after = emptyRoom,
                        )
                    },
                    settings = {
                        called(LocalPrivacyOwnedDomain.SETTINGS)
                        ProbeSettingsDeletionResult.AlreadyAbsent
                    },
                    externalNetlog = {
                        called(LocalPrivacyOwnedDomain.EXTERNAL_NETLOG)
                        ExactNetlogDeletionResult.StateUnavailable
                    },
                    internalNetlog = {
                        called(LocalPrivacyOwnedDomain.INTERNAL_NETLOG)
                        ExactNetlogDeletionResult.Cleared(fileCount = 1)
                    },
                    gatewayVault = {
                        called(LocalPrivacyOwnedDomain.GATEWAY_VAULT)
                        GatewayCredentialPrivacyDeletionResult.Cleared(referenceCount = 2)
                    },
                    primaryRuntime = {
                        called(LocalPrivacyOwnedDomain.PRIMARY_RUNTIME)
                        ServiceRetainedStateDeletionResult.ResidueConfirmed
                    },
                    specialRuntime = {
                        called(LocalPrivacyOwnedDomain.SPECIAL_RUNTIME)
                        ServiceRetainedStateDeletionResult.Cleared
                    },
                ),
            )

            val activeRun = LocalPrivacyProcessOperations.operationGate.tryAcquireRun(
                ProbeOwnedRun.PRIMARY,
            )
            assertTrue(activeRun is LocalPrivacyRunAdmission.Acquired)
            try {
                assertEquals(
                    LocalPrivacyDeletionResult.BlockedByActiveRun,
                    coordinator.delete(confirmed = true),
                )
                assertTrue(calls.values.all { it == 0 })
                assertTrue(order.isEmpty())
            } finally {
                (activeRun as LocalPrivacyRunAdmission.Acquired).lease.close()
            }

            assertEquals(
                LocalPrivacyDeletionResult.Incomplete(
                    LocalPrivacyExact8Receipt(
                        consent = LocalPrivacyDomainDeletionResult.CLEARED,
                        room = LocalPrivacyDomainDeletionResult.CLEARED,
                        settings = LocalPrivacyDomainDeletionResult.ALREADY_ABSENT,
                        externalNetlog = LocalPrivacyDomainDeletionResult.STATE_UNAVAILABLE,
                        internalNetlog = LocalPrivacyDomainDeletionResult.CLEARED,
                        gatewayVault = LocalPrivacyDomainDeletionResult.CLEARED,
                        primaryRuntime = LocalPrivacyDomainDeletionResult.RESIDUE_CONFIRMED,
                        specialRuntime = LocalPrivacyDomainDeletionResult.CLEARED,
                    ),
                ),
                coordinator.delete(confirmed = true),
            )
            assertEquals(LocalPrivacyOwnedDomain.entries.toList(), order)
            assertTrue(calls.values.all { it == 1 })
        }
    }
}

package com.aneb.probe.privacy

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPrivacyExactDomainDeletionContractTest {
    @Test
    fun `confirmed deletion invokes every fixed domain exactly once and returns eight typed receipts`() =
        runBlocking {
            val calls = LocalPrivacyOwnedDomain.entries.associateWith { AtomicInteger() }
            val order = mutableListOf<LocalPrivacyOwnedDomain>()

            fun port(
                domain: LocalPrivacyOwnedDomain,
                result: LocalPrivacyDomainDeletionResult,
            ) = LocalPrivacyDomainDeletionPort {
                calls.getValue(domain).incrementAndGet()
                order += domain
                result
            }

            val expectedReceipt = LocalPrivacyExact8Receipt(
                consent = LocalPrivacyDomainDeletionResult.CLEARED,
                room = LocalPrivacyDomainDeletionResult.CLEARED,
                settings = LocalPrivacyDomainDeletionResult.ALREADY_ABSENT,
                externalNetlog = LocalPrivacyDomainDeletionResult.STATE_UNAVAILABLE,
                internalNetlog = LocalPrivacyDomainDeletionResult.CLEARED,
                gatewayVault = LocalPrivacyDomainDeletionResult.CLEARED,
                primaryRuntime = LocalPrivacyDomainDeletionResult.RESIDUE_CONFIRMED,
                specialRuntime = LocalPrivacyDomainDeletionResult.CLEARED,
            )
            val coordinator = LocalPrivacyDeletionCoordinator(
                operationGate = LocalPrivacyOperationGate(),
                consentDeletion = port(
                    LocalPrivacyOwnedDomain.CONSENT,
                    expectedReceipt.consent,
                ),
                roomDeletion = port(LocalPrivacyOwnedDomain.ROOM, expectedReceipt.room),
                settingsDeletion = port(
                    LocalPrivacyOwnedDomain.SETTINGS,
                    expectedReceipt.settings,
                ),
                externalNetlogDeletion = port(
                    LocalPrivacyOwnedDomain.EXTERNAL_NETLOG,
                    expectedReceipt.externalNetlog,
                ),
                internalNetlogDeletion = port(
                    LocalPrivacyOwnedDomain.INTERNAL_NETLOG,
                    expectedReceipt.internalNetlog,
                ),
                gatewayVaultDeletion = port(
                    LocalPrivacyOwnedDomain.GATEWAY_VAULT,
                    expectedReceipt.gatewayVault,
                ),
                primaryRuntimeDeletion = port(
                    LocalPrivacyOwnedDomain.PRIMARY_RUNTIME,
                    expectedReceipt.primaryRuntime,
                ),
                specialRuntimeDeletion = port(
                    LocalPrivacyOwnedDomain.SPECIAL_RUNTIME,
                    expectedReceipt.specialRuntime,
                ),
            )

            assertEquals(
                LocalPrivacyDeletionResult.Incomplete(expectedReceipt),
                coordinator.delete(confirmed = true),
            )
            assertEquals(LocalPrivacyOwnedDomain.entries, order)
            LocalPrivacyOwnedDomain.entries.forEach { domain ->
                assertEquals("$domain invocation count", 1, calls.getValue(domain).get())
            }
        }

    @Test
    fun `ordinary domain failure becomes unavailable while every later domain still runs once`() =
        runBlocking {
            val calls = LocalPrivacyOwnedDomain.entries.associateWith { AtomicInteger() }
            val order = mutableListOf<LocalPrivacyOwnedDomain>()

            fun port(
                domain: LocalPrivacyOwnedDomain,
                action: suspend () -> LocalPrivacyDomainDeletionResult,
            ) = LocalPrivacyDomainDeletionPort {
                calls.getValue(domain).incrementAndGet()
                order += domain
                action()
            }

            val expectedReceipt = LocalPrivacyExact8Receipt(
                consent = LocalPrivacyDomainDeletionResult.CLEARED,
                room = LocalPrivacyDomainDeletionResult.STATE_UNAVAILABLE,
                settings = LocalPrivacyDomainDeletionResult.CLEARED,
                externalNetlog = LocalPrivacyDomainDeletionResult.CLEARED,
                internalNetlog = LocalPrivacyDomainDeletionResult.CLEARED,
                gatewayVault = LocalPrivacyDomainDeletionResult.CLEARED,
                primaryRuntime = LocalPrivacyDomainDeletionResult.CLEARED,
                specialRuntime = LocalPrivacyDomainDeletionResult.CLEARED,
            )
            val coordinator = LocalPrivacyDeletionCoordinator(
                operationGate = LocalPrivacyOperationGate(),
                consentDeletion = port(LocalPrivacyOwnedDomain.CONSENT) {
                    expectedReceipt.consent
                },
                roomDeletion = port(LocalPrivacyOwnedDomain.ROOM) {
                    throw IllegalStateException("domain failure sentinel")
                },
                settingsDeletion = port(LocalPrivacyOwnedDomain.SETTINGS) {
                    expectedReceipt.settings
                },
                externalNetlogDeletion = port(LocalPrivacyOwnedDomain.EXTERNAL_NETLOG) {
                    expectedReceipt.externalNetlog
                },
                internalNetlogDeletion = port(LocalPrivacyOwnedDomain.INTERNAL_NETLOG) {
                    expectedReceipt.internalNetlog
                },
                gatewayVaultDeletion = port(LocalPrivacyOwnedDomain.GATEWAY_VAULT) {
                    expectedReceipt.gatewayVault
                },
                primaryRuntimeDeletion = port(LocalPrivacyOwnedDomain.PRIMARY_RUNTIME) {
                    expectedReceipt.primaryRuntime
                },
                specialRuntimeDeletion = port(LocalPrivacyOwnedDomain.SPECIAL_RUNTIME) {
                    expectedReceipt.specialRuntime
                },
            )

            assertEquals(
                LocalPrivacyDeletionResult.Incomplete(expectedReceipt),
                runCatching { coordinator.delete(confirmed = true) }.getOrNull(),
            )
            assertEquals(LocalPrivacyOwnedDomain.entries, order)
            LocalPrivacyOwnedDomain.entries.forEach { domain ->
                assertEquals("$domain invocation count", 1, calls.getValue(domain).get())
            }
        }

    @Test
    fun `domain cancellation propagates unchanged stops later domains and releases deletion lease`() =
        runBlocking {
            val gate = LocalPrivacyOperationGate()
            val calls = LocalPrivacyOwnedDomain.entries.associateWith { AtomicInteger() }
            val marker = CancellationException("cancellation marker")
            val priorSuppressed = IllegalStateException("prior suppressed marker")
            marker.addSuppressed(priorSuppressed)

            fun port(
                domain: LocalPrivacyOwnedDomain,
                action: suspend () -> LocalPrivacyDomainDeletionResult = {
                    LocalPrivacyDomainDeletionResult.CLEARED
                },
            ) = LocalPrivacyDomainDeletionPort {
                calls.getValue(domain).incrementAndGet()
                action()
            }

            val coordinator = LocalPrivacyDeletionCoordinator(
                operationGate = gate,
                consentDeletion = port(LocalPrivacyOwnedDomain.CONSENT),
                roomDeletion = port(LocalPrivacyOwnedDomain.ROOM) { throw marker },
                settingsDeletion = port(LocalPrivacyOwnedDomain.SETTINGS),
                externalNetlogDeletion = port(LocalPrivacyOwnedDomain.EXTERNAL_NETLOG),
                internalNetlogDeletion = port(LocalPrivacyOwnedDomain.INTERNAL_NETLOG),
                gatewayVaultDeletion = port(LocalPrivacyOwnedDomain.GATEWAY_VAULT),
                primaryRuntimeDeletion = port(LocalPrivacyOwnedDomain.PRIMARY_RUNTIME),
                specialRuntimeDeletion = port(LocalPrivacyOwnedDomain.SPECIAL_RUNTIME),
            )

            val caught = try {
                coordinator.delete(confirmed = true)
                null
            } catch (cancellation: CancellationException) {
                cancellation
            }

            assertSame(marker, caught)
            assertEquals(1, marker.suppressed.size)
            assertSame(priorSuppressed, marker.suppressed.single())
            assertEquals(1, calls.getValue(LocalPrivacyOwnedDomain.CONSENT).get())
            assertEquals(1, calls.getValue(LocalPrivacyOwnedDomain.ROOM).get())
            LocalPrivacyOwnedDomain.entries.drop(2).forEach { domain ->
                assertEquals("$domain invocation count", 0, calls.getValue(domain).get())
            }
            val nextAdmission = gate.tryAcquireDeletion()
            assertTrue(nextAdmission is LocalPrivacyDeletionAdmission.Acquired)
            (nextAdmission as LocalPrivacyDeletionAdmission.Acquired).lease.close()
        }
}

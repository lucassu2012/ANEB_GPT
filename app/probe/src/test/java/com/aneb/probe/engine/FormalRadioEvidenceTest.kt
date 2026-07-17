package com.aneb.probe.engine

import com.aneb.probe.data.EnvEvent
import com.aneb.probe.data.EnvEventType
import com.aneb.probe.radio.RadioSample
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormalRadioEvidenceTest {
    @Test fun collectedEvidenceSortsSamplesPrefersLatestFreshAndRemovesLocation() {
        val evidence = FormalRadioEvidence.from(
            rawSamples = listOf(
                sample(ts = 30, stale = true, rsrp = -120, lat = 22.5, lon = 114.0),
                sample(ts = 10, stale = false, rsrp = -95, lat = 22.4, lon = 113.9),
                sample(ts = 20, stale = false, rsrp = -100, lat = 22.45, lon = 113.95),
            ),
            events = listOf(
                EnvEvent(25, EnvEventType.RAT_CHANGE, "lte -> nr"),
                EnvEvent(15, EnvEventType.CELL_CHANGE, "pci 1 -> 2"),
            ),
        )

        assertEquals("collected", evidence.collectionStatus)
        assertEquals(listOf(10L, 20L, 30L), evidence.samples.map { it.tsNanos })
        assertEquals(20L, evidence.representative?.tsNanos)
        assertEquals(listOf(15L, 25L), evidence.events.map { it.tsNanos })

        val context = evidence.contextJson()
        assertEquals(-100, context.getValue("rsrp_dbm").jsonPrimitive.content.toInt())
        assertEquals(3, context.getValue("sample_count").jsonPrimitive.content.toInt())
        assertEquals(3, context.getValue("samples").jsonArray.size)
        val serialized = context.toString()
        assertFalse(serialized.contains("lat", ignoreCase = true))
        assertFalse(serialized.contains("lon", ignoreCase = true))
        assertFalse(serialized.contains("accuracy", ignoreCase = true))

        val ref = evidence.evidenceRefJson()
        assertEquals("#/context/radio/samples", ref.getValue("uri").jsonPrimitive.content)
        assertEquals("location_removed", ref.getValue("redaction").jsonPrimitive.content)
        assertEquals(3, ref.getValue("record_count").jsonPrimitive.content.toInt())
        val eventJson = evidence.environmentEventsJson()
        assertEquals("cell_change", eventJson[0].jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test fun permissionDeniedIsExplicitAndDegradedSampleStaysLocalOnly() {
        val evidence = FormalRadioEvidence.from(
            rawSamples = listOf(sample(ts = 10, networkType = "permission_denied", subId = -1)),
            events = emptyList(),
        )

        assertEquals("permission_denied", evidence.collectionStatus)
        assertEquals("android_radio_permissions_denied", evidence.unavailableReason)
        assertTrue(evidence.samples.isEmpty())
        assertEquals(1, evidence.rawSamples.size)
        assertNull(evidence.representative)

        val context = evidence.contextJson()
        assertEquals(0, context.getValue("sample_count").jsonPrimitive.content.toInt())
        assertTrue(context.getValue("samples").jsonArray.isEmpty())
        assertEquals("permission_denied", context.getValue("collection_status").jsonPrimitive.content)
    }

    @Test fun unavailableAndNoSampleAreDistinct() {
        val unavailable = FormalRadioEvidence.from(
            listOf(sample(ts = 1, networkType = "telephony_unavailable", subId = -1)),
            emptyList(),
        )
        val absent = FormalRadioEvidence.from(emptyList(), emptyList())

        assertEquals("unavailable", unavailable.collectionStatus)
        assertEquals("telephony_service_unavailable", unavailable.unavailableReason)
        assertEquals("not_collected", absent.collectionStatus)
        assertEquals("no_radio_sample_before_result_finalization", absent.unavailableReason)
    }

    private fun sample(
        ts: Long,
        stale: Boolean = false,
        rsrp: Int? = -100,
        networkType: String = "lte",
        subId: Int = 1,
        lat: Double? = null,
        lon: Double? = null,
    ) = RadioSample(
        tsNanos = ts,
        cellTsNanos = ts - 1,
        stale = stale,
        subId = subId,
        subSwitched = false,
        networkType = networkType,
        overrideType = "none",
        nrState = "lte",
        rat = "lte",
        pci = 123,
        tac = 456,
        arfcn = 1800,
        rsrp = rsrp,
        rsrq = -10,
        sinr = 15,
        operatorName = "Test Operator",
        lat = lat,
        lon = lon,
        accuracyM = 5.0,
    )
}

internal fun collectedRadioEvidenceFixture() = FormalRadioEvidence.from(
    rawSamples = listOf(
        RadioSample(
            tsNanos = 10,
            cellTsNanos = 9,
            stale = false,
            subId = 1,
            subSwitched = false,
            networkType = "lte",
            overrideType = "none",
            nrState = "lte",
            rat = "lte",
            pci = 123,
            tac = 456,
            arfcn = 1800,
            rsrp = -100,
            rsrq = -10,
            sinr = 15,
            operatorName = "Test Operator",
            lat = 22.5,
            lon = 114.0,
            accuracyM = 5.0,
        ),
    ),
    events = listOf(EnvEvent(11, EnvEventType.RAT_CHANGE, "lte -> nr")),
)

internal fun radioOnlyProfileFixture(
    profileId: String,
    modeId: String,
    metricId: String,
) = ScenarioProfile(
    profileId = profileId,
    version = "1.0.0",
    contractVersion = ScenarioProfile.CONTRACT_V2,
    modeId = modeId,
    claimScope = "application_end_to_end_to_probe_node",
    measurementCatalogId = "test-radio-catalog-v1",
    measurements = listOf(
        ProfileMeasurement(
            metricId = metricId,
            label = "Public radio covariate",
            domain = "radio_covariate",
            unit = "mixed",
            measurementLevel = "exact",
            formulaId = "public-radio-snapshot-v1",
            aggregation = "time_series",
            direction = "descriptive",
            requiredForScore = false,
            minimumSampleCount = 1,
            targetRole = "covariate",
            qualityTarget = null,
        ),
    ),
)

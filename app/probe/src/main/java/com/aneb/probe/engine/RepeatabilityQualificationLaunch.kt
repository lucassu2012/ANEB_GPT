package com.aneb.probe.engine

internal data class RepeatabilityQualificationLaunchRequest(
    val debug: Boolean,
    val autorun: Boolean,
    val stageId: String?,
    val transport: TestEngine.TransportMode,
    val testMode: AnebTestMode,
    val policyId: String?,
    val policyVersion: String?,
    val policySha256: String?,
    val profileId: String?,
    val profileVersion: String?,
    val profileSha256: String?,
    val runtimePlanSha256: String?,
)

internal data class RepeatabilityQualificationLaunchWireData(
    val requested: Boolean,
    val stageId: String? = null,
    val policyId: String? = null,
    val policyVersion: String? = null,
    val policySha256: String? = null,
    val profileId: String? = null,
    val profileVersion: String? = null,
    val profileSha256: String? = null,
    val runtimePlanSha256: String? = null,
)

internal class RepeatabilityQualificationActivityHandoff {
    private var wire = RepeatabilityQualificationLaunchWireData(requested = false)

    fun replace(next: RepeatabilityQualificationLaunchWireData) {
        wire = next
    }

    fun take(): RepeatabilityQualificationLaunchWireData = wire.also {
        wire = RepeatabilityQualificationLaunchWireData(requested = false)
    }
}

internal object RepeatabilityQualificationLaunchExtras {
    private const val REQUESTED = "qualification_requested"
    private const val STAGE_ID = "qualification_stage_id"
    private const val POLICY_ID = "qualification_policy_id"
    private const val POLICY_VERSION = "qualification_policy_version"
    private const val POLICY_SHA256 = "qualification_policy_sha256"
    private const val PROFILE_ID = "qualification_profile_id"
    private const val PROFILE_VERSION = "qualification_profile_version"
    private const val PROFILE_SHA256 = "qualification_profile_sha256"
    private const val RUNTIME_PLAN_SHA256 = "qualification_runtime_plan_sha256"

    private val keys = listOf(
        REQUESTED,
        STAGE_ID,
        POLICY_ID,
        POLICY_VERSION,
        POLICY_SHA256,
        PROFILE_ID,
        PROFILE_VERSION,
        PROFILE_SHA256,
        RUNTIME_PLAN_SHA256,
    )

    fun write(
        wire: RepeatabilityQualificationLaunchWireData,
        putBoolean: (String, Boolean) -> Unit,
        putString: (String, String?) -> Unit,
    ) {
        putBoolean(REQUESTED, wire.requested)
        putString(STAGE_ID, wire.stageId)
        putString(POLICY_ID, wire.policyId)
        putString(POLICY_VERSION, wire.policyVersion)
        putString(POLICY_SHA256, wire.policySha256)
        putString(PROFILE_ID, wire.profileId)
        putString(PROFILE_VERSION, wire.profileVersion)
        putString(PROFILE_SHA256, wire.profileSha256)
        putString(RUNTIME_PLAN_SHA256, wire.runtimePlanSha256)
    }

    fun readAndRemove(
        getBoolean: (String, Boolean) -> Boolean,
        getString: (String) -> String?,
        remove: (String) -> Unit,
    ): RepeatabilityQualificationLaunchWireData = try {
        RepeatabilityQualificationLaunchWireData(
            requested = getBoolean(REQUESTED, false),
            stageId = getString(STAGE_ID),
            policyId = getString(POLICY_ID),
            policyVersion = getString(POLICY_VERSION),
            policySha256 = getString(POLICY_SHA256),
            profileId = getString(PROFILE_ID),
            profileVersion = getString(PROFILE_VERSION),
            profileSha256 = getString(PROFILE_SHA256),
            runtimePlanSha256 = getString(RUNTIME_PLAN_SHA256),
        )
    } finally {
        keys.forEach(remove)
    }

    fun readAndRemoveForActivity(
        isFirstCreation: Boolean,
        enabled: Boolean,
        getBoolean: (String, Boolean) -> Boolean,
        getString: (String) -> String?,
        remove: (String) -> Unit,
    ): RepeatabilityQualificationLaunchWireData {
        val wire = readAndRemove(
            getBoolean = getBoolean,
            getString = getString,
            remove = remove,
        )
        return if (isFirstCreation && enabled) {
            wire
        } else {
            RepeatabilityQualificationLaunchWireData(requested = false)
        }
    }
}

internal data class VerifiedRepeatabilityQualificationLaunch(
    val stageId: String,
    val transport: TestEngine.TransportMode,
    val testMode: AnebTestMode,
    val policyId: String,
    val policyVersion: String,
    val policySha256: String,
    val profileId: String,
    val profileVersion: String,
    val profileSha256: String,
    val runtimePlanSha256: String,
    val variant: String,
)

internal object RepeatabilityQualificationLaunch {
    private const val POLICY_ID = "aneb-repeatability-qualification-balanced-v1"
    private const val POLICY_VERSION = "1.0.0"
    private const val POLICY_SHA256 = "505276dc9e72eb68454461bb355b63db6227069274646835020d89a6646fedfa"
    private const val VARIANT = "repeatability_qualification"

    private data class ProfileIdentity(
        val id: String,
        val version: String,
        val profileSha256: String,
        val runtimePlanSha256: String,
    )

    private val profileByTestMode = mapOf(
        AnebTestMode.TOKEN_SIMULATION to ProfileIdentity(
            id = "token_multimodal_repeatability_qualification",
            version = "1.0.0",
            profileSha256 = "eaeb0af8c1a38c88a8f341c120701580659625eb3b68b8d7960db2888a01ee7b",
            runtimePlanSha256 = "d8f31633e0c0d91a321bb1007f7cb0c30e84f855fa4e1e7b0a181e80879e7ea4",
        ),
        AnebTestMode.AI_REALTIME_SIMULATION to ProfileIdentity(
            id = "ai_realtime_voice_repeatability_qualification",
            version = "1.0.0",
            profileSha256 = "ad86006f48bb06716c9d69d430d84f511c206ebd9114feffd0ca8679aeace75c",
            runtimePlanSha256 = "883b36003dbb84cb264c7742908c9f045f3fa7c2938db9a339566f6b32b70eda",
        ),
        AnebTestMode.NETWORK_BASIC to ProfileIdentity(
            id = "network_comprehensive_repeatability_qualification",
            version = "1.0.0",
            profileSha256 = "e39dcabd2276a19c193e0a6b0c3126af734ff7c8d2fba17c91d0d48019a0c375",
            runtimePlanSha256 = "f430fba09fd7453872690fd0d5cf9ad130637f87f347a6247c04ad069b2e4aab",
        ),
    )

    fun verify(request: RepeatabilityQualificationLaunchRequest): VerifiedRepeatabilityQualificationLaunch {
        require(request.debug && request.autorun) {
            "repeatability_qualification_requires_debug_autorun"
        }
        val expectedTransport = when (request.stageId) {
            "Q1_WIFI" -> TestEngine.TransportMode.WIFI
            "Q2_CELLULAR" -> TestEngine.TransportMode.CELLULAR
            else -> throw IllegalArgumentException("repeatability_qualification_stage_invalid")
        }
        require(request.transport == expectedTransport) {
            "repeatability_qualification_stage_transport_mismatch"
        }
        require(
            request.policyId == POLICY_ID &&
                request.policyVersion == POLICY_VERSION &&
                request.policySha256 == POLICY_SHA256,
        ) {
            "repeatability_qualification_policy_identity_mismatch"
        }
        val expectedProfile = profileByTestMode[request.testMode]
            ?: throw IllegalArgumentException("repeatability_qualification_test_mode_invalid")
        require(
            request.profileId == expectedProfile.id &&
                request.profileVersion == expectedProfile.version &&
                request.profileSha256 == expectedProfile.profileSha256 &&
                request.runtimePlanSha256 == expectedProfile.runtimePlanSha256,
        ) {
            "repeatability_qualification_profile_identity_mismatch"
        }
        return VerifiedRepeatabilityQualificationLaunch(
            stageId = requireNotNull(request.stageId),
            transport = request.transport,
            testMode = request.testMode,
            policyId = POLICY_ID,
            policyVersion = POLICY_VERSION,
            policySha256 = POLICY_SHA256,
            profileId = expectedProfile.id,
            profileVersion = expectedProfile.version,
            profileSha256 = expectedProfile.profileSha256,
            runtimePlanSha256 = expectedProfile.runtimePlanSha256,
            variant = VARIANT,
        )
    }
}

internal object RepeatabilityQualificationLaunchWire {
    fun verifyIfRequested(
        debug: Boolean,
        autorun: Boolean,
        testMode: AnebTestMode,
        transport: TestEngine.TransportMode,
        wire: RepeatabilityQualificationLaunchWireData,
    ): VerifiedRepeatabilityQualificationLaunch? {
        val hasMetadata = listOf(
            wire.stageId,
            wire.policyId,
            wire.policyVersion,
            wire.policySha256,
            wire.profileId,
            wire.profileVersion,
            wire.profileSha256,
            wire.runtimePlanSha256,
        ).any { it != null }
        if (!wire.requested) {
            require(!hasMetadata) { "repeatability_qualification_metadata_without_request" }
            return null
        }
        return RepeatabilityQualificationLaunch.verify(
            RepeatabilityQualificationLaunchRequest(
                debug = debug,
                autorun = autorun,
                stageId = wire.stageId,
                transport = transport,
                testMode = testMode,
                policyId = wire.policyId,
                policyVersion = wire.policyVersion,
                policySha256 = wire.policySha256,
                profileId = wire.profileId,
                profileVersion = wire.profileVersion,
                profileSha256 = wire.profileSha256,
                runtimePlanSha256 = wire.runtimePlanSha256,
            ),
        )
    }
}

internal object RepeatabilityQualificationEngineVariant {
    fun resolve(
        testMode: AnebTestMode,
        mode: TestEngine.Mode,
        qualification: VerifiedRepeatabilityQualificationLaunch?,
    ): String {
        if (qualification != null) {
            require(qualification.testMode == testMode) {
                "repeatability_qualification_test_mode_mismatch"
            }
            return qualification.variant
        }
        return when (testMode) {
            AnebTestMode.NETWORK_BASIC -> when (mode) {
                TestEngine.Mode.QUICK -> "quick"
                TestEngine.Mode.FORENSIC -> "standard"
                TestEngine.Mode.STRESS -> "weak_capacity_latency"
                TestEngine.Mode.NETWORK_RECOVERY -> "weak_recovery"
                TestEngine.Mode.GATEWAY_LOSS -> "gateway_loss"
                TestEngine.Mode.GATEWAY_RECOVERY -> "gateway_recovery"
            }
            AnebTestMode.TOKEN_SIMULATION -> when (mode) {
                TestEngine.Mode.QUICK -> "quick"
                TestEngine.Mode.FORENSIC -> "standard"
                TestEngine.Mode.STRESS -> "stress"
                TestEngine.Mode.NETWORK_RECOVERY,
                TestEngine.Mode.GATEWAY_LOSS,
                TestEngine.Mode.GATEWAY_RECOVERY -> error("network_lab_mode_requires_network_test")
            }
            AnebTestMode.AI_REALTIME_SIMULATION -> when (mode) {
                TestEngine.Mode.QUICK -> "quick"
                TestEngine.Mode.FORENSIC -> "standard"
                TestEngine.Mode.STRESS -> "recovery"
                TestEngine.Mode.NETWORK_RECOVERY,
                TestEngine.Mode.GATEWAY_LOSS,
                TestEngine.Mode.GATEWAY_RECOVERY -> error("network_lab_mode_requires_network_test")
            }
            AnebTestMode.TOKEN_EXPERIENCE -> error("formal_engine_variant_requires_formal_test")
        }
    }
}

internal data class PreparedRepeatabilityQualificationRun(
    val qualification: VerifiedRepeatabilityQualificationLaunch?,
    val variant: String,
)

internal object RepeatabilityQualificationRunPreparation {
    fun verify(
        debug: Boolean,
        autorun: Boolean,
        testMode: AnebTestMode,
        mode: TestEngine.Mode,
        transport: TestEngine.TransportMode,
        wire: RepeatabilityQualificationLaunchWireData,
    ): PreparedRepeatabilityQualificationRun {
        val qualification = RepeatabilityQualificationLaunchWire.verifyIfRequested(
            debug = debug,
            autorun = autorun,
            testMode = testMode,
            transport = transport,
            wire = wire,
        )
        return PreparedRepeatabilityQualificationRun(
            qualification = qualification,
            variant = RepeatabilityQualificationEngineVariant.resolve(
                testMode = testMode,
                mode = mode,
                qualification = qualification,
            ),
        )
    }
}

internal object RepeatabilityQualificationServiceIntentBoundary {
    fun write(
        wire: RepeatabilityQualificationLaunchWireData,
        putBoolean: (String, Boolean) -> Unit,
        putString: (String, String?) -> Unit,
    ) = RepeatabilityQualificationLaunchExtras.write(
        wire = wire,
        putBoolean = putBoolean,
        putString = putString,
    )

    fun readAndRemove(
        getBoolean: (String, Boolean) -> Boolean,
        getString: (String) -> String?,
        remove: (String) -> Unit,
    ) = RepeatabilityQualificationLaunchExtras.readAndRemove(
        getBoolean = getBoolean,
        getString = getString,
        remove = remove,
    )

    fun prepare(
        debug: Boolean,
        autorun: Boolean,
        config: ProbeRunService.Config,
    ): PreparedRepeatabilityQualificationRun? {
        if (config.testMode == AnebTestMode.TOKEN_EXPERIENCE) {
            val qualification = RepeatabilityQualificationLaunchWire.verifyIfRequested(
                debug = debug,
                autorun = autorun,
                testMode = config.testMode,
                transport = config.transport,
                wire = config.qualificationWire,
            )
            check(qualification == null) { "legacy_test_cannot_use_repeatability_qualification" }
            return null
        }
        return RepeatabilityQualificationRunPreparation.verify(
            debug = debug,
            autorun = autorun,
            testMode = config.testMode,
            mode = config.mode,
            transport = config.transport,
            wire = config.qualificationWire,
        )
    }
}

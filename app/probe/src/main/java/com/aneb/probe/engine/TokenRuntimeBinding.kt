package com.aneb.probe.engine

/** Exact binding between the requested Token path, its Profile and runtime plan. */
internal object TokenRuntimeBinding {
    private val variants = setOf("quick", "standard", "stress")

    fun validate(profile: ScenarioProfile, plan: TokenRuntimePlan, requestedVariant: String) {
        require(requestedVariant in variants) { "unsupported_token_variant:$requestedVariant" }
        require(profile.profileId == "token_multimodal_$requestedVariant") {
            "token_profile_id_variant_mismatch"
        }
        require(profile.evidenceTier == requestedVariant) { "token_profile_evidence_variant_mismatch" }
        val execution = requireNotNull(profile.executionPlan) { "token_execution_plan_missing" }
        require(execution.variant == requestedVariant) { "token_execution_profile_variant_mismatch" }
        require(plan.variant == requestedVariant) { "token_runtime_requested_variant_mismatch" }

        require(plan.contractVersion == execution.contractVersion) { "token_runtime_contract_mismatch" }
        require(plan.modelId == profile.business.behaviorModelId) { "token_runtime_model_id_mismatch" }
        require(plan.modelVersion == profile.business.behaviorModelVersion) { "token_runtime_model_version_mismatch" }
        require(plan.modelHash == profile.business.behaviorModelHash) { "token_runtime_model_hash_mismatch" }
        require(plan.calibrationStatus == profile.business.calibrationStatus) { "token_runtime_calibration_mismatch" }
        require(plan.seed == execution.seed) { "token_runtime_seed_mismatch" }
        require(plan.taskCount == plan.tasks.size && plan.tasks.isNotEmpty()) { "token_runtime_task_count_invalid" }
        plan.tasks.forEach { task ->
            require(task.taskId.isNotBlank() && task.workloadKind in setOf("text", "document", "image", "video")) {
                "token_runtime_task_identity_invalid"
            }
            require(task.upload.payloadBytes > 0 && task.upload.chunkBytes > 0) { "token_runtime_upload_invalid" }
            require(task.tokenStream.intervalsMs.isNotEmpty()) { "token_runtime_stream_empty" }
            require(task.tokenStream.intervalsMs.size == task.tokenStream.sizesBytes.size) {
                "token_runtime_stream_length_mismatch"
            }
            require(task.tokenStream.intervalsMs.first() == 0.0) { "token_runtime_first_interval_not_zero" }
            require(task.tokenStream.intervalsMs.all { it >= 0.0 } && task.tokenStream.sizesBytes.all { it > 0 }) {
                "token_runtime_stream_value_invalid"
            }
        }
    }
}

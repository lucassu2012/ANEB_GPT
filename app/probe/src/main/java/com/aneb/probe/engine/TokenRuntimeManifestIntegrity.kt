package com.aneb.probe.engine

internal data class TokenRuntimeManifestDigests(
    val profileSha256: String,
    val runtimePlanSha256: String,
)

/** Strict verifier for the two-file token runtime publication manifest. */
internal object TokenRuntimeManifestIntegrity {
    private val entryPattern = Regex("^([0-9a-f]{64})  (profile\\.json|runtime_plan\\.json)$")
    private val requiredNames = setOf("profile.json", "runtime_plan.json")

    fun verify(
        manifestText: String,
        profileText: String,
        runtimePlanText: String,
    ): TokenRuntimeManifestDigests {
        val normalized = manifestText.replace("\r\n", "\n")
        require('\r' !in normalized) { "token_manifest_line_ending_invalid" }
        val body = if (normalized.endsWith('\n')) normalized.dropLast(1) else normalized
        require(body.isNotEmpty() && !body.endsWith('\n')) { "token_manifest_cardinality_invalid" }
        val lines = body.split('\n')
        require(lines.size == 2) { "token_manifest_cardinality_invalid" }

        val entries = linkedMapOf<String, String>()
        lines.forEach { line ->
            val match = entryPattern.matchEntire(line)
                ?: throw IllegalArgumentException("token_manifest_format_invalid")
            val digest = match.groupValues[1]
            val name = match.groupValues[2]
            require(entries.put(name, digest) == null) { "token_manifest_duplicate_entry:$name" }
        }
        require(entries.keys == requiredNames) { "token_manifest_entries_invalid" }

        val profileSha256 = TokenRuntimeIntegrity.canonicalSha256(profileText)
        val runtimePlanSha256 = TokenRuntimeIntegrity.canonicalSha256(runtimePlanText)
        require(entries.getValue("profile.json") == profileSha256.removePrefix("sha256:")) {
            "token_manifest_profile_hash_mismatch"
        }
        require(entries.getValue("runtime_plan.json") == runtimePlanSha256.removePrefix("sha256:")) {
            "token_manifest_runtime_hash_mismatch"
        }
        return TokenRuntimeManifestDigests(profileSha256, runtimePlanSha256)
    }
}

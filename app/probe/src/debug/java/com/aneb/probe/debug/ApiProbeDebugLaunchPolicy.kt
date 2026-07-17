package com.aneb.probe.debug

/** Pure launch contract for the ADB-only real API probe entry point. */
internal object ApiProbeDebugLaunchPolicy {
    const val EXTRA_AUTORUN = "apiprobe_autorun"
    const val EXTRA_SERVER = "apiprobe_server"
    const val EXTRA_KEY = "apiprobe_key"
    const val EXTRA_PROVIDER = "apiprobe_provider"
    const val EXTRA_MODEL = "apiprobe_model"

    val sensitiveExtras = listOf(
        EXTRA_AUTORUN,
        EXTRA_SERVER,
        EXTRA_KEY,
        EXTRA_PROVIDER,
        EXTRA_MODEL,
    )

    enum class Provider {
        ANTHROPIC,
        OPENAI_COMPAT,
    }

    enum class RejectReason(val wireValue: String) {
        RECREATED("recreated"),
        ALREADY_RUNNING("already_running"),
        AUTORUN_NOT_REQUESTED("autorun_not_requested"),
        SERVER_MISSING("server_missing"),
        KEY_MISSING("key_missing"),
        PROVIDER_INVALID("provider_invalid"),
    }

    data class RawRequest(
        val autorun: Boolean,
        val server: String?,
        val apiKey: String?,
        val provider: String?,
        val model: String?,
    ) {
        override fun toString(): String =
            "RawRequest(autorun=$autorun, server=<redacted>, apiKey=<redacted>, provider=<redacted>, model=<redacted>)"
    }

    sealed interface Decision {
        data class Run(
            val server: String,
            val apiKey: String,
            val provider: Provider,
            val model: String?,
        ) : Decision {
            override fun toString(): String =
                "Run(server=<redacted>, apiKey=<redacted>, provider=$provider, model=<redacted>)"
        }

        data class Reject(val reason: RejectReason) : Decision
    }

    fun decide(
        firstCreation: Boolean,
        singleFlightAcquired: Boolean,
        request: RawRequest,
    ): Decision {
        if (!firstCreation) return Decision.Reject(RejectReason.RECREATED)
        if (!singleFlightAcquired) return Decision.Reject(RejectReason.ALREADY_RUNNING)
        if (!request.autorun) return Decision.Reject(RejectReason.AUTORUN_NOT_REQUESTED)

        val server = request.server?.trim()?.takeIf(String::isNotEmpty)
            ?: return Decision.Reject(RejectReason.SERVER_MISSING)
        val apiKey = request.apiKey?.trim()?.takeIf(String::isNotEmpty)
            ?: return Decision.Reject(RejectReason.KEY_MISSING)
        val provider = when (request.provider?.trim()?.lowercase()) {
            null, "", "openai_compat" -> Provider.OPENAI_COMPAT
            "anthropic" -> Provider.ANTHROPIC
            else -> return Decision.Reject(RejectReason.PROVIDER_INVALID)
        }
        return Decision.Run(
            server = server,
            apiKey = apiKey,
            provider = provider,
            model = request.model?.trim()?.takeIf(String::isNotEmpty),
        )
    }
}

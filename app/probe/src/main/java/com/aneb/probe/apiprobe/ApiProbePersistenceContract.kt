package com.aneb.probe.apiprobe

internal object ApiProbePersistenceContract {
    internal enum class ResultCode(val wireValue: String) {
        HTTP_ACCEPTED("HTTP_ACCEPTED"),
        HTTP_REJECTED("HTTP_REJECTED"),
        TRANSPORT_FAILED("TRANSPORT_FAILED"),
        ADAPTER_FAILED("ADAPTER_FAILED"),
        LEGACY_UNCLASSIFIED("LEGACY_UNCLASSIFIED"),
    }

    internal enum class TransportClass(val wireValue: String) {
        HTTP("HTTP"),
        IO("IO"),
        LOCAL("LOCAL"),
        NONE("NONE"),
    }

    internal enum class CompletionClass(val wireValue: String) {
        COMPLETED("COMPLETED"),
        TRUNCATED_TAIL("TRUNCATED_TAIL"),
        FAILED("FAILED"),
        LEGACY_UNCLASSIFIED("LEGACY_UNCLASSIFIED"),
    }

    internal enum class DigestScope(val wireValue: String) {
        NONE("NONE"),
    }

    internal enum class Failure {
        IO,
        LOCAL,
    }

    internal sealed interface Outcome {
        data class Http(
            val statusCode: Int,
            val streamTailTruncated: Boolean,
            val protocolErrorPresent: Boolean,
            val untrustedDetail: String?,
        ) : Outcome

        data class Failed(
            val failure: Failure,
            val untrustedDetail: String?,
        ) : Outcome
    }

    internal data class Classification(
        val resultCode: ResultCode,
        val transportClass: TransportClass,
        val completionClass: CompletionClass,
        val errorBodyByteCount: Long,
        val errorBodySha256: String?,
        val errorBodyDigestScope: DigestScope,
        val errorBodyTruncated: Boolean,
    )

    internal fun classify(outcome: Outcome): Classification {
        val result = when (outcome) {
            is Outcome.Http -> when {
                outcome.statusCode !in 200..299 -> Triple(
                    ResultCode.HTTP_REJECTED,
                    TransportClass.HTTP,
                    CompletionClass.FAILED,
                )

                outcome.protocolErrorPresent -> Triple(
                    ResultCode.ADAPTER_FAILED,
                    TransportClass.HTTP,
                    CompletionClass.FAILED,
                )

                outcome.streamTailTruncated -> Triple(
                    ResultCode.HTTP_ACCEPTED,
                    TransportClass.HTTP,
                    CompletionClass.TRUNCATED_TAIL,
                )

                else -> Triple(
                    ResultCode.HTTP_ACCEPTED,
                    TransportClass.HTTP,
                    CompletionClass.COMPLETED,
                )
            }

            is Outcome.Failed -> when (outcome.failure) {
                Failure.IO -> Triple(
                    ResultCode.TRANSPORT_FAILED,
                    TransportClass.IO,
                    CompletionClass.FAILED,
                )

                Failure.LOCAL -> Triple(
                    ResultCode.ADAPTER_FAILED,
                    TransportClass.LOCAL,
                    CompletionClass.FAILED,
                )
            }
        }

        return Classification(
            resultCode = result.first,
            transportClass = result.second,
            completionClass = result.third,
            errorBodyByteCount = 0L,
            errorBodySha256 = null,
            errorBodyDigestScope = DigestScope.NONE,
            errorBodyTruncated = false,
        )
    }
}

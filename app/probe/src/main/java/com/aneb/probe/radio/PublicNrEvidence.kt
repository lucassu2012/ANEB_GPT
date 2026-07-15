package com.aneb.probe.radio

/**
 * R-15 public-API-only NR attribution.
 *
 * [overrideType] is an operator display policy, not bearer evidence. A 5G display override with
 * LTE data or missing cell evidence therefore remains `nsa_unknown`. We emit a definite state only
 * when the negotiated data type and registered cell agree.
 */
internal object PublicNrEvidence {
    fun derive(
        networkType: String,
        overrideType: String?,
        registeredCellRat: String?,
    ): String {
        return when {
            networkType == "NR" && registeredCellRat == "NR" -> "connected"
            networkType == "LTE" && registeredCellRat == "LTE" && overrideType == "none" -> "none"
            else -> "nsa_unknown"
        }
    }
}

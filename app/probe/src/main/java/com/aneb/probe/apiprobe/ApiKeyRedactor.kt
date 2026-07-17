package com.aneb.probe.apiprobe

/** Defense-in-depth redaction for every Debug API diagnostic text exit. */
object ApiKeyRedactor {
    const val MASK = "***REDACTED***"

    fun redact(text: String?, key: String?): String? {
        if (text == null) return null
        if (key.isNullOrBlank()) return text
        return text.replace(key, MASK)
    }
}

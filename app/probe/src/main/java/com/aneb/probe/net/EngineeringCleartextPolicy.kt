package com.aneb.probe.net

import com.aneb.probe.BuildConfig
import okhttp3.HttpUrl
import okhttp3.Interceptor

/** Keeps the engineering-wide cleartext exception scoped to Prototype private-node traffic. */
internal object EngineeringCleartextPolicy {
    fun interceptor(prototypePrivate: Boolean): Interceptor = Interceptor { chain ->
        val request = chain.request()
        requireAllowed(request.url, prototypePrivate = prototypePrivate)
        chain.proceed(request)
    }

    fun requireAllowed(
        url: HttpUrl,
        prototypePrivate: Boolean,
        engineering: Boolean = BuildConfig.PROTOTYPE_ENGINEERING,
    ) {
        require(isAllowed(url, engineering, prototypePrivate)) {
            "cleartext network traffic is disabled for this engineering request"
        }
    }

    fun isAllowed(url: HttpUrl, engineering: Boolean, prototypePrivate: Boolean): Boolean {
        if (!engineering || url.isHttps) return true
        if (!prototypePrivate || url.scheme != "http" || url.query != null || url.fragment != null) return false
        if (url.encodedPath !in PROTOTYPE_PATHS) return false
        return isPrivateOrLoopbackIpv4(url.host)
    }

    private fun isPrivateOrLoopbackIpv4(host: String): Boolean {
        val octets = host.split('.').map { part -> part.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            octets[0] == 127 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    private val PROTOTYPE_PATHS = setOf(
        "/api/v1/prototype/capabilities",
        "/api/v1/prototype/runs",
    )
}

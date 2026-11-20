package com.aneb.probe.net

import com.aneb.probe.BuildConfig
import okhttp3.HttpUrl
import okhttp3.Interceptor

/** Explicit Debug-local feature client only; does not relax the legacy or Prototype policy. */
internal object LoadedNodePolicy {
    fun interceptor(): Interceptor = Interceptor { chain ->
        require(isAllowed(chain.request().url, BuildConfig.DEBUG)) { "loaded_local_node_url_rejected" }
        chain.proceed(chain.request())
    }

    fun isAllowed(url: HttpUrl, debug: Boolean): Boolean {
        if (url.isHttps) return true
        if (!debug || url.scheme != "http" || url.fragment != null ||
            url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        // Same concrete hosts as the Debug network-security configuration, never a global HTTP exception.
        if (url.host !in setOf("127.0.0.1", "10.0.2.2", "10.10.191.194")) return false
        val keys = url.queryParameterNames
        return when (url.encodedPath) {
            "/api/v1/echo", "/api/v1/profiles" -> keys.isEmpty()
            "/api/v1/download" -> keys == setOf("bytes", "chunk_kb") &&
                keys.all { key -> url.queryParameterValues(key).size == 1 &&
                    (url.queryParameter(key)?.toLongOrNull() ?: 0L) > 0L }
            "/api/v1/upload" -> keys == setOf("run") && url.queryParameterValues("run").size == 1 &&
                url.queryParameter("run")?.matches(Regex("[A-Za-z0-9-]{1,100}")) == true
            else -> false
        }
    }
}

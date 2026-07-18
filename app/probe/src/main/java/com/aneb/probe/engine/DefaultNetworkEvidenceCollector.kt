package com.aneb.probe.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.aneb.probe.data.EnvEvent
import com.aneb.probe.data.EnvEventType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Privacy-safe state machine for Android's default-network callback.
 *
 * Android Network handles stay inside this process. Persisted details use per-run aliases only,
 * so a result can prove loss/switch/recovery without exposing a reusable network identifier.
 */
internal class DefaultNetworkTransitionTracker<K : Any> {
    private data class PathState(
        val alias: String,
        val transport: String = "unknown",
        val validated: Boolean? = null,
        val notSuspended: Boolean? = null,
    )

    private val paths = linkedMapOf<K, PathState>()
    private var current: K? = null
    private var observedInitialPath = false
    private var awaitingReady = false

    @Synchronized
    fun onAvailable(path: K): List<String> {
        val state = state(path)
        val previous = current
        return when {
            previous == null && !observedInitialPath -> {
                current = path
                observedInitialPath = true
                emptyList()
            }
            previous == null -> {
                current = path
                awaitingReady = true
                listOf("default_network_available path=${state.alias}")
            }
            previous == path -> emptyList()
            else -> {
                val previousState = state(previous)
                current = path
                awaitingReady = true
                listOf(
                    "default_network_changed from_path=${previousState.alias} " +
                        "from_transport=${previousState.transport} to_path=${state.alias}",
                )
            }
        }
    }

    @Synchronized
    fun onLost(path: K): List<String> {
        if (current != path) return emptyList()
        current = null
        awaitingReady = false
        return listOf("default_network_lost ${describe(path)}")
    }

    @Synchronized
    fun onCapabilities(
        path: K,
        transport: String,
        validated: Boolean,
        notSuspended: Boolean,
    ): List<String> {
        val old = state(path)
        paths[path] = old.copy(
            transport = transport,
            validated = validated,
            notSuspended = notSuspended,
        )
        if (current == null && !observedInitialPath) {
            current = path
            observedInitialPath = true
            return emptyList()
        }
        if (current != path) return emptyList()

        val details = mutableListOf<String>()
        val becameReady = awaitingReady && validated && notSuspended
        if (becameReady) {
            details += "default_network_ready ${describe(path)} validated=$validated not_suspended=$notSuspended"
            awaitingReady = false
        }
        if (old.validated == true && !validated) {
            details += "default_network_validation_lost ${describe(path)}"
        } else if (old.validated == false && validated && !becameReady) {
            details += "default_network_validation_restored ${describe(path)}"
        }
        if (old.notSuspended == true && !notSuspended) {
            details += "default_network_suspended ${describe(path)}"
        } else if (old.notSuspended == false && notSuspended && !becameReady) {
            details += "default_network_resumed ${describe(path)}"
        }
        if (old.transport != "unknown" && old.transport != transport) {
            details += "default_network_transport_changed path=${old.alias} from=${old.transport} to=$transport"
        }
        return details
    }

    private fun state(path: K): PathState = paths.getOrPut(path) {
        PathState(alias = "path-${paths.size + 1}")
    }

    private fun describe(path: K): String {
        val state = state(path)
        return "path=${state.alias} transport=${state.transport}"
    }
}

/** Per-run collector for public Android default-network transitions. */
internal class DefaultNetworkEvidenceCollector(
    context: Context,
    private val onEvent: (EnvEvent) -> Unit,
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val tracker = DefaultNetworkTransitionTracker<Network>()
    private val started = AtomicBoolean(false)
    private val accepting = AtomicBoolean(false)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        check(started.compareAndSet(false, true)) { "default_network_collector_already_started" }
        val manager = connectivityManager
        if (manager == null) {
            emitUnavailable("connectivity_manager_unavailable")
            return
        }
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = emit(tracker.onAvailable(network))

            override fun onLost(network: Network) = emit(tracker.onLost(network))

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                emit(
                    tracker.onCapabilities(
                        path = network,
                        transport = caps.transportLabel(),
                        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        notSuspended = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
                    ),
                )
            }
        }
        accepting.set(true)
        try {
            manager.registerDefaultNetworkCallback(networkCallback)
            callback = networkCallback
        } catch (failure: Throwable) {
            accepting.set(false)
            emitUnavailable(failure.javaClass.simpleName.ifBlank { "registration_failed" })
        }
    }

    fun stop() {
        accepting.set(false)
        val registered = callback
        callback = null
        if (registered != null) {
            runCatching { connectivityManager?.unregisterNetworkCallback(registered) }
        }
    }

    private fun emit(details: List<String>) {
        if (!accepting.get()) return
        details.forEach { detail ->
            onEvent(EnvEvent(SystemClock.elapsedRealtimeNanos(), EnvEventType.PATH_CHANGE, detail))
        }
    }

    private fun emitUnavailable(reason: String) {
        onEvent(
            EnvEvent(
                SystemClock.elapsedRealtimeNanos(),
                EnvEventType.PATH_CHANGE,
                "default_network_monitor_unavailable reason=$reason",
            ),
        )
    }
}

private fun NetworkCapabilities.transportLabel(): String {
    val transports = buildList {
        if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
        if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
        if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        if (hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
    }
    return transports.ifEmpty { listOf("other") }.joinToString("+")
}

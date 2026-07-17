package com.aneb.probe.debug

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.aneb.probe.apiprobe.ApiKeyRedactor
import com.aneb.probe.apiprobe.ApiProbe
import com.aneb.probe.apiprobe.LlmProvider
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Permission-protected, Debug-only entry point for a single explicit ADB API probe.
 * It has no launcher or browsable intent filter and never renders product navigation.
 */
class ApiProbeDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawRequest = consumeAndScrub(intent)
        val firstCreation = savedInstanceState == null
        val acquired = firstCreation && inFlight.compareAndSet(false, true)
        when (
            val decision = ApiProbeDebugLaunchPolicy.decide(
                firstCreation = firstCreation,
                singleFlightAcquired = acquired,
                request = rawRequest,
            )
        ) {
            is ApiProbeDebugLaunchPolicy.Decision.Reject -> {
                if (acquired) inFlight.set(false)
                Log.i(TAG, "APIPROBE_DEBUG_REJECT reason=${decision.reason.wireValue}")
                closeDiagnosticTask()
            }

            is ApiProbeDebugLaunchPolicy.Decision.Run -> {
                try {
                    launchSingleProbe(decision)
                } catch (error: RuntimeException) {
                    inFlight.set(false)
                    Log.i(TAG, "APIPROBE_DEBUG_COMPLETE status=failed error=${error.javaClass.simpleName}")
                    closeDiagnosticTask()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        scrub(intent)
        Log.i(TAG, "APIPROBE_DEBUG_REJECT reason=new_intent")
    }

    private fun launchSingleProbe(request: ApiProbeDebugLaunchPolicy.Decision.Run) {
        val provider = when (request.provider) {
            ApiProbeDebugLaunchPolicy.Provider.ANTHROPIC -> LlmProvider.ANTHROPIC
            ApiProbeDebugLaunchPolicy.Provider.OPENAI_COMPAT -> LlmProvider.OPENAI_COMPAT
        }
        val job = lifecycleScope.launch {
            try {
                ApiProbe(applicationContext).run(
                    ApiProbe.Config(
                        provider = provider,
                        baseUrl = request.server,
                        model = request.model ?: provider.defaultModel,
                        apiKey = request.apiKey,
                    ),
                ) { line ->
                    val safeLine = ApiKeyRedactor.redact(line, request.apiKey)
                        ?: "APIPROBE_DEBUG_LOG_REDACTION_FAILED"
                    Log.i(TAG, safeLine)
                }
                Log.i(TAG, "APIPROBE_DEBUG_COMPLETE status=ok")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.i(TAG, "APIPROBE_DEBUG_COMPLETE status=failed error=${error.javaClass.simpleName}")
            }
        }
        job.invokeOnCompletion {
            inFlight.set(false)
            runOnUiThread {
                if (!isDestroyed) closeDiagnosticTask()
            }
        }
    }

    private fun consumeAndScrub(source: Intent): ApiProbeDebugLaunchPolicy.RawRequest {
        var autorun = false
        var server: String? = null
        var apiKey: String? = null
        var provider: String? = null
        var model: String? = null
        try {
            autorun = source.getBooleanExtra(ApiProbeDebugLaunchPolicy.EXTRA_AUTORUN, false)
            server = source.getStringExtra(ApiProbeDebugLaunchPolicy.EXTRA_SERVER)
            apiKey = source.getStringExtra(ApiProbeDebugLaunchPolicy.EXTRA_KEY)
            provider = source.getStringExtra(ApiProbeDebugLaunchPolicy.EXTRA_PROVIDER)
            model = source.getStringExtra(ApiProbeDebugLaunchPolicy.EXTRA_MODEL)
        } catch (_: RuntimeException) {
            // Malformed parcelables fail closed through the missing-parameter policy.
            autorun = false
            server = null
            apiKey = null
            provider = null
            model = null
        } finally {
            scrub(source)
        }
        return ApiProbeDebugLaunchPolicy.RawRequest(autorun, server, apiKey, provider, model)
    }

    private fun scrub(source: Intent) {
        ApiProbeDebugLaunchPolicy.sensitiveExtras.forEach(source::removeExtra)
    }

    private fun closeDiagnosticTask() {
        if (isTaskRoot) finishAndRemoveTask() else finish()
    }

    private companion object {
        const val TAG = "AnebProbe"
        val inFlight = AtomicBoolean(false)
    }
}

package com.aneb.probe.research.engineering

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Normal per-session OS consent only; no button automation or persisted/reused grant. */
class TongyiCaptureConsentActivity : Activity() {
    private var session: TongyiProjectionCaptureService.Session? = null
    private val main = Handler(Looper.getMainLooper())
    private val watchConsent = object : Runnable {
        override fun run() {
            val owned = session ?: return
            if (isFinishing) return
            try {
                owned.requireWithin(owned.preparationDeadlineNanos)
                main.postDelayed(this, 50)
            } catch (error: Exception) {
                owned.fail(error)
                owned.close()
                finishOwnedRequest()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owned = TongyiProjectionCaptureService.find(intent.getStringExtra(TongyiProjectionCaptureService.SESSION_ID))
        if (owned == null) { finishAndRemoveTask(); return }
        session = owned
        owned.activity = this
        owned.activityClosed = false
        try {
            owned.requireWithin(owned.preparationDeadlineNanos)
            if (savedInstanceState?.getBoolean("consent_requested") != true) {
                val manager = getSystemService(MediaProjectionManager::class.java)
                val request = if (Build.VERSION.SDK_INT >= 34)
                    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else manager.createScreenCaptureIntent()
                startActivityForResult(request, 807)
            }
            main.post(watchConsent)
        } catch (error: Exception) {
            owned.fail(error)
            owned.close()
            finishOwnedRequest()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("consent_requested", true)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 807) return
        val owned = session ?: run { finishOwnedRequest(); return }
        try {
            owned.requireWithin(owned.preparationDeadlineNanos)
            check(resultCode == RESULT_OK && data != null) { "PROJECTION_USER_CONSENT_DECLINED" }
            check(!owned.serviceRequested) { "PROJECTION_GRANT_ALREADY_CONSUMED" }
            owned.serviceRequested = true
            owned.serviceDestroyed = false
            // This Activity is visible after the normal grant result. FGS precedes getMediaProjection.
            startForegroundService(Intent(this, TongyiProjectionCaptureService::class.java)
                .putExtra(TongyiProjectionCaptureService.SESSION_ID, owned.id)
                .putExtra(TongyiProjectionCaptureService.RESULT_CODE, resultCode)
                .putExtra(TongyiProjectionCaptureService.RESULT_DATA, data))
            // The service finishes only this owned Activity after acquiring/owning the projection.
        } catch (error: Exception) {
            owned.fail(error)
            owned.close()
            finishOwnedRequest()
        }
    }

    internal fun finishOwnedRequest() {
        main.removeCallbacks(watchConsent)
        finishActivity(807) // Only the result Activity launched by this instance, never another session.
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        main.removeCallbacks(watchConsent)
        session?.let { owned ->
            if (owned.activity === this) {
                owned.activity = null
                if (!isChangingConfigurations) {
                    owned.activityClosed = true
                    if (!isFinishing && !owned.closed) {
                        owned.fail(IllegalStateException("PROJECTION_CONSENT_ACTIVITY_DESTROYED"))
                        owned.close()
                    }
                }
            }
        }
        super.onDestroy()
    }
}

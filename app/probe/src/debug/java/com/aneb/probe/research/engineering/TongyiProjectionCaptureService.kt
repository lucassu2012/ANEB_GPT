package com.aneb.probe.research.engineering

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.util.UUID

/** Debug-only, video-only resources for one explicitly selected ordinary run. */
class TongyiProjectionCaptureService : Service() {
    class Session internal constructor(
        private val context: Context,
        val root: File,
        val preparationDeadlineNanos: Long,
    ) {
        val id: String = UUID.randomUUID().toString()
        @Volatile var grantReady = false
            internal set
        @Volatile var activityClosed = false
            internal set
        @Volatile var recordingStarted = false
            internal set
        @Volatile var mediaRequired = false
            internal set
        @Volatile var closed = false
            private set
        @Volatile private var failure: Exception? = null
        @Volatile internal var serviceRequested = false
        @Volatile internal var serviceDestroyed = true
        @Volatile internal var resourcesReleased = true
        @Volatile internal var closureFailed = false
        @Volatile internal var activity: TongyiCaptureConsentActivity? = null
        @Volatile internal var service: TongyiProjectionCaptureService? = null
        private var recordingRequested = false
        private val main = Handler(Looper.getMainLooper())

        fun requireHealthy() {
            failure?.let { throw it }
            check(!closed && find(id) === this) { "PROJECTION_SESSION_CLOSED" }
        }

        internal fun requireWithin(deadlineNanos: Long) {
            requireHealthy()
            check(SystemClock.elapsedRealtimeNanos() < deadlineNanos) { "PROJECTION_ORIGINAL_DEADLINE_EXPIRED" }
            check(!File(root, "CANCEL").exists()) { "CANCELLED_NO_RETRY" }
        }

        @Synchronized internal fun fail(error: Exception) {
            if (failure == null) failure = error
        }

        fun launchConsent() {
            requireWithin(preparationDeadlineNanos)
            context.startActivity(Intent(context, TongyiCaptureConsentActivity::class.java)
                .putExtra(SESSION_ID, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        @Synchronized fun startRecording(width: Int, height: Int, densityDpi: Int, readyDeadlineNanos: Long) {
            requireWithin(minOf(preparationDeadlineNanos, readyDeadlineNanos))
            check(grantReady && activityClosed && !recordingRequested) { "PROJECTION_RECORDING_NOT_ADMITTED" }
            recordingRequested = true
            main.post {
                val ownedService = service
                if (ownedService == null) {
                    fail(IllegalStateException("PROJECTION_SERVICE_NOT_OWNED"))
                    close()
                } else ownedService.startOwnedRecording(width, height, densityDpi, readyDeadlineNanos)
            }
        }

        fun requireRecordingActive() {
            requireHealthy()
            check(recordingStarted && !resourcesReleased && !serviceDestroyed) { "PROJECTION_RECORDING_NOT_ACTIVE" }
        }

        fun close() {
            synchronized(this) {
                if (closed) return
                closed = true // Fence pending Activity results and main-thread acquisitions before posting cleanup.
            }
            main.post {
                runCatching { activity?.finishOwnedRequest() }.onFailure { closureFailed = true }
                runCatching { service?.closeOwnedResources() }.onFailure { closureFailed = true }
                runCatching {
                    if (serviceRequested && service == null && !serviceDestroyed) {
                        // The explicit pending start remains fenced; absence of onDestroy is not claimed as closure.
                        context.stopService(Intent(context, TongyiProjectionCaptureService::class.java))
                    }
                }.onFailure { closureFailed = true }
            }
        }

        fun isClosed(): Boolean = closed && activityClosed && resourcesReleased &&
            (!serviceRequested || serviceDestroyed) && !closureFailed

        fun releaseClosedSession() {
            check(isClosed()) { "PROJECTION_RESOURCE_CLOSURE_UNKNOWN" }
            synchronized(registryLock) { if (current === this) current = null }
        }
    }

    private var owned: Session? = null
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var surface: Surface? = null
    private var display: VirtualDisplay? = null
    private var recorderStarted = false
    private var releaseAttempted = false
    private var recordingWidth = 0
    private var recordingHeight = 0
    private val main = Handler(Looper.getMainLooper())
    private val stopped = object : MediaProjection.Callback() {
        override fun onStop() {
            val session = owned ?: return
            if (!session.closed) {
                session.fail(IllegalStateException("PROJECTION_REVOKED"))
                session.close()
            }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            val session = owned ?: return
            if (recordingWidth > 0 && (width != recordingWidth || height != recordingHeight)) {
                session.fail(IllegalStateException("PROJECTION_CAPTURE_SIZE_CHANGED"))
                session.close()
            }
        }
    }
    private val watchPreparation = object : Runnable {
        override fun run() {
            val session = owned ?: return
            if (session.closed) return
            try {
                session.requireHealthy()
                check(!File(session.root, "CANCEL").exists()) { "CANCELLED_NO_RETRY" }
                if (!session.recordingStarted) session.requireWithin(session.preparationDeadlineNanos)
                main.postDelayed(this, 50)
            } catch (error: Exception) {
                session.fail(error)
                session.close()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = find(intent?.getStringExtra(SESSION_ID))
        if (session == null) {
            if (owned == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (owned != null) {
            session.fail(IllegalStateException("PROJECTION_SERVICE_START_NOT_UNIQUE"))
            session.close()
            return START_NOT_STICKY
        }
        owned = session
        session.service = this
        session.resourcesReleased = false
        session.serviceDestroyed = false
        try {
            session.requireWithin(session.preparationDeadlineNanos)
            val request = checkNotNull(intent) { "PROJECTION_NORMAL_CONSENT_REQUIRED" }
            check(request.getIntExtra(RESULT_CODE, Activity.RESULT_CANCELED) == Activity.RESULT_OK) {
                "PROJECTION_NORMAL_CONSENT_REQUIRED"
            }
            val data = checkNotNull(request.getParcelableExtra<Intent>(RESULT_DATA)) { "PROJECTION_NORMAL_CONSENT_REQUIRED" }
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "ANEB private screen capture", NotificationManager.IMPORTANCE_LOW))
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("ANEB private screen capture").setContentText("User-authorized engineering session")
                .setOngoing(true).build()
            startForeground(807, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            session.requireWithin(session.preparationDeadlineNanos)
            projection = checkNotNull(getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(Activity.RESULT_OK, data)) { "PROJECTION_GRANT_NOT_AVAILABLE" }
            projection!!.registerCallback(stopped, main)
            session.requireWithin(session.preparationDeadlineNanos)
            session.grantReady = true // No recorder, Surface or VirtualDisplay exists yet.
            session.activity?.finishOwnedRequest()
            main.post(watchPreparation)
        } catch (error: Exception) {
            session.fail(error)
            session.close()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startOwnedRecording(width: Int, height: Int, densityDpi: Int, readyDeadlineNanos: Long) {
        val session = checkNotNull(owned)
        try {
            session.requireWithin(minOf(session.preparationDeadlineNanos, readyDeadlineNanos))
            check(width > 0 && height > 0 && densityDpi > 0 && recorder == null && projection != null) {
                "PROJECTION_ACTUAL_DISPLAY_REQUIRED"
            }
            val video = File(session.root, "video.mp4")
            check(!video.exists()) { "PROJECTION_VIDEO_ALREADY_EXISTS_NO_RETRY" }
            recordingWidth = width
            recordingHeight = height
            val encoder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            recorder = encoder // Every successful acquisition becomes owned before its next operation.
            encoder.setOnErrorListener { _, what, extra ->
                session.fail(IllegalStateException("PROJECTION_ENCODER_ERROR_${what}_$extra"))
                session.close()
            }
            encoder.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    session.fail(IllegalStateException("PROJECTION_CAPTURE_90_SECOND_LIMIT"))
                    session.close()
                }
            }
            encoder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            encoder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            encoder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            encoder.setVideoSize(width, height) // Unsupported actual size must fail; no crop/scale fallback.
            encoder.setVideoFrameRate(30)
            encoder.setVideoEncodingBitRate(8_000_000)
            encoder.setMaxDuration((TongyiEngineeringRunner.CAPTURE_NANOS / 1_000_000L).toInt())
            encoder.setOutputFile(video.absolutePath)
            encoder.prepare()
            session.requireWithin(minOf(session.preparationDeadlineNanos, readyDeadlineNanos))
            surface = encoder.surface
            display = checkNotNull(projection!!.createVirtualDisplay("ANEB-private-${session.id}", width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, main)) { "PROJECTION_VIRTUAL_DISPLAY_REQUIRED" }
            session.requireWithin(minOf(session.preparationDeadlineNanos, readyDeadlineNanos))
            session.mediaRequired = true
            encoder.start()
            recorderStarted = true
            session.requireWithin(minOf(session.preparationDeadlineNanos, readyDeadlineNanos))
            session.recordingStarted = true
        } catch (error: Exception) {
            session.fail(error)
            session.close()
        }
    }

    private fun closeOwnedResources() {
        if (releaseAttempted) return
        releaseAttempted = true
        val session = owned ?: return
        main.removeCallbacks(watchPreparation)
        var released = true
        fun release(operation: () -> Unit) {
            try { operation() } catch (_: Exception) { released = false }
        }
        display?.let { release { it.release(); display = null } }
        recorder?.let {
            if (recorderStarted) release { it.stop() }
            release { it.release(); recorder = null }
        }
        surface?.let { release { it.release(); surface = null } }
        projection?.let {
            release { it.unregisterCallback(stopped) }
            release { it.stop(); projection = null }
        }
        release { stopForeground(STOP_FOREGROUND_REMOVE) }
        session.resourcesReleased = released && display == null && recorder == null && surface == null && projection == null
        if (!session.resourcesReleased) session.closureFailed = true
        stopSelf()
    }

    override fun onDestroy() {
        val session = owned
        if (session != null) {
            if (!session.closed) {
                session.fail(IllegalStateException("PROJECTION_SERVICE_DESTROYED"))
                session.close()
            }
            closeOwnedResources()
            session.service = null
            session.serviceDestroyed = true
        }
        super.onDestroy()
    }

    companion object {
        internal const val SESSION_ID = "projection_session_id"
        internal const val RESULT_CODE = "projection_result_code"
        internal const val RESULT_DATA = "projection_result_data"
        private const val CHANNEL = "aneb_private_projection"
        private val registryLock = Any()
        private var current: Session? = null

        fun createSession(context: Context, root: File, preparationDeadlineNanos: Long): Session = synchronized(registryLock) {
            check(current == null && root.isDirectory) { "PROJECTION_SESSION_NOT_UNIQUE" }
            Session(context.applicationContext, root, preparationDeadlineNanos).also { current = it }
        }

        internal fun find(id: String?): Session? = synchronized(registryLock) { current?.takeIf { it.id == id } }
    }
}

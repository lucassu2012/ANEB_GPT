package com.aneb.probe.ui.research

import android.media.MediaPlayer
import android.widget.VideoView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual Reader playback binding with platform shadow callbacks; not device decoding proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class EngineeringCapturePlaybackTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Page : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun stoppedSelectionCannotPlayAgainFromQueuedPreparedCallback() {
        val page = Page()
        page.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        page.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        page.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val clip = temporary.newFile("video.mp4")
        checkNotNull(javaClass.getResourceAsStream("/engineering-reader-sample.mp4")).use { input ->
            clip.outputStream().use { input.copyTo(it) }
        }
        val view = VideoView(RuntimeEnvironment.getApplication())
        EngineeringCapturePlaybackSession(view, clip, page.lifecycle) {}.use {
            val prepared = checkNotNull(shadowOf(view).onPreparedListener)
            val player = MediaPlayer()
            try {
                prepared.onPrepared(player)
                assertTrue("Explicitly selected foreground clip can start", view.isPlaying)
                page.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                page.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                assertFalse("Background stops playback", view.isPlaying)
                page.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                page.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                // The platform may already have queued this callback before listener removal.
                prepared.onPrepared(player)
                assertFalse("Returning without selecting a clip must not restart a revoked selection", view.isPlaying)
            } finally { player.release() }
        }
    }
}

package com.aneb.probe.ui.prototype

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PrototypeZipShareLauncherTest {
    @Test
    fun `published zip opens a read-granted chooser with the exact uri`() {
        val context = RecordingContext(RuntimeEnvironment.getApplication())
        val publishedUri = Uri.parse("content://downloads/aneb/42?opaque=sentinel")

        assertTrue(PrototypeZipShareLauncher.open(context, publishedUri.toString()))

        val chooser = context.startedIntents.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Share ANEB Prototype ZIP", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull(send)
        requireNotNull(send)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/zip", send.type)
        assertEquals(
            publishedUri,
            send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java),
        )
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val clipData = requireNotNull(send.clipData)
        assertEquals(1, clipData.itemCount)
        assertEquals(publishedUri, clipData.getItemAt(0).uri)
    }

    @Test
    fun `unavailable or forbidden share targets return false`() {
        listOf(
            ActivityNotFoundException("no share target"),
            SecurityException("share denied"),
        ).forEach { failure ->
            val context = RecordingContext(
                base = RuntimeEnvironment.getApplication(),
                startFailure = failure,
            )

            assertFalse(
                PrototypeZipShareLauncher.open(
                    context,
                    "content://downloads/aneb/unavailable",
                ),
            )
            assertEquals(1, context.startedIntents.size)
        }
    }

    @Test
    fun `unexpected runtime failures propagate unchanged`() {
        val fatal = IllegalStateException("activity launch invariant failed")
        val context = RecordingContext(
            base = RuntimeEnvironment.getApplication(),
            startFailure = fatal,
        )

        val thrown = runCatching {
            PrototypeZipShareLauncher.open(
                context,
                "content://downloads/aneb/runtime-failure",
            )
        }.exceptionOrNull()

        assertSame(fatal, thrown)
        assertEquals(1, context.startedIntents.size)
    }

    private class RecordingContext(
        base: Context,
        private val startFailure: RuntimeException? = null,
    ) : ContextWrapper(base) {
        val startedIntents = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            startedIntents += intent
            startFailure?.let { throw it }
        }
    }
}

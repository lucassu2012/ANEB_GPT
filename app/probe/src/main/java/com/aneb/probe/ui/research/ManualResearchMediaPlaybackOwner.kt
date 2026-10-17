package com.aneb.probe.ui.research

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** One screen-local explicit playback selection; late results cannot revive revoked requests. */
internal class ManualResearchMediaPlaybackOwner {
    data class Request(val sourceId: String, val attemptId: String, val revision: Long)
    data class Selection(val request: Request, val file: File)

    var selection: Selection? by mutableStateOf(null)
        private set
    private var revision = 0L
    private var current: Request? = null

    fun request(sourceId: String, attemptId: String): Request {
        selection = null
        return Request(sourceId, attemptId, ++revision).also { current = it }
    }

    fun activate(request: Request, file: File, foreground: Boolean): Boolean {
        if (current != request) return false
        if (!foreground) {
            release(request)
            return false
        }
        selection = Selection(request, file)
        return true
    }

    fun release(request: Request) {
        if (current == request) stop()
    }

    fun stop() {
        current = null
        selection = null
    }
}

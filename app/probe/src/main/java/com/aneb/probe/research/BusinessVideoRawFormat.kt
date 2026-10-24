package com.aneb.probe.research

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Single-run collector result reader only; independent of debug execution and legacy V0/VF. */
internal object BusinessVideoRawFormat {
    const val FORMAT = "business-video-raw-1"

    fun matches(root: JsonObject): Boolean = root.text("format") == FORMAT

    fun readAttempts(root: JsonObject): List<ResearchAttempt> = try {
        require(matches(root) && !root.containsKey("records") && !root.containsKey("attempts"))
        require(root.text("runId")?.matches(Regex("[A-Za-z0-9_-]{1,64}")) == true)
        require(root.text("profileSha256")?.matches(SHA256) == true)
        require(root.text("conditionId")?.matches(Regex("[A-Za-z0-9_-]{1,64}")) == true)
        require(!root.text("clockId").isNullOrBlank())
        require(root.text("state") in setOf("CAPTURED_AWAITING_REVIEW", "STOPPED"))
        require(!root.text("category").isNullOrBlank())
        require(root["reason"] == JsonNull || root.text("reason") != null)
        require(root.text("swipeCalls") in setOf("0", "1", "UNKNOWN"))
        val cleanup = root["ownedCleanup"] as? JsonPrimitive
        require(cleanup != null && !cleanup.isString && cleanup.booleanOrNull != null)
        // Raw capture has no reviewed playback classifier or MP4/clock bridge.
        require(root["firstObservedPlaybackWaitMs"] == JsonNull && root["playerFirstFrameMs"] == JsonNull)
        require(root.text("stallStatus") == "not_computed")
        val events = root["events"] as? JsonArray ?: error("events")
        var previousEventNs = -1L
        events.forEach { element ->
            val event = element as? JsonObject ?: error("event")
            require(!event.text("name").isNullOrBlank())
            val atNs = event.nonNegativeLong("atNs")
            require(atNs >= previousEventNs)
            previousEventNs = atNs
        }
        val frames = root["frames"] as? JsonArray ?: error("frames")
        val seenPaths = mutableSetOf<String>()
        var previousWrittenNs = -1L
        frames.forEachIndexed { index, element ->
            val frame = element as? JsonObject ?: error("frame")
            require(frame.nonNegativeLong("sequence") == index.toLong())
            val requested = frame.nonNegativeLong("requestedNs")
            val returned = frame.nonNegativeLong("returnedNs")
            val written = frame.nonNegativeLong("writtenNs")
            require(requested >= previousWrittenNs && returned >= requested && written >= returned)
            previousWrittenNs = written
            val path = frame.text("relativePath") ?: error("relativePath")
            // Text-only relative references: never resolve, open, or verify referenced media.
            require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path)
            require(path.none { it.code < 32 || it.code == 127 })
            require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } && seenPaths.add(path))
            require(frame.text("sha256")?.matches(SHA256) == true)
            require(frame.nonNegativeLong("bytes") > 0L)
        }
        listOf(ResearchAttempt(root, isBusinessVideo = true))
    } catch (_: Exception) {
        throw ResearchImportException("无法导入原始视频采集：请核对 business-video-raw-1 的单次 run、身份、状态、时钟与帧引用；整份未保存，未转换为旧 V0/VF。")
    }

    private fun JsonObject.nonNegativeLong(key: String): Long {
        val primitive = this[key] as? JsonPrimitive ?: error(key)
        require(!primitive.isString)
        return (primitive.longOrNull ?: error(key)).also { require(it >= 0L) }
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}

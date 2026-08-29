package com.aneb.probe.prototype

import com.aneb.probe.net.AnebClient
import com.aneb.probe.net.RawSseStream

/** Bridges the Prototype runner transport seam to the existing AnebClient HTTP/SSE path. */
class AnebClientPrototypeRawPostTransport(
    private val client: AnebClient,
) : PrototypeRawPostTransport {
    override suspend fun post(url: String, requestBody: String): RawSseStream =
        client.postPrototypeRawSse(url, requestBody)
}

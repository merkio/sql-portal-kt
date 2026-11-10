package io.merkio.sqlportal.adapters

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer

/** A real HTTP server on a free local port, for adapters that make HTTP calls. */
class TestServer(
    module: Application.() -> Unit,
) : AutoCloseable {
    private val server: EmbeddedServer<*, *> =
        embeddedServer(
            CIO,
            port = 0,
            host = "127.0.0.1",
            module = module,
        ).start()

    suspend fun url(path: String = ""): String =
        "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}$path"

    override fun close() = server.stop(0, 0)
}

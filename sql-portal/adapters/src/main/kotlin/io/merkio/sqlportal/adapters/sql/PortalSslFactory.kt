package io.merkio.sqlportal.adapters.sql

import io.merkio.sqlportal.common.tls.TlsOptions
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Path
import java.util.Properties
import javax.net.ssl.SSLSocketFactory

/**
 * The PostgreSQL driver's socket factory (`sslfactory`) for the portal's TLS
 * options: CA files and client keys in PEM form, as for every other connection.
 * The driver creates it with the connection properties, where [properties]
 * put the option file paths.
 */
class PortalSslFactory(
    info: Properties,
) : SSLSocketFactory() {
    private val delegate: SSLSocketFactory =
        TlsOptions(
            caFile = info.getProperty(CA)?.let(Path::of),
            insecure = info.getProperty(INSECURE) == "true",
            certFile = info.getProperty(CERT)?.let(Path::of),
            keyFile = info.getProperty(KEY)?.let(Path::of),
        ).sslContext().socketFactory

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(
        s: Socket?,
        host: String?,
        port: Int,
        autoClose: Boolean,
    ): Socket = delegate.createSocket(s, host, port, autoClose)

    override fun createSocket(
        host: String?,
        port: Int,
    ): Socket = delegate.createSocket(host, port)

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket = delegate.createSocket(host, port, localHost, localPort)

    override fun createSocket(
        host: InetAddress?,
        port: Int,
    ): Socket = delegate.createSocket(host, port)

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = delegate.createSocket(address, port, localAddress, localPort)

    companion object {
        private const val CA = "sqlportal.tls.ca_file"
        private const val INSECURE = "sqlportal.tls.insecure"
        private const val CERT = "sqlportal.tls.cert_file"
        private const val KEY = "sqlportal.tls.key_file"

        /** The driver properties that carry [tls] to the factory. */
        fun properties(tls: TlsOptions): Map<String, String> =
            buildMap {
                tls.caFile?.let { put(CA, it.toString()) }
                if (tls.insecure) put(INSECURE, "true")
                tls.certFile?.let { put(CERT, it.toString()) }
                tls.keyFile?.let { put(KEY, it.toString()) }
            }
    }
}

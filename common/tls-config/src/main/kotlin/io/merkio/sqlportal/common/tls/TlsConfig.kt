package io.merkio.sqlportal.common.tls

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path

/** A `tls` block as written in a configuration file; paths are not resolved yet. */
@Serializable
data class TlsConfig(
    @SerialName("ca_file") val caFile: String? = null,
    /** Accept any server certificate without verification. Development only. */
    val insecure: Boolean = false,
    @SerialName("cert_file") val certFile: String? = null,
    @SerialName("key_file") val keyFile: String? = null,
) {
    /** The options with every file path resolved by [resolve]. */
    fun toOptions(resolve: (String) -> Path) =
        TlsOptions(
            caFile = caFile?.let(resolve),
            insecure = insecure,
            certFile = certFile?.let(resolve),
            keyFile = keyFile?.let(resolve),
        )
}

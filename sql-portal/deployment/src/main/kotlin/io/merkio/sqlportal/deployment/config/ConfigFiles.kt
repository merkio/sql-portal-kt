package io.merkio.sqlportal.deployment.config

import java.net.URI
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Finds the files a configuration refers to (SQL files, migrations, init
 * files, certificates). An absolute path is used as is. A relative path is
 * looked up next to the file named by `SCRIPT_PORTAL_CONFIG` (when there is
 * one), then among the bundled resources under `config/`, which also
 * works when they are packed in the application jar.
 */
class ConfigFiles(
    private val baseDir: Path?,
    private val classLoader: ClassLoader = ConfigFiles::class.java.classLoader,
) {
    fun resolve(relative: String): Path {
        val path = Path.of(relative)
        if (path.isAbsolute) return path
        baseDir
            ?.resolve(path)
            ?.normalize()
            ?.takeIf { it.exists() }
            ?.let { return it }
        bundled("$RESOURCE_ROOT/${relative.removePrefix("./")}")?.let { return it }
        // Missing: whoever reads the file reports it under this name.
        return (baseDir ?: Path.of("")).resolve(path).normalize()
    }

    private fun bundled(name: String): Path? {
        val uri = classLoader.getResource(name)?.toURI() ?: return null
        return when (uri.scheme) {
            "file" -> Path.of(uri)
            "jar" -> inJar(uri)
            else -> null
        }
    }

    companion object {
        /** Where the bundled configuration lives on the classpath. */
        const val RESOURCE_ROOT = "config"

        /** Opens the jar as a file system once, so directories in it can be listed. */
        @Synchronized
        private fun inJar(uri: URI): Path {
            try {
                FileSystems.getFileSystem(uri)
            } catch (_: FileSystemNotFoundException) {
                FileSystems.newFileSystem(uri, emptyMap<String, Any>())
            }
            return Path.of(uri)
        }
    }
}

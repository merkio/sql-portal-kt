/*
 * Template rendering on Freemarker.
 *
 * A [Templates] set loads templates from the classpath, so a deployment needs
 * no template files at runtime. Templates ending in `.ftlh` are HTML: every
 * interpolation is escaped; trusted fragments are passed with `?no_esc`.
 */
package io.merkio.sqlportal.common.template

import freemarker.cache.ClassTemplateLoader
import freemarker.core.ParseException
import freemarker.template.Configuration
import freemarker.template.TemplateException
import freemarker.template.TemplateExceptionHandler
import java.io.IOException
import java.io.StringWriter
import java.security.MessageDigest

class TemplateException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

/** A set of templates under [basePath] on the classpath of [owner]. */
class Templates(
    owner: Class<*>,
    basePath: String,
) {
    private val config =
        Configuration(Configuration.VERSION_2_3_34).apply {
            templateLoader = ClassTemplateLoader(owner, basePath)
            defaultEncoding = "UTF-8"
            recognizeStandardFileExtensions = true
            templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
            logTemplateExceptions = false
            wrapUncheckedExceptions = true
            fallbackOnNullLoopVariable = false
            // Numbers render as plain digits ("1234", not "1,234").
            numberFormat = "computer"
            booleanFormat = "c"
        }

    /**
     * Compiles the named templates, so a broken template fails at startup.
     *
     * @throws TemplateException for the first invalid template.
     */
    fun check(names: List<String>) {
        for (name in names) {
            try {
                config.getTemplate(name)
            } catch (e: ParseException) {
                throw TemplateException("template $name is invalid: ${e.message}", e)
            } catch (e: IOException) {
                throw TemplateException("template $name cannot be loaded: ${e.message}", e)
            }
        }
    }

    /**
     * Renders [name] with [model]. An unknown name is an error.
     *
     * @throws TemplateException
     */
    fun render(
        name: String,
        model: Map<String, Any?>,
    ): String =
        try {
            StringWriter().also { config.getTemplate(name).process(model, it) }.toString()
        } catch (e: freemarker.template.TemplateException) {
            throw TemplateException("template $name could not be rendered: ${e.message}", e)
        } catch (e: IOException) {
            throw TemplateException("template $name could not be rendered: ${e.message}", e)
        }
}

/** A short version string that changes whenever any of [contents] changes. */
fun contentVersion(vararg contents: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (content in contents) {
        digest.update(content.toByteArray())
        digest.update(0)
    }
    return digest.digest().take(VERSION_BYTES).joinToString("") { "%02x".format(it) }
}

private const val VERSION_BYTES = 8

/*
 * Values logback.xml reads through `<define>` before anything is logged, so
 * the log format follows the environment from the very first line.
 */
package io.merkio.sqlportal.deployment.observability

import ch.qos.logback.core.PropertyDefinerBase
import io.merkio.sqlportal.deployment.config.environment

/**
 * `TEXT` (a plain console line) in the `local` environment, `JSON` (one
 * object per line, with the MDC, app name and environment) everywhere else:
 * `k8s`, `uat`, `prod`. `LOG_FORMAT=text|json` overrides the choice.
 */
class LogFormatDefiner : PropertyDefinerBase() {
    override fun getPropertyValue(): String = logFormat(System::getenv)
}

/** The environment name for the `env` field of JSON logs. */
class EnvironmentDefiner : PropertyDefinerBase() {
    override fun getPropertyValue(): String = environment(System::getenv)
}

/** The application version for the `version` field of JSON logs. */
class VersionDefiner : PropertyDefinerBase() {
    override fun getPropertyValue(): String = VERSION
}

/** `TEXT` or `JSON`, the names of the appenders in logback.xml. */
fun logFormat(vars: (String) -> String?): String =
    when (vars("LOG_FORMAT")?.trim()?.lowercase()) {
        "text" -> "TEXT"
        "json" -> "JSON"
        else -> if (environment(vars) == LOCAL) "TEXT" else "JSON"
    }

private const val LOCAL = "local"

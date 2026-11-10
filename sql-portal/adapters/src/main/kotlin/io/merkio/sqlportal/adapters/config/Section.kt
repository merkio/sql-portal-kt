package io.merkio.sqlportal.adapters.config

import com.typesafe.config.ConfigList
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigValueType

/**
 * Typed, strict access to one object of the configuration. Every key read is
 * remembered, and [done] rejects keys nobody read, so a typo is an error
 * instead of a silently ignored setting. Scalars written as strings (for
 * example from `${?ENV}` substitution) are accepted for booleans and numbers.
 */
class Section(
    private val obj: ConfigObject,
    /** Dotted path of this object, for error messages; empty at the root. */
    val path: String,
) {
    private val read = mutableSetOf<String>()

    private fun key(name: String) = if (path.isEmpty()) name else "$path.$name"

    private fun value(name: String): ConfigValue? {
        read += name
        return obj[name]?.takeIf { it.valueType() != ConfigValueType.NULL }
    }

    private fun invalid(
        name: String,
        expected: String,
    ): Nothing = throw ConfigError.Invalid("${key(name)} must be $expected")

    fun has(name: String) = obj[name] != null

    fun stringOrNull(name: String): String? {
        val v = value(name) ?: return null
        return when (v.valueType()) {
            ConfigValueType.STRING, ConfigValueType.NUMBER, ConfigValueType.BOOLEAN -> v.unwrapped().toString()
            else -> invalid(name, "a string")
        }
    }

    fun string(name: String): String = stringOrNull(name) ?: throw ConfigError.Invalid("${key(name)} is required")

    fun string(
        name: String,
        default: String,
    ): String = stringOrNull(name) ?: default

    fun boolean(
        name: String,
        default: Boolean,
    ): Boolean {
        val v = value(name) ?: return default
        return when (val raw = v.unwrapped()) {
            is Boolean -> raw
            is String ->
                when (raw.trim().lowercase()) {
                    "true", "yes", "on" -> true
                    "false", "no", "off" -> false
                    else -> invalid(name, "true or false")
                }
            else -> invalid(name, "true or false")
        }
    }

    private fun number(name: String): Number? {
        val v = value(name) ?: return null
        return when (val raw = v.unwrapped()) {
            is Number -> raw
            is String -> raw.trim().toBigDecimalOrNull() ?: invalid(name, "a number")
            else -> invalid(name, "a number")
        }
    }

    fun longOrNull(name: String): Long? =
        number(name)?.let {
            val d = it.toDouble()
            if (d != Math.rint(d)) invalid(name, "a whole number")
            it.toLong()
        }

    fun long(
        name: String,
        default: Long,
    ): Long = longOrNull(name) ?: default

    fun intOrNull(name: String): Int? =
        longOrNull(name)?.let {
            if (it !in Int.MIN_VALUE..Int.MAX_VALUE) invalid(name, "a smaller number")
            it.toInt()
        }

    fun int(
        name: String,
        default: Int,
    ): Int = intOrNull(name) ?: default

    fun doubleOrNull(name: String): Double? = number(name)?.toDouble()

    fun list(name: String): List<ConfigValue> {
        val v = value(name) ?: return emptyList()
        return (v as? ConfigList) ?: invalid(name, "a list")
    }

    fun stringList(name: String): List<String> =
        list(name).mapIndexed { i, item ->
            when (item.valueType()) {
                ConfigValueType.STRING, ConfigValueType.NUMBER, ConfigValueType.BOOLEAN -> item.unwrapped().toString()
                else -> throw ConfigError.Invalid("${key(name)}[$i] must be a string")
            }
        }

    fun sectionOrNull(name: String): Section? {
        val v = value(name) ?: return null
        return Section(v as? ConfigObject ?: invalid(name, "an object"), key(name))
    }

    fun section(name: String): Section = sectionOrNull(name) ?: Section(EMPTY, key(name))

    fun sections(name: String): List<Section> =
        list(name).mapIndexed { i, item ->
            Section(
                item as? ConfigObject ?: throw ConfigError.Invalid("${key(name)}[$i] must be an object"),
                "${key(name)}[$i]",
            )
        }

    /** An object whose keys are free names (roles, groups, users) mapping to lists of strings. */
    fun stringListMap(name: String): Map<String, List<String>> {
        val inner = sectionOrNull(name) ?: return emptyMap()
        return inner.keys().associateWith { inner.stringList(it) }
    }

    fun keys(): Set<String> = obj.keys

    /** Rejects keys that were never read. */
    fun done() {
        val unknown = obj.keys - read
        if (unknown.isNotEmpty()) {
            throw ConfigError.Invalid("unknown key '${key(unknown.sorted().first())}'")
        }
    }

    private companion object {
        val EMPTY: ConfigObject =
            com.typesafe.config.ConfigFactory
                .empty()
                .root()
    }
}

/** The text of a scalar (`2024` → "2024"); `null` for null, lists and objects. */
fun scalarText(value: ConfigValue?): String? =
    when (value?.valueType()) {
        ConfigValueType.STRING, ConfigValueType.NUMBER, ConfigValueType.BOOLEAN -> value.unwrapped().toString()
        else -> null
    }

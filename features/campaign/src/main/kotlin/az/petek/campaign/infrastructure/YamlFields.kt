package az.petek.campaign.infrastructure

import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import kotlin.time.Duration

/** The keys of one YAML map, read through the owning [YamlReader] so problems carry lines. */
internal class YamlFields(
    private val reader: YamlReader,
    private val map: YamlMap,
    val path: String,
) {
    val keys: List<String> get() = map.entries.keys.map { it.content }

    operator fun get(key: String): YamlNode? =
        map.entries.entries
            .firstOrNull { it.key.content == key }
            ?.value
            .plain()

    fun has(key: String): Boolean = get(key) != null

    /** Whether [key] is written at all, even without a value (`do:`). */
    fun declares(key: String): Boolean = key in keys

    fun pathOf(key: String): String = childPath(path, key)

    /**
     * The node under an optional [key]; null when the key is absent. A key written without a value is a problem:
     * for keys like `do`, `assert` or `wait_for` an empty value would silently turn the step into one that checks less.
     */
    fun valued(key: String): YamlNode? =
        get(key) ?: if (declares(key)) reader.problem(pathOf(key), "${displayPath(pathOf(key))} has no value") else null

    /** The node under [key], or a "missing key" / "no value" problem. */
    fun required(key: String): YamlNode? =
        get(key) ?: if (key in keys) {
            reader.problem(pathOf(key), "${displayPath(pathOf(key))} has no value")
        } else {
            reader.problem(path, "missing required key '$key' in ${displayPath(path)}")
        }

    fun text(
        key: String,
        required: Boolean = false,
    ): String? = node(key, required)?.let { reader.text(it, pathOf(key)) }

    fun int(
        key: String,
        required: Boolean,
    ): Int? = node(key, required)?.let { reader.int(it, pathOf(key)) }

    fun long(
        key: String,
        required: Boolean,
    ): Long? = node(key, required)?.let { reader.long(it, pathOf(key)) }

    fun bool(key: String): Boolean? = reader.bool(get(key), pathOf(key))

    fun seconds(key: String): Duration? = reader.seconds(get(key), pathOf(key))

    fun textList(
        key: String,
        required: Boolean = false,
    ): List<String>? = node(key, required)?.let { reader.textList(it, pathOf(key)) }

    fun map(
        key: String,
        allowed: Set<String>,
        required: Boolean = false,
    ): YamlFields? = node(key, required)?.let { reader.map(it, pathOf(key), allowed) }

    private fun node(
        key: String,
        required: Boolean,
    ): YamlNode? = if (required) required(key) else get(key)
}

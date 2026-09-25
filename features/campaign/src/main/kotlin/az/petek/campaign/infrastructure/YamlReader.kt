package az.petek.campaign.infrastructure

import az.petek.campaign.domain.SourceLines
import az.petek.campaign.domain.ValidationIssue
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Typed, path-aware access to a kaml node tree. Problems are recorded (with the line of the offending node) instead of
 * thrown, so a single load reports every mistake in the file. Readers return null for an absent node and for a node
 * that had a problem; callers apply defaults freely because any recorded problem fails the load anyway.
 */
internal class YamlReader(
    private val lines: SourceLines,
) {
    private val found = mutableListOf<ValidationIssue>()

    /** Everything recorded so far, in file order. */
    val issues: List<ValidationIssue>
        get() = found.sortedWith(compareBy(nullsFirst()) { it.line })

    fun problem(
        path: String,
        message: String,
    ): Nothing? {
        found += ValidationIssue(lines.lineOf(path), message)
        return null
    }

    fun addAll(issues: List<ValidationIssue>) {
        found += issues
    }

    fun lineOf(path: String): Int? = lines.lineOf(path)

    /** A map whose keys must all be in [allowed]; unknown keys are problems (the schema is strict). */
    fun map(
        node: YamlNode?,
        path: String,
        allowed: Set<String>,
    ): YamlFields? {
        val plain = node.plain() ?: return null
        if (plain !is YamlMap) return problem(path, "${displayPath(path)} must be a map with keys ${allowed.joinToString(", ")}")
        plain.entries.keys
            .filter { it.content !in allowed }
            .forEach {
                problem(
                    childPath(path, it.content),
                    "unknown key '${it.content}' in ${displayPath(path)} (allowed: ${allowed.joinToString(", ")})",
                )
            }
        return YamlFields(this, plain, path)
    }

    /** Items of a list with their paths (`steps[0]`, ...). */
    fun list(
        node: YamlNode?,
        path: String,
    ): List<YamlItem>? {
        val plain = node.plain() ?: return null
        if (plain !is YamlList) return problem(path, "${displayPath(path)} must be a list")
        return plain.items.mapIndexed { index, item -> YamlItem("$path[$index]", item.plain(), item.location.line) }
    }

    fun text(
        node: YamlNode?,
        path: String,
    ): String? {
        val plain = node.plain() ?: return null
        return (plain as? YamlScalar)?.content ?: problem(path, "${displayPath(path)} must be a single value, not a list or map")
    }

    fun int(
        node: YamlNode?,
        path: String,
    ): Int? = text(node, path)?.let { it.trim().toIntOrNull() ?: problem(path, "${displayPath(path)} must be an integer, was '$it'") }

    fun long(
        node: YamlNode?,
        path: String,
    ): Long? = text(node, path)?.let { it.trim().toLongOrNull() ?: problem(path, "${displayPath(path)} must be an integer, was '$it'") }

    fun bool(
        node: YamlNode?,
        path: String,
    ): Boolean? =
        text(node, path)?.let {
            when (it.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> problem(path, "${displayPath(path)} must be true or false, was '$it'")
            }
        }

    /** A number of seconds, integer or decimal (`within_s: 2.5`). */
    fun seconds(
        node: YamlNode?,
        path: String,
    ): Duration? =
        text(node, path)?.let {
            it
                .trim()
                .toDoubleOrNull()
                ?.takeIf(Double::isFinite)
                ?.seconds
                ?: problem(path, "${displayPath(path)} must be a number of seconds, was '$it'")
        }

    fun textList(
        node: YamlNode?,
        path: String,
    ): List<String>? {
        val items = list(node, path) ?: return null
        val texts =
            items.map { item ->
                if (item.node ==
                    null
                ) {
                    problem(item.path, "${displayPath(item.path)} is empty")
                } else {
                    text(item.node, item.path)
                }
            }
        return texts.takeIf { values -> values.all { it != null } }?.filterNotNull()
    }
}

/** A list item: its path, its node (null when empty) and its line. */
internal data class YamlItem(
    val path: String,
    val node: YamlNode?,
    val line: Int,
)

package az.petek.explorer.domain

/**
 * The `robots.txt` rules that apply to the explorer. The group naming the `petek` user agent wins, otherwise the `*`
 * group. Rules follow the common (RFC 9309) reading: the longest matching `Allow`/`Disallow` path wins, `Allow` wins a
 * tie, `*` matches any characters and a trailing `$` anchors the end. An empty `Disallow:` allows everything.
 */
class RobotsRules private constructor(
    private val rules: List<Rule>,
) {
    private data class Rule(
        val allow: Boolean,
        val path: String,
        val regex: Regex,
    )

    /** Number of disallow rules in effect (for the owner: "robots.txt kept N paths out"). */
    val disallowCount: Int get() = rules.count { !it.allow }

    /** Whether the explorer may visit [path] (a raw path, optionally with query). */
    fun allows(path: String): Boolean {
        val target = path.ifEmpty { "/" }
        val winner =
            rules
                .filter { it.regex.matchesAt(target, 0) }
                .maxWithOrNull(compareBy<Rule> { it.path.length }.thenBy { it.allow })
        return winner?.allow ?: true
    }

    companion object {
        val NONE: RobotsRules = RobotsRules(emptyList())

        const val AGENT = "petek"

        fun parse(
            text: String,
            agent: String = AGENT,
        ): RobotsRules {
            val groups = groups(text)
            val own = groups.filter { group -> group.agents.any { it != "*" && agent.lowercase().contains(it) } }
            val chosen = own.ifEmpty { groups.filter { "*" in it.agents } }
            return RobotsRules(chosen.flatMap { it.rules })
        }

        private class Group(
            val agents: MutableList<String> = mutableListOf(),
            val rules: MutableList<Rule> = mutableListOf(),
        )

        private fun groups(text: String): List<Group> {
            val groups = mutableListOf<Group>()
            var current: Group? = null
            var readingAgents = false
            text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.contains(':') }.forEach { line ->
                val key = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':').trim()
                when (key) {
                    "user-agent" -> {
                        val group = current?.takeIf { readingAgents } ?: Group().also(groups::add)
                        group.agents.add(value.lowercase())
                        current = group
                        readingAgents = true
                    }

                    "allow", "disallow" -> {
                        readingAgents = false
                        if (value.isNotEmpty()) current?.rules?.add(Rule(key == "allow", value, toRegex(value)))
                    }

                    else -> {
                        readingAgents = false
                    }
                }
            }
            return groups
        }

        private fun toRegex(path: String): Regex {
            val anchored = path.endsWith("$")
            val body = if (anchored) path.dropLast(1) else path
            val pattern = body.split('*').joinToString(".*") { Regex.escape(it) }
            return Regex(if (anchored) "$pattern$" else pattern)
        }
    }
}

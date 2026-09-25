package az.petek.core.ids

/** Every entity the harness records carries an id (CLAUDE.md rule 4). Values are opaque strings. */
@JvmInline
value class RunId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "RunId must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class AgentId(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "AgentId must look like a01..a999, was '$value'" }
    }

    /** 1-based position of the agent in the identity registry. */
    val index: Int get() = value.drop(1).toInt()

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("a\\d{2,3}")

        fun of(index: Int): AgentId {
            require(index in 1..999) { "Agent index must be in 1..999, was $index" }
            return AgentId("a" + index.toString().padStart(2, '0'))
        }
    }
}

@JvmInline
value class StepId(
    val value: String,
) {
    override fun toString(): String = value
}

@JvmInline
value class EventId(
    val value: String,
) {
    override fun toString(): String = value
}

@JvmInline
value class CorrelationId(
    val value: String,
) {
    override fun toString(): String = value
}

@JvmInline
value class ArtifactId(
    val value: String,
) {
    override fun toString(): String = value
}

@JvmInline
value class FindingId(
    val value: String,
) {
    override fun toString(): String = value
}

/** Short run marker embedded in test e-mails so identities never collide across runs (e.g. `k7x2`). */
@JvmInline
value class RunTag(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "RunTag must be 4 chars of [a-z0-9], was '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[a-z0-9]{4}")
    }
}

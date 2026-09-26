/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.core.ids

/**
 * The workspace a record belongs to (ADR-0011): always [LOCAL] on the owner's machine; a hosted edition sets one per
 * account. It joins the other ids of CLAUDE.md rule 4 so a shared store never mixes two owners' runs.
 */
@JvmInline
value class WorkspaceId(
    val value: String,
) {
    init {
        require(PATTERN.matches(value)) { "WorkspaceId must be lower-case letters, digits and '-' (1-64), was '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[a-z0-9][a-z0-9-]{0,63}")

        val LOCAL = WorkspaceId("local")
    }
}

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

/**
 * A tester's id: `a` plus its 1-based position in the identity registry, zero-padded to at least two digits
 * (`a01`..`a99`, `a100`, `a1000`, ...). There is no upper bound: how many testers run depends on the machine.
 * Only the canonical spelling is accepted (`a07`, never `a7` or `a007`), so one tester never has two ids.
 *
 * Ids compare by their numeric [index], so `a99 < a100` although the text `a100` sorts first. Everything listed
 * "by agent id" uses this order, never the order of [value].
 */
@JvmInline
value class AgentId(
    val value: String,
) : Comparable<AgentId> {
    init {
        require(isCanonical(value)) {
            "AgentId must be 'a' followed by a positive index zero-padded to at least 2 digits (a01, a99, a100), was '$value'"
        }
    }

    /** 1-based position of the agent in the identity registry. */
    val index: Int get() = value.drop(1).toInt()

    override fun compareTo(other: AgentId): Int = index.compareTo(other.index)

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("a\\d{2,}")

        fun of(index: Int): AgentId {
            require(index >= 1) { "Agent index must be positive, was $index" }
            return AgentId(format(index))
        }

        /** The id spelled [value], or null when [value] is not a canonical agent id (e.g. a path segment like `a007`). */
        fun parseOrNull(value: String): AgentId? = if (isCanonical(value)) AgentId(value) else null

        private fun format(index: Int): String = "a" + index.toString().padStart(2, '0')

        private fun isCanonical(value: String): Boolean {
            if (!PATTERN.matches(value)) return false
            val index = value.drop(1).toIntOrNull() ?: return false
            return index >= 1 && format(index) == value
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

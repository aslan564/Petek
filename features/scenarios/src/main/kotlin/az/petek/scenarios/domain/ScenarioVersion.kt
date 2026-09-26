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

package az.petek.scenarios.domain

import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/** Id of one stored scenario version (CLAUDE.md rule 4). Opaque, e.g. `scn_0192…`. */
@JvmInline
value class ScenarioVersionId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ScenarioVersionId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Where a version is in the owner's review (docs/PLAN.md Faza 7):
 * - [DRAFT]: proposed (imported, written by the explorer or by triage), not yet reviewed; not runnable by default.
 * - [APPROVED]: the owner accepted it; at most one APPROVED version per scenario name.
 * - [FROZEN]: an approved version locked as a baseline. Nothing about it ever changes again.
 * - [SUPERSEDED]: was APPROVED until a newer version of the same name was approved.
 */
enum class ScenarioStatus {
    DRAFT,
    APPROVED,
    FROZEN,
    SUPERSEDED,
    ;

    /** Only reviewed versions run by default; a draft runs only when the caller asks for it explicitly. */
    val runnable: Boolean get() = this == APPROVED || this == FROZEN
}

/** Who wrote the version: the owner (a file), the explorer agent (Faza 6), or a triage proposal (Faza 7). */
enum class ScenarioSource { USER, EXPLORER, TRIAGE }

/**
 * One immutable text of a scenario (a campaign YAML) plus its review state.
 *
 * The content ([name], [version], [yaml], [source], [parentId], [note], [createdAt]) never changes after the version is
 * stored; only the review fields ([status], [approvedAt], [frozenAt], [supersededBy]) move, and only through
 * [ScenarioLifecycle]. A [ScenarioStatus.FROZEN] version changes in no way at all.
 *
 * [version] counts per [name] from 1. [parentId] is the version this one was derived from (the previous file on
 * import, the triaged version for a triage proposal), so the owner can always see the change as a diff.
 */
data class ScenarioVersion(
    val id: ScenarioVersionId,
    val name: String,
    val version: Int,
    val yaml: String,
    val status: ScenarioStatus,
    val source: ScenarioSource,
    val parentId: ScenarioVersionId?,
    val note: String,
    val createdAt: Instant,
    val approvedAt: Instant? = null,
    val frozenAt: Instant? = null,
    /** The version whose approval superseded this one; set exactly when [status] is [ScenarioStatus.SUPERSEDED]. */
    val supersededBy: ScenarioVersionId? = null,
) {
    init {
        require(name.isNotBlank()) { "Scenario name must not be blank" }
        require(version >= 1) { "Scenario version must be >= 1, was $version" }
        require(parentId != id) { "Scenario version $id cannot be its own parent" }
        require((status == ScenarioStatus.DRAFT) == (approvedAt == null)) { "$label: approvedAt is set exactly when reviewed" }
        require((status == ScenarioStatus.FROZEN) == (frozenAt != null)) { "$label: frozenAt is set exactly when FROZEN" }
        require((status == ScenarioStatus.SUPERSEDED) == (supersededBy != null)) { "$label: supersededBy is set exactly when SUPERSEDED" }
    }

    /** Lowercase hex SHA-256 of the UTF-8 [yaml]; equals a run's `campaign_hash` when the run used this exact text. */
    val sha256: String get() = ScenarioHash.of(yaml)

    val runnable: Boolean get() = status.runnable

    /** Human label, e.g. `kadrohr-core v3`. */
    val label: String get() = "$name v$version"

    /** File name the scenario is checked and exported under; it also names a scenario whose YAML has no `campaign.name`. */
    val fileName: String get() = fileNameOf(name)

    /** True when [other] has the same content (everything except the review fields). */
    fun sameContentAs(other: ScenarioVersion): Boolean =
        id == other.id &&
            name == other.name &&
            version == other.version &&
            yaml == other.yaml &&
            source == other.source &&
            parentId == other.parentId &&
            note == other.note &&
            createdAt == other.createdAt

    companion object {
        fun fileNameOf(name: String): String = "$name.yaml"
    }
}

/** What a caller stores; the repository assigns [ScenarioVersion.version] and starts it as [ScenarioStatus.DRAFT]. */
data class NewScenarioVersion(
    val id: ScenarioVersionId,
    val name: String,
    val yaml: String,
    val source: ScenarioSource,
    val parentId: ScenarioVersionId?,
    val note: String,
    val createdAt: Instant,
) {
    init {
        require(name.isNotBlank()) { "Scenario name must not be blank" }
        require(parentId != id) { "Scenario version $id cannot be its own parent" }
    }

    fun toVersion(version: Int): ScenarioVersion =
        ScenarioVersion(id, name, version, yaml, ScenarioStatus.DRAFT, source, parentId, note, createdAt)
}

/**
 * A compare-and-set change of one version's review fields: it applies only while the stored version still has
 * [before]'s status. Content changes are impossible by construction, and a FROZEN version can never be [before].
 */
data class ScenarioVersionUpdate(
    val before: ScenarioVersion,
    val after: ScenarioVersion,
) {
    init {
        require(before.sameContentAs(after)) { "Only the review fields of ${before.label} may change" }
        require(before.status != ScenarioStatus.FROZEN) { "${before.label} is FROZEN and cannot change" }
        require(before.status != after.status) { "An update of ${before.label} must change its status" }
    }

    val id: ScenarioVersionId get() = before.id
}

/** SHA-256 of scenario text, in the same form as `Campaign.sourceHash` (hex of the UTF-8 bytes). */
object ScenarioHash {
    fun of(yaml: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(yaml.toByteArray(Charsets.UTF_8)))
}

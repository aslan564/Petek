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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant

class ScenarioLifecycleTest {
    private val t0 = Instant.parse("2026-01-01T10:00:00Z")
    private val now = Instant.parse("2026-01-02T10:00:00Z")

    private fun version(
        n: Int,
        status: ScenarioStatus,
        name: String = "mini",
        approvedAt: Instant? = if (status == ScenarioStatus.DRAFT) null else t0.plusSeconds(n.toLong()),
    ) = ScenarioVersion(
        id = ScenarioVersionId("scn_${name}_$n"),
        name = name,
        version = n,
        yaml = "campaign: {name: $name} # v$n\n",
        status = status,
        source = ScenarioSource.USER,
        parentId = null,
        note = "",
        createdAt = t0,
        approvedAt = approvedAt,
        frozenAt = if (status == ScenarioStatus.FROZEN) t0.plusSeconds(100) else null,
        supersededBy = if (status == ScenarioStatus.SUPERSEDED) ScenarioVersionId("scn_other") else null,
    )

    @Test
    fun `approving a draft makes it approved at the given time`() {
        val draft = version(1, ScenarioStatus.DRAFT)

        val updates = ScenarioLifecycle.approve(draft, listOf(draft), now)

        updates shouldBe listOf(ScenarioVersionUpdate(draft, draft.copy(status = ScenarioStatus.APPROVED, approvedAt = now)))
    }

    @Test
    fun `approving supersedes the previously approved version of the same name first`() {
        val v1 = version(1, ScenarioStatus.APPROVED)
        val v2 = version(2, ScenarioStatus.DRAFT)

        val updates = ScenarioLifecycle.approve(v2, listOf(v1, v2), now)

        updates.map { it.after.status } shouldBe listOf(ScenarioStatus.SUPERSEDED, ScenarioStatus.APPROVED)
        updates.first().after shouldBe v1.copy(status = ScenarioStatus.SUPERSEDED, supersededBy = v2.id)
    }

    @Test
    fun `approving never touches frozen baselines, drafts or other scenarios`() {
        val frozen = version(1, ScenarioStatus.FROZEN)
        val otherDraft = version(2, ScenarioStatus.DRAFT)
        val otherName = version(1, ScenarioStatus.APPROVED, name = "other")
        val target = version(3, ScenarioStatus.DRAFT)

        val updates = ScenarioLifecycle.approve(target, listOf(frozen, otherDraft, otherName, target), now)

        updates.map { it.id } shouldBe listOf(target.id)
    }

    @Test
    fun `approving an approved or frozen version changes nothing`() {
        ScenarioLifecycle.approve(version(1, ScenarioStatus.APPROVED), emptyList(), now).shouldBeEmpty()
        ScenarioLifecycle.approve(version(1, ScenarioStatus.FROZEN), emptyList(), now).shouldBeEmpty()
    }

    @Test
    fun `a superseded version cannot be approved again`() {
        val error =
            shouldThrow<ScenarioTransitionException> { ScenarioLifecycle.approve(version(1, ScenarioStatus.SUPERSEDED), emptyList(), now) }

        error.message shouldContain "new draft"
    }

    @Test
    fun `freezing an approved version records when`() {
        val approved = version(1, ScenarioStatus.APPROVED)

        ScenarioLifecycle.freeze(approved, now) shouldBe
            listOf(ScenarioVersionUpdate(approved, approved.copy(status = ScenarioStatus.FROZEN, frozenAt = now)))
    }

    @Test
    fun `freezing is idempotent and only approved versions can be frozen`() {
        ScenarioLifecycle.freeze(version(1, ScenarioStatus.FROZEN), now).shouldBeEmpty()
        shouldThrow<ScenarioTransitionException> { ScenarioLifecycle.freeze(version(1, ScenarioStatus.DRAFT), now) }.message shouldContain
            "approve it first"
        shouldThrow<ScenarioTransitionException> { ScenarioLifecycle.freeze(version(1, ScenarioStatus.SUPERSEDED), now) }
    }

    @Test
    fun `only approved and frozen versions are runnable`() {
        ScenarioStatus.entries.filter { it.runnable } shouldBe listOf(ScenarioStatus.APPROVED, ScenarioStatus.FROZEN)
    }

    @Test
    fun `the current version is the runnable one approved last`() {
        val frozenV1 = version(1, ScenarioStatus.FROZEN)
        val approvedV3 = version(3, ScenarioStatus.APPROVED)
        val draftV4 = version(4, ScenarioStatus.DRAFT)
        val supersededV2 = version(2, ScenarioStatus.SUPERSEDED)

        ScenarioLifecycle.current(listOf(frozenV1, supersededV2, approvedV3, draftV4)) shouldBe approvedV3
        ScenarioLifecycle.current(listOf(frozenV1, supersededV2)) shouldBe frozenV1
        ScenarioLifecycle.current(listOf(draftV4)) shouldBe null
    }

    @Test
    fun `an update can change only the review fields and never a frozen version`() {
        val approved = version(1, ScenarioStatus.APPROVED)
        shouldThrow<IllegalArgumentException> {
            ScenarioVersionUpdate(approved, approved.copy(status = ScenarioStatus.FROZEN, frozenAt = now, yaml = "changed"))
        }
        val frozen = version(1, ScenarioStatus.FROZEN)
        shouldThrow<IllegalArgumentException> {
            ScenarioVersionUpdate(frozen, frozen.copy(status = ScenarioStatus.SUPERSEDED, supersededBy = ScenarioVersionId("x")))
        }
        shouldThrow<IllegalArgumentException> { ScenarioVersionUpdate(approved, approved) }
    }

    @Test
    fun `review fields must match the status`() {
        shouldThrow<IllegalArgumentException> { version(1, ScenarioStatus.DRAFT).copy(approvedAt = now) }
        shouldThrow<IllegalArgumentException> { version(1, ScenarioStatus.APPROVED).copy(frozenAt = now) }
        shouldThrow<IllegalArgumentException> { version(1, ScenarioStatus.APPROVED).copy(supersededBy = ScenarioVersionId("x")) }
        shouldThrow<IllegalArgumentException> { version(1, ScenarioStatus.DRAFT).copy(version = 0) }
    }

    @Test
    fun `the hash is the SHA-256 of the UTF-8 text and the label names the version`() {
        val v = version(2, ScenarioStatus.DRAFT).copy(yaml = "ə")

        v.sha256 shouldBe "9d460d2c8f235cedd3cb4f731a3fa7149618542a09d7ccc92460850061983aba"
        v.label shouldBe "mini v2"
        v.fileName shouldBe "mini.yaml"
    }
}

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

package az.petek.scenarios.infrastructure

import az.petek.scenarios.domain.ConcurrentScenarioChangeException
import az.petek.scenarios.domain.FrozenScenarioException
import az.petek.scenarios.domain.NewScenarioVersion
import az.petek.scenarios.domain.ScenarioHash
import az.petek.scenarios.domain.ScenarioLifecycle
import az.petek.scenarios.domain.ScenarioNotFoundException
import az.petek.scenarios.domain.ScenarioRepository
import az.petek.scenarios.domain.ScenarioSource
import az.petek.scenarios.domain.ScenarioStatus
import az.petek.scenarios.domain.ScenarioVersion
import az.petek.scenarios.domain.ScenarioVersionId
import az.petek.scenarios.domain.ScenarioVersionUpdate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.time.Instant

/** Rules every [ScenarioRepository] implementation must follow; run against SQLite and the in-memory fake. */
abstract class ScenarioRepositoryContract {
    protected abstract fun repository(): ScenarioRepository

    private val t0 = Instant.parse("2026-01-01T10:00:00.123456789Z")

    private fun draft(
        id: String,
        name: String = "mini",
        yaml: String = "campaign: {name: $name} # $id\n",
        parent: String? = null,
        source: ScenarioSource = ScenarioSource.USER,
        at: Instant = t0,
    ) = NewScenarioVersion(ScenarioVersionId(id), name, yaml, source, parent?.let(::ScenarioVersionId), "note of $id", at)

    private fun test(block: suspend (ScenarioRepository) -> Unit) = runBlocking { block(repository()) }

    @Test
    fun `added versions are numbered per name from one and stored as drafts`() =
        test { repo ->
            val v1 = repo.add(draft("scn_1"))
            val other = repo.add(draft("scn_2", name = "other"))
            val v2 = repo.add(draft("scn_3", parent = "scn_1", source = ScenarioSource.TRIAGE))

            v1.version shouldBe 1
            other.version shouldBe 1
            v2.version shouldBe 2
            v2.status shouldBe ScenarioStatus.DRAFT
            v2.parentId shouldBe ScenarioVersionId("scn_1")
            v2.source shouldBe ScenarioSource.TRIAGE
        }

    @Test
    fun `a stored version reads back exactly, with nanoseconds and non-ASCII text`() =
        test { repo ->
            val yaml = "campaign:\n  name: mini\n  departments: [Satış, Maliyyə, Əməliyyat]\r\n# ✓\n"
            val stored = repo.add(draft("scn_1", yaml = yaml))

            repo.find(ScenarioVersionId("scn_1")) shouldBe stored
            stored.yaml shouldBe yaml
            stored.createdAt shouldBe t0
            stored.note shouldBe "note of scn_1"
        }

    @Test
    fun `an unknown id is not found and an existing id cannot be added again`() =
        test { repo ->
            repo.find(ScenarioVersionId("scn_missing")) shouldBe null
            repo.add(draft("scn_1"))

            shouldThrow<IllegalArgumentException> { repo.add(draft("scn_1", yaml = "other")) }
            repo.history("mini").size shouldBe 1
        }

    @Test
    fun `history is oldest first, the catalog is ordered by name then version`() =
        test { repo ->
            repo.add(draft("scn_1", name = "zeta"))
            repo.add(draft("scn_2", name = "alpha"))
            repo.add(draft("scn_3", name = "zeta"))

            repo.history("zeta").map { it.id.value } shouldBe listOf("scn_1", "scn_3")
            repo.all().map { it.name to it.version } shouldBe listOf("alpha" to 1, "zeta" to 1, "zeta" to 2)
            repo.history("nobody").shouldBeEmpty()
        }

    @Test
    fun `versions are found by the SHA-256 of their text`() =
        test { repo ->
            repo.add(draft("scn_1", yaml = "a\n", at = t0))
            repo.add(draft("scn_2", yaml = "b\n"))
            repo.add(draft("scn_3", yaml = "a\n", at = t0.plusSeconds(5)))

            repo.withHash(ScenarioHash.of("a\n")).map { it.id.value } shouldBe listOf("scn_1", "scn_3")
            repo.withHash(ScenarioHash.of("c\n")).shouldBeEmpty()
        }

    @Test
    fun `an approval with its supersession is applied as one change`() =
        test { repo ->
            val v1 = repo.add(draft("scn_1"))
            repo.update(ScenarioLifecycle.approve(v1, listOf(v1), t0))
            val v2 = repo.add(draft("scn_2"))

            repo.update(ScenarioLifecycle.approve(v2, repo.history("mini"), t0.plusSeconds(1)))

            val history = repo.history("mini")
            history.map { it.status } shouldBe listOf(ScenarioStatus.SUPERSEDED, ScenarioStatus.APPROVED)
            history[0].supersededBy shouldBe v2.id
            history[0].approvedAt shouldBe t0
            history[1].approvedAt shouldBe t0.plusSeconds(1)
        }

    @Test
    fun `a stale update changes nothing of its batch`() =
        test { repo ->
            val v1 = repo.add(draft("scn_1"))
            val other = repo.add(draft("scn_2", name = "other"))
            repo.update(ScenarioLifecycle.approve(v1, listOf(v1), t0))
            // Computed from a snapshot in which v1 was still a draft: its update is stale, the first one is not.
            val stale =
                listOf(
                    ScenarioVersionUpdate(other, other.copy(status = ScenarioStatus.APPROVED, approvedAt = t0)),
                    ScenarioVersionUpdate(v1, v1.copy(status = ScenarioStatus.APPROVED, approvedAt = t0.plusSeconds(9))),
                )

            shouldThrow<ConcurrentScenarioChangeException> { repo.update(stale) }

            repo.find(other.id)?.status shouldBe ScenarioStatus.DRAFT
            repo.find(v1.id)?.approvedAt shouldBe t0
        }

    @Test
    fun `an approval computed before another approval was applied is refused as concurrent`() =
        test { repo ->
            val v1 = repo.add(draft("scn_1"))
            val v2 = repo.add(draft("scn_2"))
            val snapshot = repo.history("mini")
            val first = ScenarioLifecycle.approve(v1, snapshot, t0)
            val second = ScenarioLifecycle.approve(v2, snapshot, t0.plusSeconds(1))

            repo.update(first)
            shouldThrow<ConcurrentScenarioChangeException> { repo.update(second) }

            repo.history("mini").map { it.status } shouldBe listOf(ScenarioStatus.APPROVED, ScenarioStatus.DRAFT)
            repo.update(ScenarioLifecycle.approve(v2, repo.history("mini"), t0.plusSeconds(2)))
            repo.history("mini").map { it.status } shouldBe listOf(ScenarioStatus.SUPERSEDED, ScenarioStatus.APPROVED)
        }

    @Test
    fun `a frozen version never changes again`() =
        test { repo ->
            val v1 = repo.add(draft("scn_1"))
            repo.update(ScenarioLifecycle.approve(v1, listOf(v1), t0))
            val approved = repo.find(v1.id)!!
            repo.update(ScenarioLifecycle.freeze(approved, t0.plusSeconds(1)))
            val frozen = repo.find(v1.id)!!

            // Pretend a caller still believes it is APPROVED and tries to supersede it.
            val attempt =
                ScenarioVersionUpdate(approved, approved.copy(status = ScenarioStatus.SUPERSEDED, supersededBy = ScenarioVersionId("x")))
            shouldThrow<FrozenScenarioException> { repo.update(listOf(attempt)) }

            repo.find(v1.id) shouldBe frozen
            frozen.status shouldBe ScenarioStatus.FROZEN
            frozen.frozenAt shouldBe t0.plusSeconds(1)
        }

    @Test
    fun `updating an unknown version fails and an empty update is a no-op`() =
        test { repo ->
            val ghost = draft("scn_ghost").toVersion(1)

            shouldThrow<ScenarioNotFoundException> {
                repo.update(listOf(ScenarioVersionUpdate(ghost, ghost.copy(status = ScenarioStatus.APPROVED, approvedAt = t0))))
            }
            repo.update(emptyList())
        }

    @Test
    fun `concurrent adds of one name get consecutive distinct version numbers`() =
        test { repo ->
            val versions =
                withContext(Dispatchers.Default) {
                    (1..40).map { i -> async { repo.add(draft("scn_$i", yaml = "v$i\n")) } }.awaitAll()
                }

            versions.map { it.version }.sorted() shouldBe (1..40).toList()
            repo.history("mini").map(ScenarioVersion::version) shouldBe (1..40).toList()
        }
}

class InMemoryScenarioRepositoryTest : ScenarioRepositoryContract() {
    override fun repository(): ScenarioRepository =
        az.petek.scenarios.testing
            .InMemoryScenarioRepository()
}

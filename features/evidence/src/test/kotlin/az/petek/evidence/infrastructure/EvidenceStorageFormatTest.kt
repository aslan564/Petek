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

package az.petek.evidence.infrastructure

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.CorrelationId
import az.petek.core.ids.StepId
import az.petek.core.sqlite.SqliteDatabase
import az.petek.evidence.infrastructure.EvidenceFixtures.assertion
import az.petek.evidence.infrastructure.EvidenceFixtures.run
import az.petek.evidence.infrastructure.EvidenceFixtures.step
import az.petek.evidence.infrastructure.EvidenceFixtures.withStore
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random

class EvidenceStorageFormatTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the store creates every evidence table`() =
        withStore(dir) { _, db ->
            val tables = db.column("SELECT name FROM sqlite_master WHERE type = 'table'")

            tables shouldContainAll
                listOf("run", "run_resource", "step", "event", "receipt", "artifact", "assertion", "finding", "usage")
        }

    @Test
    fun `no column is a bounded VARCHAR, so no value can be refused for its length`() =
        withStore(dir) { _, db ->
            val schema = db.column("SELECT sql FROM sqlite_master WHERE type = 'table' AND name <> 'sqlite_sequence'")

            schema.filter { it.contains("VARCHAR", ignoreCase = true) } shouldBe emptyList()
        }

    @Test
    fun `long ids, labels and resource keys are stored in full`() =
        withStore(dir) { store, _ ->
            val long = "x".repeat(1_000)
            val run = run().copy(campaignHash = "sha256:$long", repeatGroup = "grp_$long", repeatIndex = 1)
            val resource = EvidenceFixtures.resource("ext_$long").copy(kind = "kind_$long")
            val step =
                step("stp_$long").copy(scenarioStep = "announce_$long", correlationId = CorrelationId("cor_$long"))
            val event = EvidenceFixtures.event("evt_$long").copy(name = "created_$long", objectIdSource = "dom_$long")
            val receipt = EvidenceFixtures.receipt("evt_$long", EvidenceFixtures.A07, t1 = EvidenceFixtures.at(1))
            val artifact = EvidenceFixtures.artifact("art_$long").copy(stepId = StepId("stp_$long"), sha256 = "sha_$long")
            val assertion = assertion("type_$long", artifactIds = listOf(ArtifactId("art_$long")))
            val finding = EvidenceFixtures.finding("fnd_$long").copy(scenarioStep = "announce_$long")

            store.create(run)
            store.addResource(resource)
            store.step(step)
            store.event(event)
            store.receipt(receipt)
            store.artifact(artifact)
            store.assertion(assertion)
            store.finding(finding)

            store.find(EvidenceFixtures.RUN) shouldBe run
            store.byRepeatGroup("grp_$long") shouldContainExactly listOf(run)
            store.resources(EvidenceFixtures.RUN) shouldContainExactly listOf(resource)
            store.steps(EvidenceFixtures.RUN) shouldContainExactly listOf(step)
            store.events(EvidenceFixtures.RUN) shouldContainExactly listOf(event)
            store.receipts(EvidenceFixtures.RUN) shouldContainExactly listOf(receipt)
            store.artifacts(EvidenceFixtures.RUN) shouldContainExactly listOf(artifact)
            store.assertions(EvidenceFixtures.RUN) shouldContainExactly listOf(assertion)
            store.findings(EvidenceFixtures.RUN) shouldContainExactly listOf(finding)
        }

    @Test
    fun `instants beyond year 9999 are still stored and read back unchanged`() =
        withStore(dir) { store, _ ->
            val farFuture = step("stp_1", startedAt = Instant.parse("+10000-01-01T00:00:00.000000001Z"))

            store.step(farFuture)

            store.steps(EvidenceFixtures.RUN) shouldContainExactly listOf(farFuture)
        }

    @Test
    fun `building the store twice on one database is harmless`() =
        withStore(dir) { store, db ->
            store.create(run())

            SqliteEvidenceStore(db).find(EvidenceFixtures.RUN) shouldBe run()
        }

    @Test
    fun `instants are stored as fixed-width ISO-8601 text and enums by name`() =
        withStore(dir) { store, db ->
            store.step(step("stp_1"))

            db.column("SELECT started_at FROM step") shouldContainExactly listOf("2026-01-01T10:00:00.123456789Z")
            db.column("SELECT kind FROM step") shouldContainExactly listOf("DO")
            db.column("SELECT status FROM step") shouldContainExactly listOf("PASSED")
        }

    @Test
    fun `artifact ids are stored as a JSON array of strings`() =
        withStore(dir) { store, db ->
            store.assertion(assertion("count", artifactIds = listOf(ArtifactId("art_1"), ArtifactId("art \"2\""))))
            store.assertion(assertion("count", artifactIds = emptyList()))

            db.column("SELECT artifact_ids FROM assertion ORDER BY seq") shouldContainExactly
                listOf("""["art_1","art \"2\""]""", "[]")
        }

    @Test
    fun `instant text always has the same width and sorts like the instants`() {
        val random = Random(2026)
        val instants =
            List(500) { Instant.ofEpochSecond(random.nextLong(0, 32_503_680_000), random.nextLong(0, 1_000_000_000)) } +
                listOf(Instant.EPOCH, Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("9999-12-31T23:59:59.999999999Z"))

        val encoded = instants.map(InstantText::encode)

        encoded.map { it.length }.toSet() shouldBe setOf(InstantText.LENGTH)
        instants.sorted().map(InstantText::encode) shouldBe encoded.sorted()
        encoded.map(InstantText::decode) shouldBe instants
    }

    @Test
    fun `whole seconds are written with nine zero fraction digits`() {
        InstantText.encode(Instant.parse("2026-01-01T10:00:00Z")) shouldBe "2026-01-01T10:00:00.000000000Z"
        InstantText.decode("2026-01-01T10:00:00.000000000Z") shouldBe Instant.parse("2026-01-01T10:00:00Z")
    }

    @Test
    fun `artifact id json round-trips empty lists and awkward characters`() {
        val ids = listOf(ArtifactId("a\\b"), ArtifactId("[x]"), ArtifactId("ə ü ş"), ArtifactId(""))

        ArtifactIdsJson.decode(ArtifactIdsJson.encode(ids)) shouldBe ids
        ArtifactIdsJson.decode(ArtifactIdsJson.encode(emptyList())) shouldBe emptyList()
    }

    private suspend fun SqliteDatabase.column(sql: String): List<String> =
        read {
            exec(sql) { rs ->
                buildList {
                    while (rs.next()) add(rs.getString(1))
                }
            } ?: emptyList()
        }
}

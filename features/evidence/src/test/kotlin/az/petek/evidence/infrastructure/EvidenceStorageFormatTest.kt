package az.petek.evidence.infrastructure

import az.petek.core.ids.ArtifactId
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

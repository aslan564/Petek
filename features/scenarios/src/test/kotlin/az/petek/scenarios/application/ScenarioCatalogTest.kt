package az.petek.scenarios.application

import az.petek.core.sqlite.SqliteDatabase
import az.petek.core.testing.FakeHarnessClock
import az.petek.scenarios.domain.DiffLineType
import az.petek.scenarios.domain.ScenarioHash
import az.petek.scenarios.domain.ScenarioInvalidException
import az.petek.scenarios.domain.ScenarioLifecycle
import az.petek.scenarios.domain.ScenarioNotFoundException
import az.petek.scenarios.domain.ScenarioNotRunnableException
import az.petek.scenarios.domain.ScenarioRepository
import az.petek.scenarios.domain.ScenarioSource
import az.petek.scenarios.domain.ScenarioStatus
import az.petek.scenarios.domain.ScenarioTransitionException
import az.petek.scenarios.domain.ScenarioValidator
import az.petek.scenarios.domain.ScenarioVersionId
import az.petek.scenarios.domain.ScenarioVersionUpdate
import az.petek.scenarios.infrastructure.FileSystemScenarioFiles
import az.petek.scenarios.infrastructure.SqliteScenarioRepository
import az.petek.scenarios.testing.InMemoryScenarioRepository
import az.petek.scenarios.testing.ScenarioTestKit
import az.petek.scenarios.testing.ScenarioTestKit.MINI_YAML
import az.petek.scenarios.testing.SequentialScenarioIds
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

class ScenarioCatalogTest {
    @TempDir
    lateinit var dir: Path

    private val clock = FakeHarnessClock()
    private val ids = SequentialScenarioIds()
    private val repository = InMemoryScenarioRepository()

    private fun catalog(
        validator: ScenarioValidator = ScenarioTestKit.validator(dir.resolve("work")),
        repo: ScenarioRepository = repository,
    ) = ScenarioCatalog(repo, validator, FileSystemScenarioFiles(), clock, ids)

    private fun file(
        name: String,
        text: String,
    ): Path = dir.resolve(name).also { Files.writeString(it, text) }

    private val v2Yaml = MINI_YAML.replace("do: \"Bildirişləri aç və yeni elanı oxu\"", "do: \"Zəng işarəsini aç və yeni elanı oxu\"")

    @Test
    fun `an imported file becomes a user draft named by its campaign`() =
        runTest {
            val result = catalog().importFile(file("any-file-name.yaml", MINI_YAML))

            result.created shouldBe true
            val version = result.version
            version.id shouldBe ScenarioVersionId("scn_1")
            version.name shouldBe "mini"
            version.version shouldBe 1
            version.status shouldBe ScenarioStatus.DRAFT
            version.source shouldBe ScenarioSource.USER
            version.parentId shouldBe null
            version.note shouldBe "imported from any-file-name.yaml"
            version.createdAt shouldBe clock.now().wall
            version.yaml shouldBe MINI_YAML
        }

    @Test
    fun `importing the same text again returns the stored version`() =
        runTest {
            val first = catalog().importFile(file("mini.yaml", MINI_YAML)).version

            val again = catalog().importFile(file("copy.yaml", MINI_YAML), note = "again")

            again shouldBe ImportResult(first, created = false)
            repository.history("mini").size shouldBe 1
        }

    @Test
    fun `importing a changed file makes the next version, derived from the newest one`() =
        runTest {
            val v1 = catalog().importFile(file("mini.yaml", MINI_YAML)).version

            val v2 = catalog().importFile(file("mini.yaml", v2Yaml), note = "owner's fix").version

            v2.version shouldBe 2
            v2.parentId shouldBe v1.id
            v2.note shouldBe "owner's fix"
            val diff = catalog().diffFromParent(v2.id).shouldNotBeNull()
            diff.oldLabel shouldBe "mini v1"
            diff.newLabel shouldBe "mini v2"
            diff.hunks
                .single()
                .lines
                .filter { it.type != DiffLineType.CONTEXT }
                .map { it.type to it.text.trim() } shouldBe
                listOf(
                    DiffLineType.REMOVED to "do: \"Bildirişləri aç və yeni elanı oxu\"",
                    DiffLineType.ADDED to "do: \"Zəng işarəsini aç və yeni elanı oxu\"",
                )
        }

    @Test
    fun `an invalid file is refused with its issues and nothing is stored`() =
        runTest {
            val broken = MINI_YAML.replace("wait_for: announcement_created", "wait_for: nothing_emits_this")

            val error = shouldThrow<ScenarioInvalidException> { catalog().importFile(file("mini.yaml", broken)) }

            error.issues.single().message shouldContain "nothing_emits_this"
            error.issues.single().line shouldBe broken.lines().indexOfFirst { it.contains("nothing_emits_this") } + 1
            repository.all().shouldBeEmpty()
        }

    @Test
    fun `a draft from the explorer is stored with its source and note`() =
        runTest {
            val draft = catalog().createDraft(MINI_YAML, ScenarioSource.EXPLORER, note = "  from the site model  ")

            draft.source shouldBe ScenarioSource.EXPLORER
            draft.note shouldBe "from the site model"
            draft.status shouldBe ScenarioStatus.DRAFT
        }

    @Test
    fun `a text without a campaign name is named after the given file name, or after the parent`() =
        runTest {
            val unnamed = MINI_YAML.replace("  name: mini\n", "")

            val v1 = catalog().createDraft(unnamed, ScenarioSource.USER, fileName = "kadrohr-core.yaml")
            val v2 = catalog().createDraft(unnamed.replace("seed: 7", "seed: 8"), ScenarioSource.USER, parentId = v1.id)

            v1.name shouldBe "kadrohr-core"
            v2.name shouldBe "kadrohr-core"
            v2.version shouldBe 2
        }

    @Test
    fun `a new version of a scenario must keep its name and its parent must exist`() =
        runTest {
            val v1 = catalog().createDraft(MINI_YAML, ScenarioSource.USER)

            val renamed =
                shouldThrow<ScenarioInvalidException> {
                    catalog().createDraft(
                        MINI_YAML.replace("name: mini", "name: maxi"),
                        ScenarioSource.USER,
                        v1.id,
                    )
                }
            renamed.issues.single().message shouldContain "must keep its name"
            renamed.issues.single().line shouldBe 3
            shouldThrow<ScenarioNotFoundException> { catalog().createDraft(MINI_YAML, ScenarioSource.USER, ScenarioVersionId("scn_404")) }
        }

    @Test
    fun `approving supersedes the previously approved version`() =
        runTest {
            val v1 = catalog().createDraft(MINI_YAML, ScenarioSource.USER)
            catalog().approve(v1.id)
            clock.advance(5.minutes)
            val v2 = catalog().createDraft(v2Yaml, ScenarioSource.USER, v1.id)

            val approved = catalog().approve(v2.id)

            approved.status shouldBe ScenarioStatus.APPROVED
            approved.approvedAt shouldBe clock.now().wall
            val old = catalog().get(v1.id)
            old.status shouldBe ScenarioStatus.SUPERSEDED
            old.supersededBy shouldBe v2.id
            catalog().current("mini") shouldBe approved
        }

    @Test
    fun `approving validates again and refuses a draft the harness no longer accepts`() =
        runTest {
            val draft = catalog().createDraft(MINI_YAML, ScenarioSource.USER)
            val stricter = ScenarioTestKit.validator(dir.resolve("work"), runFunctions = setOf("login"))

            val error = shouldThrow<ScenarioInvalidException> { catalog(stricter).approve(draft.id) }

            error.issues.single().message shouldContain "register_and_login"
            catalog().get(draft.id).status shouldBe ScenarioStatus.DRAFT
        }

    @Test
    fun `approving and freezing are idempotent`() =
        runTest {
            val v1 = catalog().createDraft(MINI_YAML, ScenarioSource.USER)
            val approved = catalog().approve(v1.id)
            clock.advance(1.minutes)

            catalog().approve(v1.id) shouldBe approved
            val frozen = catalog().freeze(v1.id)
            clock.advance(1.minutes)

            catalog().freeze(v1.id) shouldBe frozen
            catalog().approve(v1.id) shouldBe frozen
            repository.appliedUpdates.map { it.after.status } shouldBe listOf(ScenarioStatus.APPROVED, ScenarioStatus.FROZEN)
        }

    @Test
    fun `a frozen baseline stays frozen when a newer version is approved`() =
        runTest {
            val v1 = catalog().createDraft(MINI_YAML, ScenarioSource.USER)
            catalog().approve(v1.id)
            val frozen = catalog().freeze(v1.id)
            clock.advance(1.minutes)
            val v2 = catalog().createDraft(v2Yaml, ScenarioSource.TRIAGE, v1.id)

            catalog().approve(v2.id)

            catalog().get(v1.id) shouldBe frozen
            catalog().current("mini")?.id shouldBe v2.id
            catalog().list(setOf(ScenarioStatus.APPROVED, ScenarioStatus.FROZEN)).map { it.id } shouldBe listOf(v1.id, v2.id)
        }

    @Test
    fun `only approved versions can be frozen and superseded ones are not approved again`() =
        runTest {
            val v1 = catalog().createDraft(MINI_YAML, ScenarioSource.USER)
            shouldThrow<ScenarioTransitionException> { catalog().freeze(v1.id) }
            catalog().approve(v1.id)
            val v2 = catalog().createDraft(v2Yaml, ScenarioSource.USER, v1.id)
            catalog().approve(v2.id)

            shouldThrow<ScenarioTransitionException> { catalog().approve(v1.id) }
            shouldThrow<ScenarioTransitionException> { catalog().freeze(v1.id) }
            shouldThrow<ScenarioNotFoundException> { catalog().approve(ScenarioVersionId("scn_404")) }
        }

    @Test
    fun `drafts run only when explicitly allowed`() =
        runTest {
            val draft = catalog().createDraft(MINI_YAML, ScenarioSource.EXPLORER)

            shouldThrow<ScenarioNotRunnableException> { catalog().runnable(draft.id) }.message shouldContain "DRAFT"
            catalog().runnable(draft.id, allowUnreviewed = true) shouldBe draft
            catalog().approve(draft.id)
            catalog().runnable(draft.id).status shouldBe ScenarioStatus.APPROVED
            catalog().current("nobody") shouldBe null
        }

    @Test
    fun `the catalog lists by status and gives the history of a name`() =
        runTest {
            val v1 = catalog().createDraft(MINI_YAML, ScenarioSource.USER)
            val v2 = catalog().createDraft(v2Yaml, ScenarioSource.USER, v1.id)
            catalog().approve(v1.id)

            catalog().list(setOf(ScenarioStatus.DRAFT)).map { it.id } shouldBe listOf(v2.id)
            catalog().list().map { it.id } shouldBe listOf(v1.id, v2.id)
            catalog().history("mini").map { it.version } shouldBe listOf(1, 2)
            catalog().find(ScenarioVersionId("scn_404")) shouldBe null
        }

    @Test
    fun `any two versions can be diffed and a first version has no parent diff`() =
        runTest {
            val v1 = catalog().createDraft(MINI_YAML, ScenarioSource.USER)
            val v2 = catalog().createDraft(v2Yaml, ScenarioSource.USER, v1.id)

            catalog().diff(v2.id, v1.id).unified() shouldContain "+    do: \"Bildirişləri aç və yeni elanı oxu\""
            catalog().diff(v1.id, v1.id).identical shouldBe true
            catalog().diffFromParent(v1.id) shouldBe null
        }

    @Test
    fun `export writes the exact text so the exported file hashes like its version`() =
        runTest {
            val v1 = catalog().createDraft(MINI_YAML, ScenarioSource.USER)
            val target = dir.resolve("out/mini.yaml")

            catalog().export(v1.id, target)

            ScenarioHash.of(Files.readString(target)) shouldBe v1.sha256
            shouldThrow<az.petek.scenarios.domain.ScenarioFileException> { catalog().export(v1.id, target) }
            val v2 = catalog().createDraft(v2Yaml, ScenarioSource.USER, v1.id)
            catalog().export(v2.id, target, overwrite = true)
            Files.readString(target) shouldBe v2Yaml
        }

    @Test
    fun `concurrent approvals leave exactly one approved version`() =
        runBlocking<Unit> {
            SqliteDatabase.open(dir.resolve("petek.db")).use { db ->
                val sqlite = SqliteScenarioRepository(db)
                val shared = catalog(repo = sqlite)
                val drafts = (1..6).map { shared.createDraft(MINI_YAML.replace("seed: 7", "seed: $it"), ScenarioSource.USER) }

                withContext(Dispatchers.Default) { drafts.map { async { catalog(repo = sqlite).approve(it.id) } }.awaitAll() }

                val statuses = sqlite.history("mini").map { it.status }
                statuses.count { it == ScenarioStatus.APPROVED } shouldBe 1
                statuses.count { it == ScenarioStatus.SUPERSEDED } shouldBe 5
                val draftIds = drafts.map { it.id }.toSet()
                sqlite.history("mini").mapNotNull { it.supersededBy }.all { it in draftIds } shouldBe true
                drafts.map { it.id } shouldContainExactlyInAnyOrder sqlite.history("mini").map { it.id }
            }
        }

    @Test
    fun `an approval that races with freezing the version it supersedes still succeeds`() =
        runTest {
            val v1 = catalog().createDraft(MINI_YAML, ScenarioSource.USER)
            catalog().approve(v1.id)
            val v2 = catalog().createDraft(v2Yaml, ScenarioSource.USER, v1.id)
            val racing = FreezeBeforeFirstUpdate(repository, v1.id, clock.now().wall)

            val approved = catalog(repo = racing).approve(v2.id)

            approved.status shouldBe ScenarioStatus.APPROVED
            repository.find(v1.id)?.status shouldBe ScenarioStatus.FROZEN
            repository.history("mini").count { it.status == ScenarioStatus.APPROVED } shouldBe 1
            catalog().current("mini")?.id shouldBe v2.id
        }

    /** Freezes [victim], as a concurrent reviewer would, right before the first batch of updates is applied. */
    private class FreezeBeforeFirstUpdate(
        private val delegate: ScenarioRepository,
        private val victim: ScenarioVersionId,
        private val at: Instant,
    ) : ScenarioRepository by delegate {
        private var fired = false

        override suspend fun update(updates: List<ScenarioVersionUpdate>) {
            if (!fired) {
                fired = true
                delegate.update(ScenarioLifecycle.freeze(delegate.find(victim).shouldNotBeNull(), at))
            }
            delegate.update(updates)
        }
    }
}

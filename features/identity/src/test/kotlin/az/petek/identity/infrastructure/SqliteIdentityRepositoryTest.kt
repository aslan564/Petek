package az.petek.identity.infrastructure

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.core.sqlite.SqliteDatabase
import az.petek.identity.IdentityTestData.OTHER_RUN_TAG
import az.petek.identity.IdentityTestData.RUN_TAG
import az.petek.identity.IdentityTestData.generator
import az.petek.identity.IdentityTestData.spec
import az.petek.identity.domain.IdentityConflictException
import az.petek.identity.domain.IdentityPlan
import az.petek.identity.domain.IdentityStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqliteIdentityRepositoryTest {
    @TempDir
    lateinit var dir: Path

    private val runId = RunId("0199a1b2-0000-7000-8000-000000000001")
    private val otherRunId = RunId("0199a1b2-0000-7000-8000-000000000002")
    private val plan = generator().generate(spec(), RUN_TAG)
    private val opened = mutableListOf<SqliteDatabase>()

    private fun open(): SqliteDatabase = SqliteDatabase.open(dir.resolve("evidence/petek.db")).also { opened += it }

    private fun repository(db: SqliteDatabase = open()) = SqliteIdentityRepository(db)

    @AfterEach
    fun closeDatabases() {
        opened.forEach { it.close() }
    }

    private fun storedReason(
        db: SqliteDatabase,
        agentId: String,
    ): String? =
        runBlocking {
            db.read {
                IdentityTable
                    .selectAll()
                    .where { (IdentityTable.runId eq runId.value) and (IdentityTable.agentId eq agentId) }
                    .single()[IdentityTable.statusReason]
            }
        }

    @Test
    fun `stored identities come back with every field intact`() =
        runBlocking<Unit> {
            val repository = repository()

            repository.replaceAll(runId, plan)

            val stored = repository.findByRun(runId)
            stored shouldBe plan.identities
            val admin = stored.first()
            admin.department shouldBe null
            admin.password.reveal() shouldBe
                plan.identities
                    .first()
                    .password
                    .reveal()
        }

    @Test
    fun `identities survive reopening the database file`() {
        runBlocking { repository().replaceAll(runId, plan) }
        closeDatabases()
        opened.clear()

        runBlocking { repository().findByRun(runId) } shouldBe plan.identities
    }

    @Test
    fun `identities are ordered by agent number, also beyond a99`() =
        runBlocking<Unit> {
            val repository = repository()
            val big = generator().generate(spec(testers = 120), RUN_TAG)

            repository.replaceAll(runId, big)

            repository.findByRun(runId).map { it.agentId } shouldBe (1..120).map { AgentId.of(it) }
        }

    @Test
    fun `an unknown run has no identities`() =
        runBlocking<Unit> {
            repository().findByRun(runId).shouldBeEmpty()
        }

    @Test
    fun `replacing with the same plan is idempotent`() =
        runBlocking<Unit> {
            val repository = repository()

            repository.replaceAll(runId, plan)
            repository.replaceAll(runId, plan)

            repository.findByRun(runId) shouldBe plan.identities
        }

    @Test
    fun `replacing drops identities the new plan no longer has and resets their state`() =
        runBlocking<Unit> {
            val repository = repository()
            repository.replaceAll(runId, plan)
            repository.updateStatus(runId, AgentId("a02"), IdentityStatus.FAILED, "mail_timeout")
            repository.updateStorageState(runId, AgentId("a02"), "/tmp/a02.json")
            val smaller = generator().generate(spec(testers = 10), OTHER_RUN_TAG)

            repository.replaceAll(runId, smaller)

            repository.findByRun(runId) shouldBe smaller.identities
        }

    @Test
    fun `replacing with an empty plan clears the run`() =
        runBlocking<Unit> {
            val repository = repository()
            repository.replaceAll(runId, plan)

            repository.replaceAll(runId, IdentityPlan(RUN_TAG, emptyList()))

            repository.findByRun(runId).shouldBeEmpty()
        }

    @Test
    fun `runs are stored side by side without touching each other`() =
        runBlocking<Unit> {
            val repository = repository()
            val other = generator().generate(spec(), OTHER_RUN_TAG)

            repository.replaceAll(runId, plan)
            repository.replaceAll(otherRunId, other)
            repository.updateStatus(otherRunId, AgentId("a01"), IdentityStatus.ACTIVE)

            repository.findByRun(runId) shouldBe plan.identities
            repository.findByRun(otherRunId).first().status shouldBe IdentityStatus.ACTIVE
        }

    @Test
    fun `an e-mail used by another run is a conflict that names the e-mail`() =
        runBlocking<Unit> {
            val repository = repository()
            repository.replaceAll(runId, plan)

            val error = shouldThrow<IdentityConflictException> { repository.replaceAll(otherRunId, plan) }

            error.message.orEmpty() shouldContain "eli.k7x2.a01@test.kadrohr.com"
            error.message.orEmpty() shouldContain otherRunId.value
            repository.findByRun(otherRunId).shouldBeEmpty()
            repository.findByRun(runId) shouldBe plan.identities
        }

    @Test
    fun `e-mails conflict regardless of letter case`() =
        runBlocking<Unit> {
            val repository = repository()
            repository.replaceAll(runId, plan)
            val shouting =
                IdentityPlan(RUN_TAG, listOf(plan.identities.first().let { it.copy(email = it.email.uppercase()) }))

            val error = shouldThrow<IdentityConflictException> { repository.replaceAll(otherRunId, shouting) }

            error.message.orEmpty() shouldContain "eli.k7x2.a01@test.kadrohr.com"
        }

    @Test
    fun `a failed replacement keeps the run's previous registry`() =
        runBlocking<Unit> {
            val repository = repository()
            val previous = generator().generate(spec(), RunTag("b000"))
            repository.replaceAll(runId, plan)
            repository.replaceAll(otherRunId, previous)

            shouldThrow<IdentityConflictException> { repository.replaceAll(otherRunId, plan) }

            repository.findByRun(otherRunId) shouldBe previous.identities
        }

    @Test
    fun `the conflict message never reveals passwords`() =
        runBlocking<Unit> {
            val repository = repository()
            repository.replaceAll(runId, plan)

            val error = shouldThrow<IdentityConflictException> { repository.replaceAll(otherRunId, plan) }

            plan.identities.forEach { error.message.orEmpty() shouldNotContain it.password.reveal() }
        }

    @Test
    fun `a unique violation raised by the database itself is still a conflict without secrets`() =
        runBlocking<Unit> {
            val db = open()
            val repository = repository(db)
            // Another writer takes a01's e-mail between the pre-check and the insert.
            db.write {
                exec(
                    """
                    CREATE TRIGGER take_email BEFORE INSERT ON identity
                    WHEN NEW.run_id = '${runId.value}' AND NEW.agent_id = 'a01'
                    BEGIN
                        INSERT INTO identity (run_id, agent_id, display_name, email, password, phone, role,
                            department, registration, status)
                        VALUES ('intruder', 'a01', 'Intruder', NEW.email, 'x', '+994500000000', 'admin',
                            NULL, 'owner', 'planned');
                    END
                    """.trimIndent(),
                )
            }

            val error = shouldThrow<IdentityConflictException> { repository.replaceAll(runId, plan) }

            error.message.orEmpty() shouldContain "e-mail already used by another run"
            error.message.orEmpty() shouldContain runId.value
            plan.identities.forEach { error.message.orEmpty() shouldNotContain it.password.reveal() }
            error.cause shouldBe null
            repository.findByRun(runId).shouldBeEmpty()
        }

    @Test
    fun `a plan repeating an e-mail, display name or agent id is rejected before writing`() =
        runBlocking<Unit> {
            val repository = repository()
            val (first, second) = plan.identities
            val sameEmail = IdentityPlan(RUN_TAG, listOf(first, second.copy(email = first.email.uppercase())))
            val sameName = IdentityPlan(RUN_TAG, listOf(first, second.copy(displayName = first.displayName)))
            val sameAgent = IdentityPlan(RUN_TAG, listOf(first, second.copy(agentId = first.agentId)))

            shouldThrow<IdentityConflictException> { repository.replaceAll(runId, sameEmail) }.message.orEmpty() shouldContain
                "duplicate e-mail ${first.email}"
            shouldThrow<IdentityConflictException> { repository.replaceAll(runId, sameName) }.message.orEmpty() shouldContain
                "duplicate display name ${first.displayName}"
            shouldThrow<IdentityConflictException> { repository.replaceAll(runId, sameAgent) }.message.orEmpty() shouldContain
                "duplicate agent id a01"
            repository.findByRun(runId).shouldBeEmpty()
        }

    @Test
    fun `status updates are stored with their reason`() =
        runBlocking<Unit> {
            val db = open()
            val repository = repository(db)
            repository.replaceAll(runId, plan)

            repository.updateStatus(runId, AgentId("a07"), IdentityStatus.FAILED, "otp_rejected")

            repository.findByRun(runId).single { it.agentId == AgentId("a07") }.status shouldBe IdentityStatus.FAILED
            storedReason(db, "a07") shouldBe "otp_rejected"
            repository
                .findByRun(runId)
                .filter { it.agentId != AgentId("a07") }
                .map { it.status }
                .toSet() shouldBe
                setOf(IdentityStatus.PLANNED)
        }

    @Test
    fun `a status without a reason clears the previous reason`() =
        runBlocking<Unit> {
            val db = open()
            val repository = repository(db)
            repository.replaceAll(runId, plan)
            repository.updateStatus(runId, AgentId("a07"), IdentityStatus.FAILED, "mail_timeout")

            repository.updateStatus(runId, AgentId("a07"), IdentityStatus.ACTIVE)

            repository.findByRun(runId).single { it.agentId == AgentId("a07") }.status shouldBe IdentityStatus.ACTIVE
            storedReason(db, "a07") shouldBe null
        }

    @Test
    fun `every status survives the round trip`() =
        runBlocking<Unit> {
            val repository = repository()
            repository.replaceAll(runId, plan)

            IdentityStatus.entries.forEach { status ->
                repository.updateStatus(runId, AgentId("a03"), status)
                repository.findByRun(runId).single { it.agentId == AgentId("a03") }.status shouldBe status
            }
        }

    @Test
    fun `the storage state path is stored per agent`() =
        runBlocking<Unit> {
            val repository = repository()
            repository.replaceAll(runId, plan)

            repository.updateStorageState(runId, AgentId("a05"), "evidence/run/a05/storage-state.json")

            val stored = repository.findByRun(runId)
            stored.single { it.agentId == AgentId("a05") }.storageStatePath shouldBe "evidence/run/a05/storage-state.json"
            stored.count { it.storageStatePath != null } shouldBe 1
        }

    @Test
    fun `updating an agent the run does not have fails loudly`() =
        runBlocking<Unit> {
            val repository = repository()
            repository.replaceAll(runId, plan)

            shouldThrow<NoSuchElementException> {
                repository.updateStatus(runId, AgentId("a99"), IdentityStatus.ACTIVE)
            }.message.orEmpty() shouldContain "a99"
            shouldThrow<NoSuchElementException> {
                repository.updateStorageState(otherRunId, AgentId("a01"), "x.json")
            }.message.orEmpty() shouldContain otherRunId.value
        }

    @Test
    fun `concurrent updates of different agents all land`() =
        runBlocking<Unit> {
            val repository = repository()
            repository.replaceAll(runId, plan)

            plan.identities
                .map { identity ->
                    async {
                        repository.updateStatus(runId, identity.agentId, IdentityStatus.REGISTERED)
                        repository.updateStorageState(runId, identity.agentId, "${identity.agentId}.json")
                    }
                }.awaitAll()

            val stored = repository.findByRun(runId)
            stored.map { it.status }.toSet() shouldBe setOf(IdentityStatus.REGISTERED)
            stored.forEach { it.storageStatePath shouldBe "${it.agentId}.json" }
        }

    @Test
    fun `several repositories on one database share the table`() =
        runBlocking<Unit> {
            val db = open()
            val first = repository(db)
            val second = repository(db)

            first.replaceAll(runId, plan)

            second.findByRun(runId) shouldHaveSize 30
        }
}

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

package az.petek.identity.infrastructure

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.ids.RunTag
import az.petek.core.model.RegistrationMode
import az.petek.core.sqlite.SqliteDatabase
import az.petek.identity.IdentityTestData.OTHER_RUN_TAG
import az.petek.identity.IdentityTestData.OWNER_EMAIL
import az.petek.identity.IdentityTestData.OWNER_PASSWORD
import az.petek.identity.IdentityTestData.RUN_TAG
import az.petek.identity.IdentityTestData.generator
import az.petek.identity.IdentityTestData.ownAccountSpec
import az.petek.identity.IdentityTestData.spec
import az.petek.identity.domain.Identity
import az.petek.identity.domain.IdentityConflictException
import az.petek.identity.domain.IdentityPlan
import az.petek.identity.domain.IdentityStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException

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
    fun `identities are ordered by agent number, also beyond a99 and a999`() =
        runBlocking<Unit> {
            val repository = repository()
            val big = generator().generate(spec(testers = 1_050, managers = 20), RUN_TAG)

            repository.replaceAll(runId, big)

            val stored = repository.findByRun(runId)
            stored.map { it.agentId } shouldBe (1..1_050).map { AgentId.of(it) }
            stored shouldBe big.identities
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

            error.message.orEmpty() shouldContain "eli.k7x2.a01@test.portal.example"
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

            error.message.orEmpty() shouldContain "eli.k7x2.a01@test.portal.example"
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
                        repository.updateStatus(runId, identity.agentId, IdentityStatus.ACTIVE)
                        repository.updateStorageState(runId, identity.agentId, "${identity.agentId}.json")
                    }
                }.awaitAll()

            val stored = repository.findByRun(runId)
            stored.map { it.status }.toSet() shouldBe setOf(IdentityStatus.ACTIVE)
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

    // --- the owner's accounts (`login`) -----------------------------------------------------------------------------

    private val ownAccounts = generator().generate(ownAccountSpec(), RUN_TAG)
    private val ownAccountsOfOtherRun = generator().generate(ownAccountSpec(), OTHER_RUN_TAG)

    @Test
    fun `every run that signs in with the owner's account stores it, as a repeated run and the next run do`() =
        runBlocking<Unit> {
            val repository = repository()
            val thirdRunId = RunId("0199a1b2-0000-7000-8000-000000000003")
            val third = generator().generate(ownAccountSpec(), RunTag("p4w8"))

            repository.replaceAll(runId, ownAccounts)
            repository.replaceAll(otherRunId, ownAccountsOfOtherRun)
            repository.replaceAll(thirdRunId, third)

            listOf(runId to ownAccounts, otherRunId to ownAccountsOfOtherRun, thirdRunId to third).forEach { (id, plan) ->
                val stored = repository.findByRun(id)
                stored shouldBe plan.identities.map { it.asStored() }
                stored.single { it.registration == RegistrationMode.LOGIN }.email shouldBe OWNER_EMAIL
            }
        }

    @Test
    fun `the owner's account repeats across runs regardless of letter case`() =
        runBlocking<Unit> {
            val repository = repository()
            val shouting =
                IdentityPlan(
                    OTHER_RUN_TAG,
                    ownAccountsOfOtherRun.identities.map { if (it.ownAccount) it.copy(email = it.email.uppercase()) else it },
                )

            repository.replaceAll(runId, ownAccounts)
            repository.replaceAll(otherRunId, shouting)

            repository.findByRun(otherRunId).single { it.ownAccount }.email shouldBe OWNER_EMAIL.uppercase()
        }

    @Test
    fun `a generated e-mail shared by two runs is still refused next to the owner's account, which is not named`() =
        runBlocking<Unit> {
            val repository = repository()
            repository.replaceAll(runId, ownAccounts)
            val generated = ownAccounts.identities.filterNot { it.ownAccount }.map { it.email }

            val error = shouldThrow<IdentityConflictException> { repository.replaceAll(otherRunId, ownAccounts) }

            error.message.orEmpty() shouldContain "e-mail already used by another run: "
            generated.forEach { error.message.orEmpty() shouldContain it }
            error.message.orEmpty() shouldNotContain OWNER_EMAIL
            error.message.orEmpty() shouldNotContain OWNER_PASSWORD
            repository.findByRun(otherRunId).shouldBeEmpty()
        }

    @Test
    fun `a generated e-mail is refused when another run stored it for the owner's account`() =
        runBlocking<Unit> {
            val repository = repository()
            repository.replaceAll(runId, ownAccounts)
            val signingUp = plan.identities.first().copy(email = OWNER_EMAIL)

            val error =
                shouldThrow<IdentityConflictException> { repository.replaceAll(otherRunId, IdentityPlan(RUN_TAG, listOf(signingUp))) }

            error.message.orEmpty() shouldContain "e-mail already used by another run: $OWNER_EMAIL"
        }

    @Test
    fun `the owner's password is not stored, the generated testers' passwords are`() =
        runBlocking<Unit> {
            val db = open()
            val repository = repository(db)

            repository.replaceAll(runId, ownAccounts)
            repository.replaceAll(otherRunId, ownAccountsOfOtherRun)

            val columns = storedPasswords(db)
            columns.map { it.second } shouldNotContain OWNER_PASSWORD
            columns.filter { it.first == RegistrationMode.LOGIN.key }.map { it.second }.toSet() shouldBe setOf("")
            val generated = (ownAccounts.identities + ownAccountsOfOtherRun.identities).filterNot { it.ownAccount }
            columns.map { it.second } shouldContain generated.first().password.reveal()
            repository.findByRun(runId).single { it.ownAccount }.password shouldBe Identity.NOT_STORED
            closeDatabases()
            opened.clear()
            databaseFiles() shouldNotContain OWNER_PASSWORD
        }

    // --- the database's e-mail index -----------------------------------------------------------------------------

    @Test
    fun `a new database makes the generated testers' e-mails unique, not the owner's accounts'`() =
        runBlocking<Unit> {
            val db = open()
            repository(db)

            val indexes = emailIndexes(db)
            indexes[IdentityTable.GENERATED_EMAIL_INDEX] shouldBe true
            indexes.keys shouldNotContain IdentityTable.LEGACY_EMAIL_INDEX
            // The database itself holds the rule, also for a writer that skips the repository's own check.
            insertRaw(db, "run_a", "a01", "Owner Account", OWNER_EMAIL, RegistrationMode.LOGIN.key)
            insertRaw(db, "run_b", "a01", "Owner Account", OWNER_EMAIL.uppercase(), RegistrationMode.LOGIN.key)
            insertRaw(db, "run_a", "a02", "Self One", "self.k7x2.a02@test.portal.example", RegistrationMode.SELF.key)
            val error =
                shouldThrow<SQLException> {
                    insertRaw(db, "run_b", "a02", "Self One", "SELF.k7x2.a02@test.portal.example", RegistrationMode.SELF.key)
                }
            error.message.orEmpty() shouldContain "UNIQUE constraint failed"
        }

    @Test
    fun `a database of an earlier release is moved over, keeps its rows and then stores the owner's account again`() =
        runBlocking<Unit> {
            val db = open()
            val legacyCompany = generator().generate(spec(testers = 6, managers = 1), RunTag("b000"))
            db.createMissing(FirstReleaseIdentityTable)
            insertFirstRelease(db, otherRunId, legacyCompany)
            insertFirstRelease(db, runId, ownAccounts)
            emailIndexes(db) shouldBe mapOf(IdentityTable.LEGACY_EMAIL_INDEX to false)
            databaseFiles() shouldContain OWNER_PASSWORD

            val repository = repository(db)

            emailIndexes(db) shouldBe mapOf(IdentityTable.GENERATED_EMAIL_INDEX to true)
            repository.findByRun(otherRunId) shouldBe legacyCompany.identities
            repository.findByRun(runId) shouldBe ownAccounts.identities.map { it.asStored() }
            storedPasswords(db).map { it.second } shouldNotContain OWNER_PASSWORD
            val nextRunId = RunId("0199a1b2-0000-7000-8000-000000000003")
            repository.replaceAll(nextRunId, ownAccountsOfOtherRun)
            repository.findByRun(nextRunId).single { it.ownAccount }.email shouldBe OWNER_EMAIL
            shouldThrow<IdentityConflictException> {
                repository.replaceAll(RunId("0199a1b2-0000-7000-8000-000000000004"), legacyCompany)
            }.message.orEmpty() shouldContain legacyCompany.identities.first().email
            closeDatabases()
            opened.clear()
            databaseFiles() shouldNotContain OWNER_PASSWORD
        }

    @Test
    fun `a database an earlier release ran a campaign on keeps no copy of the owner's password in its file`() =
        runBlocking<Unit> {
            val db = open()
            db.createMissing(FirstReleaseIdentityTable)
            insertFirstRelease(db, runId, ownAccounts)
            runFirstRelease(db, runId, ownAccounts)
            insertFirstRelease(db, otherRunId, plan)
            runFirstRelease(db, otherRunId, plan)
            closeDatabases()
            opened.clear()
            databaseFiles() shouldContain OWNER_PASSWORD

            val repository = repository()

            repository.findByRun(runId).map { it.storageStatePath } shouldBe ownAccounts.identities.map { statePath(it.agentId) }
            closeDatabases()
            opened.clear()
            databaseFiles() shouldNotContain OWNER_PASSWORD
        }

    @Test
    fun `moving a database over twice changes nothing more`() =
        runBlocking<Unit> {
            val db = open()
            db.createMissing(FirstReleaseIdentityTable)
            insertFirstRelease(db, runId, ownAccounts)
            repository(db)
            val once = storedPasswords(db)

            repository(db)
            closeDatabases()
            opened.clear()
            val reopened = repository()

            emailIndexes(opened.single()) shouldBe mapOf(IdentityTable.GENERATED_EMAIL_INDEX to true)
            storedPasswords(opened.single()) shouldBe once
            reopened.findByRun(runId) shouldBe ownAccounts.identities.map { it.asStored() }
        }

    /** Every stored row's registration and password column, as written. */
    private fun storedPasswords(db: SqliteDatabase): List<Pair<String, String>> =
        runBlocking {
            db.read {
                IdentityTable.selectAll().map { it[IdentityTable.registration] to it[IdentityTable.password] }
            }
        }

    /** Everything the database files hold (the database and its journal), as text. */
    private fun databaseFiles(): String =
        Files.walk(dir).use { files ->
            files.filter(Files::isRegularFile).toList().joinToString("\n") { String(Files.readAllBytes(it), Charsets.ISO_8859_1) }
        }

    /** The table's indexes on `email`, each with whether it is partial. */
    private fun emailIndexes(db: SqliteDatabase): Map<String, Boolean> =
        runBlocking {
            db.read {
                val all = mutableMapOf<String, Boolean>()
                exec("PRAGMA index_list(\"identity\")") { rows ->
                    while (rows.next()) all[rows.getString("name")] = rows.getInt("partial") == 1
                }
                all.filterKeys { name ->
                    var onEmail = false
                    exec("PRAGMA index_info(\"$name\")") { rows ->
                        while (rows.next()) onEmail = onEmail || rows.getString("name") == "email"
                    }
                    onEmail
                }
            }
        }

    private fun insertRaw(
        db: SqliteDatabase,
        run: String,
        agent: String,
        name: String,
        email: String,
        registration: String,
    ) = runBlocking {
        db.write {
            exec(
                "INSERT INTO identity (run_id, agent_id, display_name, email, password, phone, role, registration, status) " +
                    "VALUES ('$run', '$agent', '$name', '$email', 'x', '+994500000000', 'reader', '$registration', 'planned')",
            )
        }
    }

    /** What the first release wrote for [plan]: every field as given, the owner's password too. */
    private fun insertFirstRelease(
        db: SqliteDatabase,
        run: RunId,
        plan: IdentityPlan,
    ) = runBlocking {
        db.write {
            FirstReleaseIdentityTable.batchInsert(plan.identities, shouldReturnGeneratedValues = false) { identity ->
                this[FirstReleaseIdentityTable.runId] = run.value
                this[FirstReleaseIdentityTable.agentId] = identity.agentId.value
                this[FirstReleaseIdentityTable.displayName] = identity.displayName
                this[FirstReleaseIdentityTable.email] = identity.email
                this[FirstReleaseIdentityTable.password] = identity.password.reveal()
                this[FirstReleaseIdentityTable.phone] = identity.phone
                this[FirstReleaseIdentityTable.role] = identity.role.key
                this[FirstReleaseIdentityTable.department] = identity.department
                this[FirstReleaseIdentityTable.registration] = identity.registration.key
                this[FirstReleaseIdentityTable.status] = "planned"
            }
        }
    }

    /**
     * What the first release's runner did to [plan]'s rows during a run: each agent's status and its storage state, one
     * write transaction each. The rows grow, so SQLite moves them and leaves the old cells in the page's free space.
     */
    private fun runFirstRelease(
        db: SqliteDatabase,
        run: RunId,
        plan: IdentityPlan,
    ) = runBlocking {
        plan.identities.forEach { identity ->
            val row = (FirstReleaseIdentityTable.runId eq run.value) and (FirstReleaseIdentityTable.agentId eq identity.agentId.value)
            db.write { FirstReleaseIdentityTable.update({ row }) { it[status] = "registered" } }
            db.write {
                FirstReleaseIdentityTable.update({ row }) {
                    it[status] = "active"
                    it[statusReason] = "signed in and kept the session for the rest of the campaign"
                }
            }
            db.write { FirstReleaseIdentityTable.update({ row }) { it[storageStatePath] = statePath(identity.agentId) } }
        }
    }

    private fun statePath(agentId: AgentId) = "/home/owner/petek/evidence/storage-state/${agentId.value}.json"

    /** The `identity` table as the first release declared it: every e-mail unique, no workspace column. */
    private object FirstReleaseIdentityTable : Table("identity") {
        val runId = varchar("run_id", 128)
        val agentId = varchar("agent_id", 8)
        val displayName = varchar("display_name", 255)
        val email = varchar("email", 320, collate = "NOCASE").uniqueIndex("identity_email_unique")
        val password = varchar("password", 128)
        val phone = varchar("phone", 32)
        val role = varchar("role", 16)
        val department = varchar("department", 255).nullable()
        val registration = varchar("registration", 16)
        val status = varchar("status", 16)
        val statusReason = text("status_reason").nullable()
        val storageStatePath = text("storage_state_path").nullable()

        override val primaryKey = PrimaryKey(runId, agentId, name = "identity_run_agent_pk")

        init {
            uniqueIndex("identity_run_display_name_unique", runId, displayName)
        }
    }
}

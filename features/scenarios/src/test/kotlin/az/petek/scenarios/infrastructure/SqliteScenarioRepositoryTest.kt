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

import az.petek.core.sqlite.SqliteDatabase
import az.petek.scenarios.domain.NewScenarioVersion
import az.petek.scenarios.domain.ScenarioLifecycle
import az.petek.scenarios.domain.ScenarioRepository
import az.petek.scenarios.domain.ScenarioSource
import az.petek.scenarios.domain.ScenarioStatus
import az.petek.scenarios.domain.ScenarioVersionId
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class SqliteScenarioRepositoryTest : ScenarioRepositoryContract() {
    @TempDir
    lateinit var dir: Path

    private val opened = mutableListOf<SqliteDatabase>()

    private fun database(): SqliteDatabase = SqliteDatabase.open(dir.resolve("petek.db")).also { opened += it }

    override fun repository(): ScenarioRepository = SqliteScenarioRepository(database())

    @AfterEach
    fun close() {
        opened.forEach { it.close() }
    }

    private val t0 = Instant.parse("2026-01-01T10:00:00Z")

    private fun draft(
        id: String,
        name: String = "mini",
    ) = NewScenarioVersion(ScenarioVersionId(id), name, "campaign: {name: $name} # $id\n", ScenarioSource.USER, null, "", t0)

    /** Runs raw SQL the way a tool or a buggy caller could, bypassing the repository's own checks. */
    private fun sql(
        db: SqliteDatabase,
        statement: String,
    ) {
        transaction(db.database) { exec(statement) }
    }

    @Test
    fun `the database itself refuses to change or delete a frozen version`() =
        runBlocking<Unit> {
            val db = database()
            val repo = SqliteScenarioRepository(db)
            val v1 = repo.add(draft("scn_1"))
            repo.update(ScenarioLifecycle.approve(v1, listOf(v1), t0))
            repo.update(ScenarioLifecycle.freeze(repo.find(v1.id)!!, t0))

            shouldThrowAny { sql(db, "UPDATE scenario_version SET status = 'SUPERSEDED' WHERE id = 'scn_1'") }.message shouldContain
                "FROZEN"
            shouldThrowAny { sql(db, "DELETE FROM scenario_version WHERE id = 'scn_1'") }.message shouldContain "FROZEN"

            repo.find(v1.id)?.status shouldBe ScenarioStatus.FROZEN
        }

    @Test
    fun `the database itself refuses to change the text of any version`() =
        runBlocking<Unit> {
            val db = database()
            val repo = SqliteScenarioRepository(db)
            repo.add(draft("scn_1"))

            shouldThrowAny { sql(db, "UPDATE scenario_version SET yaml = 'hacked' WHERE id = 'scn_1'") }.message shouldContain "immutable"

            repo.find(ScenarioVersionId("scn_1"))?.yaml shouldBe "campaign: {name: mini} # scn_1\n"
        }

    @Test
    fun `the database itself refuses a second approved version of a name`() =
        runBlocking<Unit> {
            val db = database()
            val repo = SqliteScenarioRepository(db)
            val v1 = repo.add(draft("scn_1"))
            repo.add(draft("scn_2"))
            repo.update(ScenarioLifecycle.approve(v1, listOf(v1), t0))

            shouldThrowAny {
                sql(
                    db,
                    "UPDATE scenario_version SET status = 'APPROVED', approved_at = '2026-01-01T10:00:00.000000000Z' WHERE id = 'scn_2'",
                )
            }

            repo.history("mini").map { it.status } shouldBe listOf(ScenarioStatus.APPROVED, ScenarioStatus.DRAFT)
        }

    @Test
    fun `versions survive reopening the database and the schema is created only once`() {
        runBlocking {
            val first = database()
            val repo = SqliteScenarioRepository(first)
            val v1 = repo.add(draft("scn_1"))
            repo.update(ScenarioLifecycle.approve(v1, listOf(v1), t0))
            first.close()

            val reopened = SqliteScenarioRepository(database())
            SqliteScenarioRepository(database())

            reopened.find(v1.id)?.status shouldBe ScenarioStatus.APPROVED
            reopened.add(draft("scn_2")).version shouldBe 2
        }
    }
}

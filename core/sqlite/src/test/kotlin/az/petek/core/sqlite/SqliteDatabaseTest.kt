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

package az.petek.core.sqlite

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SqliteDatabaseTest {
    private object Items : Table("items") {
        val id = integer("id")
        val name = varchar("name", 64)
        override val primaryKey = PrimaryKey(id)
    }

    @Test
    fun `concurrent writers are serialized and every row is stored`(
        @TempDir dir: Path,
    ) = runBlocking<Unit> {
        SqliteDatabase.open(dir.resolve("nested/petek.db")).use { db ->
            db.createMissing(Items)
            (1..200)
                .map { i ->
                    async {
                        db.write {
                            Items.insert {
                                it[id] = i
                                it[name] = "item-$i"
                            }
                        }
                    }
                }.awaitAll()
            db.read { Items.selectAll().count() } shouldBe 200L
        }
    }

    @Test
    fun `a failing write is rolled back and the writer keeps serving later writes`(
        @TempDir dir: Path,
    ) = runBlocking<Unit> {
        SqliteDatabase.open(dir.resolve("petek.db")).use { db ->
            db.createMissing(Items)

            shouldThrow<IllegalStateException> {
                db.write {
                    Items.insert {
                        it[id] = 1
                        it[name] = "half-written"
                    }
                    error("boom")
                }
            }
            db.write {
                Items.insert {
                    it[id] = 2
                    it[name] = "stored"
                }
            }

            db.read { Items.selectAll().map { it[Items.name] } } shouldBe listOf("stored")
        }
    }

    @Test
    fun `a write that reads first is not broken by a schema statement on another thread`(
        @TempDir dir: Path,
    ) = runBlocking<Unit> {
        SqliteDatabase.open(dir.resolve("petek.db")).use { db ->
            db.createMissing(Items)
            // The start-up race the panel once lost on CI: the writer numbers a row (a read) while a repository being
            // constructed runs its schema statements on the caller's thread. With a deferred write transaction the
            // writer's snapshot went stale and SQLite refused the insert at once (SQLITE_BUSY_SNAPSHOT).
            val writerReading = CountDownLatch(1)
            val setUpDone = CountDownLatch(1)
            val attempts = AtomicInteger()
            val setUp =
                thread(name = "constructor") {
                    writerReading.await()
                    db.setUp {
                        exec("CREATE INDEX IF NOT EXISTS items_name ON items (name)")
                        Items.insert {
                            it[id] = 1
                            it[name] = "from-set-up"
                        }
                    }
                    setUpDone.countDown()
                }

            db.write {
                attempts.incrementAndGet()
                val next = (Items.selectAll().count() + 1).toInt()
                writerReading.countDown()
                // With the lock taken up front the constructor waits for this transaction and the latch times out,
                // which is the point; a deferred transaction would let the constructor commit first.
                setUpDone.await(1, TimeUnit.SECONDS)
                Items.insert {
                    it[id] = next + 1
                    it[name] = "from-writer"
                }
            }
            setUp.join()

            db.read { Items.selectAll().map { it[Items.name] }.sorted() } shouldBe listOf("from-set-up", "from-writer")
            // Exposed retries a failed transaction, which hid the refusal locally; the block must have run once.
            attempts.get() shouldBe 1
        }
    }

    @Test
    fun `creating tables twice is harmless`(
        @TempDir dir: Path,
    ) {
        SqliteDatabase.open(dir.resolve("petek.db")).use { db ->
            db.createMissing(Items)
            db.createMissing(Items)
        }
    }

    private object ItemsV2 : Table("items") {
        val id = integer("id")
        val name = varchar("name", 64)
        val workspace = text("workspace_id").default("local")
        val note = text("note").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    private object ItemsWithRequiredColumn : Table("items") {
        val id = integer("id")
        val required = text("required")
        override val primaryKey = PrimaryKey(id)
    }

    @Test
    fun `a column a newer version declares is added to a table an older one created, keeping its rows`(
        @TempDir dir: Path,
    ) = runBlocking<Unit> {
        SqliteDatabase.open(dir.resolve("petek.db")).use { db ->
            db.createMissing(Items)
            db.write {
                Items.insert {
                    it[id] = 1
                    it[name] = "old"
                }
            }

            db.createMissing(ItemsV2)
            db.createMissing(ItemsV2)

            val row = db.read { ItemsV2.selectAll().single() }
            row[ItemsV2.name] shouldBe "old"
            row[ItemsV2.workspace] shouldBe "local"
            row[ItemsV2.note] shouldBe null
        }
    }

    @Test
    fun `a required column without a default is refused instead of breaking an existing table`(
        @TempDir dir: Path,
    ) {
        SqliteDatabase.open(dir.resolve("petek.db")).use { db ->
            db.createMissing(Items)

            shouldThrow<IllegalArgumentException> { db.createMissing(ItemsWithRequiredColumn) }.message shouldBe
                "column items.required cannot be added to an existing table: it needs a default"
        }
    }
}

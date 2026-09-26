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

package az.petek.ownership.infrastructure

import az.petek.core.sqlite.SqliteDatabase
import az.petek.ownership.domain.OwnershipMethod
import az.petek.ownership.domain.OwnershipRecord
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class SqliteOwnershipLedgerTest {
    @TempDir
    lateinit var dir: Path

    private val db: SqliteDatabase by lazy { SqliteDatabase.open(dir.resolve("petek.db")) }

    @AfterEach
    fun close() {
        db.close()
    }

    @Test
    fun `a saved proof is found by its host`() =
        runTest {
            val ledger = SqliteOwnershipLedger(db)
            val record = OwnershipRecord("stage.example.com", OwnershipMethod.DNS_TXT, Instant.parse("2026-09-26T10:00:00Z"))

            ledger.save(record)

            ledger.find("stage.example.com") shouldBe record
            ledger.find("www.example.com") shouldBe null
        }

    @Test
    fun `a later proof replaces the earlier one and survives a new ledger on the same file`() =
        runTest {
            SqliteOwnershipLedger(
                db,
            ).save(OwnershipRecord("stage.example.com", OwnershipMethod.DNS_TXT, Instant.parse("2026-09-01T10:00:00Z")))
            val later = OwnershipRecord("stage.example.com", OwnershipMethod.WELL_KNOWN_FILE, Instant.parse("2026-10-01T10:00:00Z"))
            SqliteOwnershipLedger(db).save(later)

            SqliteOwnershipLedger(db).find("stage.example.com") shouldBe later
        }
}

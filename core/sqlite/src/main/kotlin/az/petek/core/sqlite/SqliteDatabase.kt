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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.Executors

/**
 * One SQLite database file shared by the feature repositories of a process.
 * Writes are serialized on a single dedicated thread (SQLite allows one writer); reads run on [Dispatchers.IO]
 * and see a consistent snapshot thanks to WAL mode.
 *
 * Write transactions open with `BEGIN IMMEDIATE`: the write lock is taken before the first statement, so a
 * transaction that reads and then writes (numbering a version, checking a row exists) never holds a snapshot that a
 * schema statement on another thread makes stale — SQLite refuses such an upgrade at once (`SQLITE_BUSY_SNAPSHOT`),
 * the busy timeout does not apply to it. Read transactions stay deferred and never take the lock.
 */
class SqliteDatabase private constructor(
    val path: Path,
    /** Read connections (deferred transactions). Also what raw SQL in tests goes through. */
    val database: Database,
    /** Write connections (`BEGIN IMMEDIATE`, waits [BUSY_TIMEOUT_MILLIS] for the lock). */
    private val writable: Database,
) : AutoCloseable {
    private val writerExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "petek-sqlite-writer").apply { isDaemon = true }
        }
    private val writer = writerExecutor.asCoroutineDispatcher()

    /** Runs [block] in a write transaction on the single writer thread. */
    suspend fun <T> write(block: JdbcTransaction.() -> T): T = withContext(writer) { transaction(writable) { block() } }

    /** Runs [block] in a read transaction. */
    suspend fun <T> read(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(database) { block() } }

    /**
     * Creates missing tables and indexes and adds columns a newer Pətək declares to tables an older one created
     * (idempotent). Called once per repository at start-up. An added column must be nullable or have a default, as
     * SQLite's `ALTER TABLE ... ADD COLUMN` requires.
     */
    fun createMissing(vararg tables: Table) {
        setUp {
            SchemaUtils.create(*tables)
            tables.forEach { table -> addMissingColumns(table) }
        }
    }

    private fun JdbcTransaction.addMissingColumns(table: Table) {
        val existing = mutableSetOf<String>()
        exec("PRAGMA table_info(\"${table.tableName}\")") { rows ->
            while (rows.next()) existing += rows.getString("name").lowercase()
        }
        table.columns
            .filter { it.name.lowercase() !in existing }
            .forEach { column ->
                require(column.columnType.nullable || column.defaultValueFun != null) {
                    "column ${table.tableName}.${column.name} cannot be added to an existing table: it needs a default"
                }
                exec("ALTER TABLE \"${table.tableName}\" ADD COLUMN ${column.descriptionDdl()}")
            }
    }

    /**
     * Runs [block] in a write transaction on the calling thread: for the schema statements a repository runs when it
     * is constructed (indexes, triggers), which cannot suspend. It takes the write lock like [write] does, so it waits
     * for a write in progress instead of making that write fail.
     */
    fun <T> setUp(block: JdbcTransaction.() -> T): T = transaction(writable) { block() }

    override fun close() {
        writer.close()
    }

    companion object {
        /** How long a connection waits for the write lock before giving up. */
        const val BUSY_TIMEOUT_MILLIS = 10_000

        fun open(path: Path): SqliteDatabase {
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            val url = "jdbc:sqlite:${path.toAbsolutePath()}"
            return SqliteDatabase(
                path,
                database = connect(url, SQLiteConfig.TransactionMode.DEFERRED),
                writable = connect(url, SQLiteConfig.TransactionMode.IMMEDIATE),
            )
        }

        private fun connect(
            url: String,
            mode: SQLiteConfig.TransactionMode,
        ): Database {
            val config =
                SQLiteConfig().apply {
                    setJournalMode(SQLiteConfig.JournalMode.WAL)
                    setBusyTimeout(BUSY_TIMEOUT_MILLIS)
                    enforceForeignKeys(true)
                    setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
                    setTransactionMode(mode)
                }
            return Database.connect(
                datasource = SQLiteDataSource(config).apply { setUrl(url) },
                databaseConfig =
                    DatabaseConfig {
                        defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
                    },
            )
        }
    }
}

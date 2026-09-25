/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.Executors

/**
 * One SQLite database file shared by the feature repositories of a process.
 * Writes are serialized on a single dedicated thread (SQLite allows one writer); reads run on [Dispatchers.IO]
 * and see a consistent snapshot thanks to WAL mode.
 */
class SqliteDatabase private constructor(
    val path: Path,
    val database: Database,
) : AutoCloseable {
    private val writerExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "petek-sqlite-writer").apply { isDaemon = true }
        }
    private val writer = writerExecutor.asCoroutineDispatcher()

    /** Runs [block] in a write transaction on the single writer thread. */
    suspend fun <T> write(block: JdbcTransaction.() -> T): T = withContext(writer) { transaction(database) { block() } }

    /** Runs [block] in a read transaction. */
    suspend fun <T> read(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(database) { block() } }

    /** Creates missing tables and indexes (idempotent). Called once per repository at start-up. */
    fun createMissing(vararg tables: Table) {
        transaction(database) { SchemaUtils.create(*tables) }
    }

    override fun close() {
        writer.close()
    }

    companion object {
        fun open(path: Path): SqliteDatabase {
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            val database =
                Database.connect(
                    url = "jdbc:sqlite:${path.toAbsolutePath()}",
                    driver = "org.sqlite.JDBC",
                    setupConnection = { connection ->
                        connection.createStatement().use { statement ->
                            statement.execute("PRAGMA journal_mode=WAL")
                            statement.execute("PRAGMA busy_timeout=10000")
                            statement.execute("PRAGMA foreign_keys=ON")
                            statement.execute("PRAGMA synchronous=NORMAL")
                        }
                    },
                    databaseConfig =
                        DatabaseConfig {
                            defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE
                        },
                )
            return SqliteDatabase(path, database)
        }
    }
}

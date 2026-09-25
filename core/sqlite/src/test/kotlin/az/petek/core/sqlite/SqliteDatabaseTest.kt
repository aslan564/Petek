/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
    fun `creating tables twice is harmless`(
        @TempDir dir: Path,
    ) {
        SqliteDatabase.open(dir.resolve("petek.db")).use { db ->
            db.createMissing(Items)
            db.createMissing(Items)
        }
    }
}

package az.petek.core.sqlite

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
    ) = runBlocking {
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
    fun `creating tables twice is harmless`(
        @TempDir dir: Path,
    ) {
        SqliteDatabase.open(dir.resolve("petek.db")).use { db ->
            db.createMissing(Items)
            db.createMissing(Items)
        }
    }
}

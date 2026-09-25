/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.evidence.infrastructure

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.core.testing.SequentialIdGenerator
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.walk
import kotlin.io.path.writeBytes

class FileSystemArtifactStoreTest {
    @TempDir
    lateinit var root: Path

    private val run = RunId("run_1")
    private val step = StepId("stp_7")

    private fun store() = FileSystemArtifactStore(root, SequentialIdGenerator())

    @Test
    fun `writes the bytes under run and owner and describes them with hash and size`() =
        runTest {
            val record = store().write(run, step, "a07", ArtifactType.SCREENSHOT, "hello".toByteArray())

            record shouldBe
                ArtifactRecord(
                    artifactId = ArtifactId("art_1"),
                    runId = run,
                    stepId = step,
                    type = ArtifactType.SCREENSHOT,
                    relativePath = "run_1/a07/0001-screenshot.png",
                    sha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                    sizeBytes = 5,
                )
            root.resolve("run_1/a07/0001-screenshot.png").readBytes() shouldBe "hello".toByteArray()
        }

    @Test
    fun `resolve points at the written file`() =
        runTest {
            val store = store()
            val bytes = ByteArray(10_000) { (it % 251).toByte() }

            val record = store.write(run, step, "a07", ArtifactType.DOM, bytes)

            store.resolve(record) shouldBe root.toAbsolutePath().resolve("run_1/a07/0001-dom.html")
            store.resolve(record).readBytes() shouldBe bytes
            record.sizeBytes shouldBe 10_000
        }

    @Test
    fun `an empty artifact is stored with the hash of no bytes`() =
        runTest {
            val record = store().write(run, step, "a07", ArtifactType.LOG, ByteArray(0))

            record.sizeBytes shouldBe 0
            record.sha256 shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            Files.size(root.resolve(record.relativePath)) shouldBe 0
        }

    @Test
    fun `each artifact type uses its own extension`() =
        runTest {
            val store = store()

            val paths = ArtifactType.entries.map { store.write(run, step, "a07", it, byteArrayOf(1)).relativePath }

            paths shouldContainExactly
                listOf(
                    "run_1/a07/0001-screenshot.png",
                    "run_1/a07/0002-a11y.yaml",
                    "run_1/a07/0003-dom.html",
                    "run_1/a07/0004-http.txt",
                    "run_1/a07/0005-mail.json",
                    "run_1/a07/0006-oracle.json",
                    "run_1/a07/0007-prompt.txt",
                    "run_1/a07/0008-log.txt",
                )
        }

    @Test
    fun `the sequence counts per run and owner`() =
        runTest {
            val store = store()

            val first = store.write(run, step, "a07", ArtifactType.SCREENSHOT, byteArrayOf(1))
            val otherOwner = store.write(run, step, "a08", ArtifactType.SCREENSHOT, byteArrayOf(2))
            val second = store.write(run, step, "a07", ArtifactType.SCREENSHOT, byteArrayOf(3))
            val otherRun = store.write(RunId("run_2"), step, "a07", ArtifactType.SCREENSHOT, byteArrayOf(4))

            first.relativePath shouldBe "run_1/a07/0001-screenshot.png"
            otherOwner.relativePath shouldBe "run_1/a08/0001-screenshot.png"
            second.relativePath shouldBe "run_1/a07/0002-screenshot.png"
            otherRun.relativePath shouldBe "run_2/a07/0001-screenshot.png"
        }

    @Test
    fun `concurrent writes of one owner get distinct consecutive numbers and all their bytes`() =
        runTest {
            val store = store()

            val records =
                withContext(Dispatchers.Default) {
                    (1..120)
                        .map { i -> async { store.write(run, step, "a07", ArtifactType.LOG, "log $i".toByteArray()) } }
                        .awaitAll()
                }

            records.map { it.relativePath } shouldContainExactlyInAnyOrder
                (1..120).map { "run_1/a07/${it.toString().padStart(4, '0')}-log.txt" }
            records.map { it.artifactId }.toSet().size shouldBe 120
            records.forEach { store.resolve(it).readBytes().decodeToString() shouldStartWith "log " }
            records.map { store.resolve(it).readBytes().decodeToString() }.toSet() shouldBe (1..120).map { "log $it" }.toSet()
        }

    @Test
    fun `a new store continues numbering after files already on disk instead of overwriting them`() =
        runTest {
            store().write(run, step, "a07", ArtifactType.SCREENSHOT, "first".toByteArray())
            store().write(run, step, "a07", ArtifactType.SCREENSHOT, "second".toByteArray())

            val third = store().write(run, step, "a07", ArtifactType.SCREENSHOT, "third".toByteArray())

            third.relativePath shouldBe "run_1/a07/0003-screenshot.png"
            root.resolve("run_1/a07/0001-screenshot.png").readBytes().decodeToString() shouldBe "first"
            root.resolve("run_1/a07/0002-screenshot.png").readBytes().decodeToString() shouldBe "second"
        }

    @Test
    fun `two stores writing to the same run and owner in turn never overwrite each other`() =
        runTest {
            val first = store()
            first.write(run, step, "a07", ArtifactType.SCREENSHOT, "A1".toByteArray())
            val second = store()

            val written =
                listOf(
                    second.write(run, step, "a07", ArtifactType.SCREENSHOT, "B1".toByteArray()),
                    first.write(run, step, "a07", ArtifactType.SCREENSHOT, "A2".toByteArray()),
                    second.write(run, step, "a07", ArtifactType.SCREENSHOT, "B2".toByteArray()),
                    first.write(run, step, "a07", ArtifactType.SCREENSHOT, "A3".toByteArray()),
                )

            written.map { it.relativePath }.toSet().size shouldBe written.size
            written.map { store().resolve(it).readBytes().decodeToString() } shouldContainExactly listOf("B1", "A2", "B2", "A3")
            root.resolve("run_1/a07/0001-screenshot.png").readBytes().decodeToString() shouldBe "A1"
        }

    @Test
    fun `a name taken on disk after the store started is skipped instead of replaced`() =
        runTest {
            val store = store()
            store.write(run, step, "a07", ArtifactType.SCREENSHOT, "mine".toByteArray())
            val foreign = root.resolve("run_1/a07/0002-screenshot.png")
            foreign.writeBytes("foreign".toByteArray())

            val next = store.write(run, step, "a07", ArtifactType.SCREENSHOT, "next".toByteArray())

            next.relativePath shouldBe "run_1/a07/0003-screenshot.png"
            foreign.readBytes().decodeToString() shouldBe "foreign"
            store.resolve(next).readBytes().decodeToString() shouldBe "next"
        }

    @Test
    fun `numbering ignores stray files that do not start with a sequence`() =
        runTest {
            val ownerDir = Files.createDirectories(root.resolve("run_1/a07"))
            ownerDir.resolve("notes.txt").writeBytes(byteArrayOf(1))
            ownerDir.resolve(".0042-screenshot.png.tmp").writeBytes(byteArrayOf(1))

            store().write(run, step, "a07", ArtifactType.SCREENSHOT, byteArrayOf(1)).relativePath shouldBe
                "run_1/a07/0001-screenshot.png"
        }

    @Test
    fun `successful writes leave no temporary files behind`() =
        runTest {
            val store = store()
            withContext(Dispatchers.Default) {
                (1..30).map { i -> async { store.write(run, step, "a0${i % 3}", ArtifactType.HTTP, ByteArray(i)) } }.awaitAll()
            }

            val files = root.walk().filter { it.isRegularFile() }.toList()
            files.size shouldBe 30
            files.filter { it.name.startsWith(".") || it.name.endsWith(".tmp") }.shouldBeEmpty()
        }

    @Test
    fun `owners are sanitized so they can never leave the run directory`() =
        runTest {
            val store = store()

            val traversal = store.write(run, step, "../../etc", ArtifactType.LOG, byteArrayOf(1))
            val separators = store.write(run, step, "a/b\\c", ArtifactType.LOG, byteArrayOf(1))
            val blank = store.write(run, step, "", ArtifactType.LOG, byteArrayOf(1))

            traversal.relativePath shouldBe "run_1/______etc/0001-log.txt"
            separators.relativePath shouldBe "run_1/a_b_c/0001-log.txt"
            blank.relativePath shouldBe "run_1/_/0001-log.txt"
            listOf(traversal, separators, blank).forEach { store.resolve(it).startsWith(root.resolve("run_1")) shouldBe true }
        }

    @Test
    fun `run ids that are not a single safe directory name are rejected`() =
        runTest {
            val store = store()

            listOf("..", ".", "../escape", "a/b", ".hidden").forEach { bad ->
                shouldThrow<IllegalArgumentException> { store.write(RunId(bad), step, "a07", ArtifactType.LOG, byteArrayOf(1)) }
                shouldThrow<IllegalArgumentException> { store.runDirectory(RunId(bad)) }
            }
            root.listDirectoryEntries().shouldBeEmpty()
        }

    @Test
    fun `resolve rejects recorded paths that escape the evidence root`() {
        val store = store()

        listOf("../outside.png", "run_1/../../outside.png", "run_1/a07/../../../x", "", ".").forEach { bad ->
            shouldThrow<IllegalArgumentException> { store.resolve(recordAt(bad)) }
        }
        shouldThrow<IllegalArgumentException> { store.resolve(recordAt(root.resolve("run_1/a07/0001-log.txt").toString())) }
    }

    @Test
    fun `resolve accepts paths that only look unusual but stay inside the root`() {
        store().resolve(recordAt("run_1/a07/../a08/0001-log.txt")) shouldBe
            root.toAbsolutePath().resolve("run_1/a08/0001-log.txt")
    }

    @Test
    fun `the run directory is the run id under the root`() {
        store().runDirectory(run) shouldBe root.toAbsolutePath().resolve("run_1")
    }

    @Test
    fun `a relative root is anchored at the working directory`() {
        val store = FileSystemArtifactStore(Path.of("evidence"), SequentialIdGenerator())

        store.runDirectory(run) shouldBe Path.of("evidence", "run_1").toAbsolutePath()
    }

    private fun recordAt(relativePath: String) =
        ArtifactRecord(ArtifactId("art_x"), run, step, ArtifactType.LOG, relativePath, sha256 = "0".repeat(64), sizeBytes = 0)
}

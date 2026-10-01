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

package az.petek.reporting.application

import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactType
import az.petek.evidence.domain.LookFrameKind
import az.petek.evidence.testing.InMemoryArtifactStore
import az.petek.reporting.LookTestData.BLUE
import az.petek.reporting.LookTestData.RED
import az.petek.reporting.LookTestData.RawCodec
import az.petek.reporting.LookTestData.frame
import az.petek.reporting.LookTestData.look
import az.petek.reporting.LookTestData.page
import az.petek.reporting.LookTestData.painted
import az.petek.reporting.ReportTestData
import az.petek.reporting.domain.visual.IgnoredBy
import az.petek.reporting.domain.visual.LookChange
import az.petek.reporting.domain.visual.LookReason
import az.petek.reporting.domain.visual.Raster
import az.petek.reporting.domain.visual.VisualThresholds
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

class CompareLooksTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { InMemoryArtifactStore(dir) }
    private val codec = RawCodec()
    private val base = RunId("run_base")
    private val now = RunId("run_now")
    private val report by lazy { ReportLayout.directory(store, now) }
    private val visual by lazy { report.resolve("visual").resolve("run_base") }
    private val name = "001-public-look-qiymetler-phone"

    private val page = page(60, 80)
    private val shown = page.painted(10, 20, 30, 20, RED)
    private val changed = page.painted(10, 20, 30, 20, BLUE)

    /** Writes [raster] as a visual artifact of [runId], on disk where the store resolves it. */
    private suspend fun stored(
        runId: RunId,
        agent: String,
        raster: Raster,
    ): ArtifactRecord {
        val bytes = codec.encode(raster)
        val record = store.write(runId, StepId("stp_look_$agent"), agent, ArtifactType.VISUAL, bytes)
        val path = store.resolve(record)
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        return record
    }

    /** A run whose tester a01 looked at `/qiymetler` and saw [main] (and [moved], a frame of the same load). */
    private suspend fun looked(
        runId: RunId,
        main: Raster,
        moved: Raster? = null,
    ): CompareLooks.LookRun {
        val records = listOfNotNull(stored(runId, "a01", main), moved?.let { stored(runId, "a01", it) })
        val frames =
            records.mapIndexed { i, record ->
                frame(record.artifactId.value, if (i == 0) LookFrameKind.MAIN else LookFrameKind.MOVED, main.width, main.height)
            }
        val look = look("a01", runId, page = "/qiymetler", frames = frames, pageHeight = main.height)
        return CompareLooks.LookRun(ReportTestData.run(runId, result = az.petek.evidence.domain.RunResult.PASSED), listOf(look), records)
    }

    private suspend fun inputs(
        before: Raster = shown,
        after: Raster = changed,
    ) = CompareLooks.Inputs(listOf(looked(base, before)), listOf(looked(now, after)))

    private fun sha256(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toHexString()

    private fun manifest(): JsonObject = Json.parseToJsonElement(Files.readString(visual.resolve("visual.json"))).jsonObject

    @Test
    fun `overlays, crops and the manifest are written under the report's visual folder of the baseline`() =
        runBlocking<Unit> {
            val look = CompareLooks(store, codec).compare(inputs(), report).single()

            look.change shouldBe LookChange.CHANGED
            visual.resolve("visual.json").shouldExist()
            visual.resolve("$name.png").shouldExist()
            listOf("before", "after", "diff").forEach { visual.resolve("$name-r1-$it.png").shouldExist() }
            look.files?.overlay shouldBe "visual/run_base/$name.png"
            look.files?.before shouldBe "../../run_base/a01/0001-visual.png"
            look.files?.after shouldBe "../a01/0002-visual.png"
            look.files
                ?.crops
                ?.single()
                ?.diff shouldBe "visual/run_base/$name-r1-diff.png"
            codec.decode(Files.readAllBytes(visual.resolve("$name.png"))).let { (it.width to it.height) shouldBe (60 to 80) }
            val files = checkNotNull(look.files)
            report.resolve(checkNotNull(files.before)).normalize().shouldExist()
            report.resolve(checkNotNull(files.after)).normalize().shouldExist()
        }

    @Test
    fun `the manifest names every input by artifact id and its verified sha256`() =
        runBlocking<Unit> {
            val inputs = inputs()
            val frames = (inputs.baseline + inputs.current).flatMap { it.artifacts }

            CompareLooks(store, codec).compare(inputs, report)

            val manifest = manifest()
            manifest["algorithm"]?.jsonPrimitive?.content shouldBe VisualThresholds.ALGORITHM
            manifest["inputs"]!!.jsonArray.map { input ->
                val o = input.jsonObject
                listOf("artifactId", "recorded", "found").map { o[it]!!.jsonPrimitive.content }
            } shouldContainExactlyInAnyOrder frames.map { listOf(it.artifactId.value, it.sha256, it.sha256) }
            val before =
                manifest["looks"]!!
                    .jsonArray
                    .single()
                    .jsonObject["before"]!!
                    .jsonObject
            before["artifactId"]!!.jsonPrimitive.content shouldBe frames[0].artifactId.value
            before["sha256"]!!.jsonPrimitive.content shouldBe frames[0].sha256
            before["verified"]!!.jsonPrimitive.boolean shouldBe true
            before["samples"]!!
                .jsonArray
                .single()
                .jsonObject["frames"]!!
                .jsonArray
                .map { it.jsonPrimitive.content } shouldContainExactly
                listOf(frames[0].artifactId.value)
            manifest["files"]!!.jsonArray.forEach { file ->
                val o = file.jsonObject
                sha256(visual.resolve(o["name"]!!.jsonPrimitive.content)) shouldBe o["sha256"]!!.jsonPrimitive.content
            }
        }

    @Test
    fun `a second comparison of the same inputs decodes nothing`() =
        runBlocking<Unit> {
            val inputs = inputs()
            val first = CompareLooks(store, codec).compare(inputs, report)
            codec.decodes.get() shouldBeGreaterThan 0
            codec.decodes.set(0)

            val second = CompareLooks(store, codec).compare(inputs, report)

            codec.decodes.get() shouldBe 0
            second shouldBe first
        }

    @Test
    fun `files of an earlier computation not in the new manifest are removed`() =
        runBlocking<Unit> {
            val inputs = inputs()
            CompareLooks(store, codec).compare(inputs, report)
            visual.resolve("$name.png").shouldExist()

            // Regions of a thousand cells: the same change no longer counts, so no picture is derived.
            val look = CompareLooks(store, codec, VisualThresholds(regionCells = 1_000)).compare(inputs, report).single()

            look.change shouldBe LookChange.UNCHANGED
            visual.resolve("$name.png").shouldNotExist()
            visual.listDirectoryEntries().map { it.name } shouldContainExactly listOf("visual.json")
        }

    @Test
    fun `two comparisons at once leave one consistent manifest`() =
        runBlocking<Unit> {
            val inputs = inputs()
            val compare = CompareLooks(store, codec)

            val results =
                coroutineScope {
                    List(2) { async(Dispatchers.Default) { compare.compare(inputs, report) } }.awaitAll()
                }

            results[0] shouldBe results[1]
            val listed = manifest()["files"]!!.jsonArray.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject }
            visual.listDirectoryEntries().map { it.name } shouldContainExactlyInAnyOrder listed.keys + "visual.json"
            listed.forEach { (file, entry) -> sha256(visual.resolve(file)) shouldBe entry["sha256"]!!.jsonPrimitive.content }
        }

    @Test
    fun `what moved within one load is not compared`() =
        runBlocking<Unit> {
            // The block changes colour between two frames of the current run's load: it moves by itself. The page is
            // tall enough for the moving part to stay under the kept-moving limit.
            val tall = page(60, 200)
            val red = tall.painted(10, 20, 30, 20, RED)
            val blue = tall.painted(10, 20, 30, 20, BLUE)
            val inputs = CompareLooks.Inputs(listOf(looked(base, red)), listOf(looked(now, blue, moved = red)))

            val look = CompareLooks(store, codec).compare(inputs, report).single()

            look.change shouldBe LookChange.UNCHANGED
            (look.ignored[IgnoredBy.MOVING] ?: 0) shouldBeGreaterThan 0
        }

    @Test
    fun `a frame changed or lost after it was recorded is altered evidence or no image`() =
        runBlocking<Unit> {
            val inputs = inputs()
            val after =
                inputs.current
                    .single()
                    .artifacts
                    .single()
            Files.write(store.resolve(after), codec.encode(page))

            val altered = CompareLooks(store, codec).compare(inputs, report).single()
            Files.delete(store.resolve(after))
            val missing = CompareLooks(store, codec).compare(inputs, report).single()

            altered.change shouldBe LookChange.NOT_COMPARABLE
            altered.reason shouldBe LookReason.EVIDENCE_ALTERED
            altered.before?.verified shouldBe true
            altered.after?.verified shouldBe false
            missing.reason shouldBe LookReason.NO_IMAGE
        }
}

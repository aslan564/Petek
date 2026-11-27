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

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.evidence.domain.ArtifactRecord
import az.petek.evidence.domain.ArtifactStore
import az.petek.evidence.domain.LookFrame
import az.petek.evidence.domain.LookFrameKind
import az.petek.evidence.domain.LookMaskReason
import az.petek.evidence.domain.PageLookRecord
import az.petek.evidence.domain.RunRecord
import az.petek.reporting.domain.visual.CapturePair
import az.petek.reporting.domain.visual.CellMask
import az.petek.reporting.domain.visual.FrameCheck
import az.petek.reporting.domain.visual.IgnoredPixels
import az.petek.reporting.domain.visual.LookChange
import az.petek.reporting.domain.visual.LookComparison
import az.petek.reporting.domain.visual.LookCrop
import az.petek.reporting.domain.visual.LookCrops
import az.petek.reporting.domain.visual.LookFiles
import az.petek.reporting.domain.visual.LookJudge
import az.petek.reporting.domain.visual.LookKey
import az.petek.reporting.domain.visual.LookOverlay
import az.petek.reporting.domain.visual.LookPairing
import az.petek.reporting.domain.visual.LookPlan
import az.petek.reporting.domain.visual.LookReason
import az.petek.reporting.domain.visual.LookRunSamples
import az.petek.reporting.domain.visual.LookSide
import az.petek.reporting.domain.visual.MaskSuggestions
import az.petek.reporting.domain.visual.NoiseMap
import az.petek.reporting.domain.visual.PixelDiff
import az.petek.reporting.domain.visual.PixelMask
import az.petek.reporting.domain.visual.Raster
import az.petek.reporting.domain.visual.RasterCodec
import az.petek.reporting.domain.visual.RowAlignment
import az.petek.reporting.domain.visual.SideSamples
import az.petek.reporting.domain.visual.VisualThresholds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Compares the page looks of two runs (`site_health`'s `look`, docs/adr/0014) and derives the pictures that show what
 * changed, under `report/visual/<baseline run>/` of the current run's report:
 *
 * 1. [LookPairing] pairs the looks by step, page and screen and chooses each side's samples.
 * 2. The inputs (every chosen sample's record and its frames' recorded hashes, the algorithm and [thresholds]) are
 *    digested. When `visual.json` there has the same digest, every frame it read still hashes as it did and every
 *    picture it lists is intact, its looks are returned as they are: nothing is decoded.
 * 3. Otherwise each look is computed, two at a time on [cpu], holding a few decoded captures at once: the
 *    representatives' frames are verified against their recorded hashes, each sample's moving map and each side's
 *    peer map are built, the representatives are aligned and compared, the other pairs of samples only when the verdict
 *    turns on them ([LookJudge.needsOtherPairs]), and a changed look gets its difference picture and crops.
 * 4. Every picture is written to a temporary file and moved into place; `visual.json` is written last, and files of an
 *    earlier computation it no longer lists are removed. One comparison at a time per directory in this process.
 *
 * The frames are linked where the evidence keeps them, never copied; nothing is written to the database.
 */
class CompareLooks(
    private val artifacts: ArtifactStore,
    private val codec: RasterCodec,
    val thresholds: VisualThresholds = VisualThresholds(),
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Both sides' runs, each compared run first and then its repeat siblings, and the steps the scenario added or removed. */
    data class Inputs(
        val baseline: List<LookRun>,
        val current: List<LookRun>,
        val added: Set<String> = emptySet(),
        val removed: Set<String> = emptySet(),
    )

    /** One run's looks and artifacts (only its [az.petek.evidence.domain.ArtifactType.VISUAL] ones are read). */
    data class LookRun(
        val run: RunRecord,
        val looks: List<PageLookRecord>,
        val artifacts: List<ArtifactRecord>,
    )

    /** The looks of [inputs], decided; their pictures are written under [reportDirectory] (the current run's report). */
    suspend fun compare(
        inputs: Inputs,
        reportDirectory: Path,
    ): List<LookComparison> {
        val plans =
            LookPairing.plan(
                inputs.baseline.map { LookRunSamples(it.run, it.looks) },
                inputs.current.map { LookRunSamples(it.run, it.looks) },
                inputs.added,
                inputs.removed,
                thresholds.maxSamples,
            )
        if (plans.isEmpty()) return emptyList()
        val baseline = inputs.baseline.first().run
        val current = inputs.current.first().run
        val records = (inputs.baseline + inputs.current).flatMap { it.artifacts }.associateBy { it.artifactId }
        val directory = reportDirectory.resolve(ReportLayout.VISUAL).resolve(baseline.runId.value)
        val digest = digest(plans, records, baseline.runId, current.runId)
        val lock = LOCKS.computeIfAbsent(directory.toAbsolutePath().normalize()) { Mutex() }
        return lock.withLock {
            cached(directory, digest, records) ?: computed(plans, Job(directory, records, baseline.runId, current.runId), digest)
        }
    }

    private suspend fun computed(
        plans: List<LookPlan>,
        job: Job,
        digest: String,
    ): List<LookComparison> {
        val permits = Semaphore(PARALLEL_LOOKS)
        val results = coroutineScope { plans.map { plan -> async(cpu) { permits.withPermit { look(plan, job) } } }.awaitAll() }
        val manifest =
            VisualManifest(
                algorithm = VisualThresholds.ALGORITHM,
                thresholds = ThresholdsDto.of(thresholds),
                baselineRunId = job.baseline.value,
                currentRunId = job.current.value,
                inputsDigest = digest,
                inputs = job.read.values.sortedWith(compareBy({ it.runId }, { it.artifactId })),
                looks = results.map { LookDto.of(it.comparison, it.before, it.after) },
                files = results.flatMap { it.outputs }.sortedBy { it.name },
            )
        withContext(io) {
            write(job.directory, VisualManifest.FILE, MANIFEST_JSON.encodeToString(VisualManifest.serializer(), manifest).toByteArray())
            val keep = manifest.files.mapTo(HashSet()) { it.name } + VisualManifest.FILE
            job.directory
                .listDirectoryEntries()
                .filter { it.isRegularFile() && it.name !in keep }
                .forEach(Files::deleteIfExists)
        }
        return results.map { it.comparison }
    }

    /** One look, from its plan to its verdict and pictures. */
    private suspend fun look(
        plan: LookPlan,
        job: Job,
    ): Computed {
        val before = plan.before
        val after = plan.after
        if (plan.decided != null || before == null || after == null) {
            return Computed(
                base(plan, job, false, false).copy(change = plan.decided ?: LookChange.NOT_COMPARABLE, reason = plan.reason),
                plan,
            )
        }
        val checkBefore = verify(before.representative, job)
        val checkAfter = verify(after.representative, job)
        val facts = LookJudge.facts(before.representative, after.representative, thresholds)
        val refused = LookJudge.refusal(worst(checkBefore, checkAfter), plan.mismatch)
        val verified = base(plan, job, checkBefore == FrameCheck.OK, checkAfter == FrameCheck.OK)
        if (refused != null) return Computed(verified.copy(change = LookChange.NOT_COMPARABLE, reason = refused, facts = facts), plan)
        return try {
            compared(plan, before, after, verified, job)
        } catch (unusable: Unusable) {
            Computed(verified.copy(change = LookChange.NOT_COMPARABLE, reason = unusable.reason, facts = facts), plan)
        }
    }

    private suspend fun compared(
        plan: LookPlan,
        before: SideSamples,
        after: SideSamples,
        base: LookComparison,
        job: Job,
    ): Computed {
        val t = thresholds
        val b = sample(before.representative, decode(main(before.representative), job), job)
        val a = sample(after.representative, decode(main(after.representative), job), job)
        val peersBefore = peers(before, b, job)
        val peersAfter = peers(after, a, job)
        val noiseBefore = b.moving.or(peersBefore.noise)
        val noiseAfter = a.moving.or(peersAfter.noise)
        val pair = pairOf(b, a)
        val masksAfter = masks(a.record, pair.after)
        val ignoreBefore = masks(b.record, pair.before).with(noiseBefore)
        val ignoreAfter = masksAfter.with(noiseAfter)
        val alignment = RowAlignment.align(pair.before, pair.after, ignoreBefore, ignoreAfter)
        val diff =
            PixelDiff.compare(
                pair.before,
                pair.after,
                alignment,
                ignoreBefore,
                ignoreAfter,
                runTexts(b.record),
                runTexts(a.record),
                pair.beforeCapped,
                pair.afterCapped,
                t,
            )
        var input =
            LookJudge.Input(
                before = b.record,
                after = a.record,
                changed = diff.changed,
                movingBefore = noiseBefore.share(),
                movingAfter = noiseAfter.share(),
                ignoredBefore = share(ignoreBefore),
                ignoredAfter = share(ignoreAfter),
            )
        var pairs = 1
        if (LookJudge.needsOtherPairs(input, t)) {
            val (consistent, compared) = otherPairs(listOf(b) + peersBefore.samples, listOf(a) + peersAfter.samples, job)
            pairs += compared
            input = input.copy(othersChanged = consistent)
        }
        val decision = LookJudge.decide(input, t)
        val samplesBefore = listOf(b.record) + peersBefore.samples.map { it.record }
        val samplesAfter = listOf(a.record) + peersAfter.samples.map { it.record }
        val look =
            base.copy(
                change = decision.change,
                reason = decision.reason,
                reasonShare = decision.share,
                facts = decision.facts,
                before = base.before?.copy(samples = samplesBefore.size),
                after = base.after?.copy(samples = samplesAfter.size),
                regions = diff.regions,
                bands = diff.bands,
                comparedPixels = diff.comparedPixels,
                changedPixels = diff.changedPixels,
                differingPixels = diff.differingPixels,
                capturedPixels = pair.after.width.toLong() * pair.after.height,
                ignored =
                    IgnoredPixels.byReason(
                        pair.after.width,
                        pair.after.height,
                        main(a.record).masks,
                        t.halo,
                        a.moving,
                        peersAfter.noise,
                    ),
                pairs = pairs,
            )
        if (decision.change != LookChange.CHANGED) return Computed(look, plan, samplesBefore, samplesAfter)
        val suggestions = MaskSuggestions.of(diff.regions, a.record.anchors)
        // The difference picture and the crops of the largest counted areas, for a changed look only.
        val overlay = LookOverlay.render(pair.after, diff, masksAfter, noiseAfter)
        val name = String.format(Locale.ROOT, "%03d-%s", plan.index, slug(plan.key))
        val outputs = mutableListOf(write(job, "$name.png", codec.encode(overlay)))
        val crops =
            LookCrops.windows(diff, alignment, pair.before.width, pair.before.height).map { window ->
                val crop = LookCrops.cut(pair.before, pair.after, overlay, window)
                val prefix = "$name-r${window.number}"
                outputs += write(job, "$prefix-before.png", codec.encode(crop.before))
                outputs += write(job, "$prefix-after.png", codec.encode(crop.after))
                outputs += write(job, "$prefix-diff.png", codec.encode(crop.diff))
                LookCrop(
                    window.number,
                    window.box,
                    job.link("$prefix-before.png"),
                    job.link("$prefix-after.png"),
                    job.link("$prefix-diff.png"),
                )
            }
        val files = (look.files ?: LookFiles(null, null, null)).copy(overlay = job.link("$name.png"), crops = crops)
        return Computed(look.copy(suggestions = suggestions, files = files), plan, samplesBefore, samplesAfter, outputs)
    }

    /**
     * Every other pair of samples (each with its own masks and moving map), until one shows no change: the change
     * must be seen in all of them. Returns whether it was, and how many pairs were compared.
     */
    private suspend fun otherPairs(
        before: List<Sample>,
        after: List<Sample>,
        job: Job,
    ): Pair<Boolean, Int> {
        var compared = 0
        before.forEachIndexed { i, b ->
            val beforeRaster = if (i == 0) b.raster else decodeOrNull(main(b.record), job) ?: return@forEachIndexed
            after.forEachIndexed { j, a ->
                if (i == 0 && j == 0) return@forEachIndexed
                val afterRaster = if (j == 0) a.raster else decodeOrNull(main(a.record), job) ?: return@forEachIndexed
                val pair =
                    CapturePair.of(
                        beforeRaster,
                        b.record.pageHeight,
                        b.record.maxHeight,
                        afterRaster,
                        a.record.pageHeight,
                        a.record.maxHeight,
                    )
                val ignoreBefore = masks(b.record, pair.before).with(b.moving)
                val ignoreAfter = masks(a.record, pair.after).with(a.moving)
                val alignment = RowAlignment.align(pair.before, pair.after, ignoreBefore, ignoreAfter)
                val diff =
                    PixelDiff.compare(
                        pair.before,
                        pair.after,
                        alignment,
                        ignoreBefore,
                        ignoreAfter,
                        runTexts(b.record),
                        runTexts(a.record),
                        pair.beforeCapped,
                        pair.afterCapped,
                        thresholds,
                    )
                compared++
                if (!diff.changed) return false to compared
            }
        }
        return true to compared
    }

    /** A side's peers that could be read, and where they differ from its representative (its coordinates). */
    private suspend fun peers(
        side: SideSamples,
        representative: Sample,
        job: Job,
    ): Peers {
        val main = representative.raster
        var noise = CellMask(main.width, main.height, thresholds.cell)
        val samples = mutableListOf<Sample>()
        val ignoreMain = masks(representative.record, main).with(representative.moving)
        side.peers.forEach { peer ->
            val sample =
                try {
                    sample(peer, decode(main(peer), job), job)
                } catch (_: Unusable) {
                    return@forEach
                }
            val ignorePeer = masks(peer, sample.raster).with(sample.moving)
            noise = noise.or(NoiseMap.between(main, sample.raster, ignoreMain, ignorePeer, thresholds))
            samples += sample.released()
        }
        return Peers(noise, samples)
    }

    private fun pairOf(
        b: Sample,
        a: Sample,
    ) = CapturePair.of(b.raster, b.record.pageHeight, b.record.maxHeight, a.raster, a.record.pageHeight, a.record.maxHeight)

    /** The record's main-frame masks, grown by the halo, over [raster]. */
    private fun masks(
        record: PageLookRecord,
        raster: Raster,
    ) = PixelMask.of(raster.width, raster.height, main(record).masks.map { it.box }, thresholds.halo)

    private fun share(mask: PixelMask): Double =
        if (mask.width == 0 || mask.height == 0) 0.0 else mask.count().toDouble() / (mask.width.toLong() * mask.height)

    /** The base of a look's comparison: its key and both sides' representatives, linked where the evidence keeps them. */
    private fun base(
        plan: LookPlan,
        job: Job,
        verifiedBefore: Boolean,
        verifiedAfter: Boolean,
    ): LookComparison {
        val before = plan.before?.let { side(it, verifiedBefore, job) }
        val after = plan.after?.let { side(it, verifiedAfter, job) }
        val links =
            LookFiles(null, plan.before?.let { job.frameLink(it.representative) }, plan.after?.let { job.frameLink(it.representative) })
        return LookComparison(
            plan.index,
            plan.key,
            plan.decided ?: LookChange.NOT_COMPARABLE,
            before = before,
            after = after,
            files = links,
        )
    }

    private fun side(
        samples: SideSamples,
        verified: Boolean,
        job: Job,
    ): LookSide {
        val record = samples.representative
        val frame = record.frames.firstOrNull { it.kind == LookFrameKind.MAIN } ?: record.frames.firstOrNull()
        val artifact = frame?.let { job.records[it.artifactId] }
        return LookSide(
            record.runId,
            record.agentId,
            record.stepId,
            frame?.artifactId ?: ArtifactId(""),
            artifact?.relativePath.orEmpty(),
            artifact?.sha256.orEmpty(),
            verified,
            record.renderer,
            record.viewportWidth,
            record.viewportHeight,
            record.pageHeight,
            record.landedPath,
            record.status,
            record.settled,
            samples.all.size,
        )
    }

    /** Every frame of a representative read and checked against its recorded hash: altered first, then missing. */
    private suspend fun verify(
        record: PageLookRecord,
        job: Job,
    ): FrameCheck {
        if (record.frames.isEmpty()) return FrameCheck.MISSING
        val checks =
            record.frames.map { frame ->
                try {
                    read(frame, job)
                    FrameCheck.OK
                } catch (unusable: Unusable) {
                    if (unusable.reason == LookReason.EVIDENCE_ALTERED) FrameCheck.ALTERED else FrameCheck.MISSING
                }
            }
        return checks.reduce(::worst)
    }

    private fun worst(
        a: FrameCheck,
        b: FrameCheck,
    ): FrameCheck =
        when {
            a == FrameCheck.ALTERED || b == FrameCheck.ALTERED -> FrameCheck.ALTERED
            a == FrameCheck.MISSING || b == FrameCheck.MISSING -> FrameCheck.MISSING
            else -> FrameCheck.OK
        }

    /** The frame's bytes, read on [io] and checked against the recorded hash; what was found is kept for the manifest. */
    private suspend fun read(
        frame: LookFrame,
        job: Job,
    ): ByteArray {
        val record = job.records[frame.artifactId] ?: throw Unusable(LookReason.NO_IMAGE)
        val bytes = withContext(io) { bytesOf(record) }
        val found = bytes?.let(::sha256)
        job.read[record.artifactId] = InputFileDto(record.runId.value, record.artifactId.value, record.relativePath, record.sha256, found)
        return when {
            bytes == null -> throw Unusable(LookReason.NO_IMAGE)
            found != record.sha256 -> throw Unusable(LookReason.EVIDENCE_ALTERED)
            else -> bytes
        }
    }

    private suspend fun decode(
        frame: LookFrame,
        job: Job,
    ): Raster {
        val bytes = read(frame, job)
        return try {
            codec.decode(bytes)
        } catch (_: Exception) {
            throw Unusable(LookReason.NO_IMAGE)
        }
    }

    private suspend fun decodeOrNull(
        frame: LookFrame,
        job: Job,
    ): Raster? =
        try {
            decode(frame, job)
        } catch (_: Unusable) {
            null
        }

    private fun bytesOf(record: ArtifactRecord): ByteArray? =
        try {
            artifacts.resolve(record).takeIf { it.isRegularFile() }?.let(Files::readAllBytes)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }

    /** The cached looks when `visual.json` matches [digest], its inputs are as they were and its pictures intact. */
    private suspend fun cached(
        directory: Path,
        digest: String,
        records: Map<ArtifactId, ArtifactRecord>,
    ): List<LookComparison>? =
        withContext(io) {
            val manifest =
                try {
                    MANIFEST_JSON.decodeFromString(VisualManifest.serializer(), Files.readString(directory.resolve(VisualManifest.FILE)))
                } catch (_: IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            if (manifest == null || manifest.algorithm != VisualThresholds.ALGORITHM || manifest.inputsDigest != digest) {
                return@withContext null
            }
            val inputsAsRead =
                manifest.inputs.all { input ->
                    val record = records[ArtifactId(input.artifactId)] ?: return@all false
                    bytesOf(record)?.let(::sha256) == input.found
                }
            val outputsIntact =
                manifest.files.all { file ->
                    val path = directory.resolve(file.name)
                    path.isRegularFile() && sha256(Files.readAllBytes(path)) == file.sha256
                }
            if (!inputsAsRead || !outputsIntact) return@withContext null
            try {
                manifest.looks.map { it.toComparison() }
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    /** What the comparison depends on, hashed: a change of any record, frame hash, threshold or algorithm recomputes. */
    private fun digest(
        plans: List<LookPlan>,
        records: Map<ArtifactId, ArtifactRecord>,
        baseline: RunId,
        current: RunId,
    ): String {
        fun sample(record: PageLookRecord) =
            SampleDigest(
                runId = record.runId.value,
                stepId = record.stepId.value,
                agentId = record.agentId.value,
                scenarioStep = record.scenarioStep,
                page = record.page,
                device = record.device,
                landedPath = record.landedPath,
                status = record.status,
                viewportWidth = record.viewportWidth,
                viewportHeight = record.viewportHeight,
                pageHeight = record.pageHeight,
                maxHeight = record.maxHeight,
                testers = record.testers,
                renderer = record.renderer,
                settled = record.settled,
                unsettled = record.unsettled,
                fonts = record.fonts,
                frames =
                    record.frames.map { frame ->
                        val artifact = records[frame.artifactId]
                        FrameDigest(
                            frame.artifactId.value,
                            frame.kind.name,
                            frame.width,
                            frame.height,
                            frame.masks.map { MaskDto(BoxDto.of(it.box), it.reason.name, it.source) },
                            artifact?.relativePath,
                            artifact?.sha256,
                        )
                    },
                anchors = record.anchors.map { AnchorDigest(it.selector, BoxDto.of(it.box)) },
                recordedAt = record.recordedAt.toString(),
            )
        val inputs =
            InputsDigest(
                VisualThresholds.ALGORITHM,
                ThresholdsDto.of(thresholds),
                baseline.value,
                current.value,
                plans.map { plan ->
                    PlanDigest(
                        plan.index,
                        plan.key.scenarioStep,
                        plan.key.page,
                        plan.key.device,
                        plan.decided?.name,
                        plan.reason?.name,
                        plan.mismatch?.name,
                        plan.before
                            ?.all
                            .orEmpty()
                            .map(::sample),
                        plan.after
                            ?.all
                            .orEmpty()
                            .map(::sample),
                    )
                },
            )
        return sha256(DIGEST_JSON.encodeToString(InputsDigest.serializer(), inputs).toByteArray())
    }

    private suspend fun write(
        job: Job,
        name: String,
        bytes: ByteArray,
    ): OutputFileDto =
        withContext(io) {
            write(job.directory, name, bytes)
            OutputFileDto(name, sha256(bytes))
        }

    /** One comparison's state: where it writes, the artifacts it may read, and the frames it read. */
    private inner class Job(
        val directory: Path,
        val records: Map<ArtifactId, ArtifactRecord>,
        val baseline: RunId,
        val current: RunId,
    ) {
        val read = ConcurrentHashMap<ArtifactId, InputFileDto>()

        /** A derived picture's link from the report directory. */
        fun link(name: String): String = "${ReportLayout.VISUAL}/${baseline.value}/$name"

        /** A look's main frame, linked where the evidence keeps it. */
        fun frameLink(record: PageLookRecord): String? =
            record.frames
                .firstOrNull { it.kind == LookFrameKind.MAIN }
                ?.let { records[it.artifactId] }
                ?.let { ReportLayout.link(artifacts, current, it) }
    }

    /** A decoded capture of a sample and the cells where it moved by itself within its run. */
    private class Sample(
        val record: PageLookRecord,
        private val decoded: Raster?,
        val moving: CellMask,
    ) {
        val raster: Raster get() = checkNotNull(decoded) { "a peer's capture is decoded again when needed" }

        /** The same sample without its pixels, so a peer holds no capture while others are compared. */
        fun released() = Sample(record, null, moving)
    }

    private class Peers(
        val noise: CellMask,
        val samples: List<Sample>,
    )

    private class Computed(
        val comparison: LookComparison,
        plan: LookPlan,
        val before: List<PageLookRecord> = plan.before?.all.orEmpty(),
        val after: List<PageLookRecord> = plan.after?.all.orEmpty(),
        val outputs: List<OutputFileDto> = emptyList(),
    )

    /** A frame that cannot be used: [reason] is [LookReason.NO_IMAGE] or [LookReason.EVIDENCE_ALTERED]. */
    private class Unusable(
        val reason: LookReason,
    ) : Exception(reason.name, null, false, false)

    /** A decoded capture and its moving map. */
    private suspend fun sample(
        record: PageLookRecord,
        raster: Raster,
        job: Job,
    ): Sample = Sample(record, raster, moving(record, raster, job))

    /** The cells where [record]'s main frame and its other frames (moved, reloaded) differ, in [main]'s coordinates. */
    private suspend fun moving(
        record: PageLookRecord,
        main: Raster,
        job: Job,
    ): CellMask {
        var cells = CellMask(main.width, main.height, thresholds.cell)
        val ignoreMain = masks(record, main)
        val mainMasks = main(record).masks
        record.frames.filter { it.kind != LookFrameKind.MAIN }.forEach { frame ->
            val other = decode(frame, job)
            val ignoreOther = PixelMask.of(other.width, other.height, frame.masks.ifEmpty { mainMasks }.map { it.box }, thresholds.halo)
            cells = cells.or(NoiseMap.between(main, other, ignoreMain, ignoreOther, thresholds))
        }
        return cells
    }

    private fun main(record: PageLookRecord): LookFrame =
        record.frames.firstOrNull { it.kind == LookFrameKind.MAIN } ?: record.frames.firstOrNull() ?: throw Unusable(LookReason.NO_IMAGE)

    private fun runTexts(record: PageLookRecord) = main(record).masks.filter { it.reason == LookMaskReason.RUN_TEXT }.map { it.box }

    internal companion object {
        /** Looks computed at once: each holds a few decoded captures. */
        const val PARALLEL_LOOKS = 2

        /** One comparison at a time per visual directory in this process. */
        private val LOCKS = ConcurrentHashMap<Path, Mutex>()

        private const val MAX_SLUG = 80
        private val NOT_SLUG = Regex("[^a-z0-9]+")

        /** `<step>-<page>-<device>` in `[a-z0-9-]`, the page `/` as `home`, at most 80 characters. */
        fun slug(key: LookKey): String {
            fun part(text: String) = text.lowercase(Locale.ROOT).replace(NOT_SLUG, "-").trim('-')
            val page = part(key.page).ifEmpty { "home" }
            return listOfNotNull(part(key.scenarioStep).ifEmpty { null }, page, key.device?.let(::part)?.ifEmpty { null })
                .joinToString("-")
                .take(MAX_SLUG)
                .trimEnd('-')
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

        /** Writes [bytes] to a temporary sibling and moves it into place, so a reader never sees half a file. */
        fun write(
            directory: Path,
            name: String,
            bytes: ByteArray,
        ) {
            Files.createDirectories(directory)
            val target = directory.resolve(name)
            val temporary = directory.resolve("$name.tmp-${UUID.randomUUID()}")
            try {
                Files.write(temporary, bytes)
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}

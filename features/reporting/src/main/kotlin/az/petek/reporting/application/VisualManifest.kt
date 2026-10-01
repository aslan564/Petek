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

import az.petek.core.ids.AgentId
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.evidence.domain.LookBox
import az.petek.evidence.domain.LookMask
import az.petek.evidence.domain.PageLookRecord
import az.petek.reporting.domain.visual.Band
import az.petek.reporting.domain.visual.BandKind
import az.petek.reporting.domain.visual.ChangedRegion
import az.petek.reporting.domain.visual.IgnoredBy
import az.petek.reporting.domain.visual.LookChange
import az.petek.reporting.domain.visual.LookComparison
import az.petek.reporting.domain.visual.LookCrop
import az.petek.reporting.domain.visual.LookFact
import az.petek.reporting.domain.visual.LookFactKind
import az.petek.reporting.domain.visual.LookFiles
import az.petek.reporting.domain.visual.LookKey
import az.petek.reporting.domain.visual.LookReason
import az.petek.reporting.domain.visual.LookSide
import az.petek.reporting.domain.visual.VisualThresholds
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * `report/visual/<baseline run>/visual.json` (docs/adr/0014): how a comparison's looks were decided and from what.
 * It names the algorithm and its thresholds, both runs, every input frame by artifact id with its recorded and found
 * hashes, each look's verdict with both sides' provenance (the samples used, the masks as recorded), and every derived
 * picture with its hash. It is also the cache: the same inputs, unchanged files and intact outputs are read back
 * instead of decoding anything again. Compare writes nothing to the database.
 */

internal val MANIFEST_JSON =
    Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

/** The digest's own encoding: compact, every field written, so equal inputs always give equal bytes. */
internal val DIGEST_JSON = Json { encodeDefaults = true }

@Serializable
internal data class VisualManifest(
    val algorithm: String,
    val thresholds: ThresholdsDto,
    val baselineRunId: String,
    val currentRunId: String,
    val inputsDigest: String,
    val inputs: List<InputFileDto>,
    val looks: List<LookDto>,
    val files: List<OutputFileDto>,
) {
    companion object {
        const val FILE = "visual.json"
    }
}

@Serializable
internal data class ThresholdsDto(
    val colorDelta: Double,
    val shiftRadius: Int,
    val cell: Int,
    val cellPixels: Int,
    val regionCells: Int,
    val bandRows: Int,
    val halo: Int,
    val noiseCellPixels: Int,
    val noiseGrow: Int,
    val movingLimit: Double,
    val ignoredLimit: Double,
    val heightFact: Int,
    val maxSamples: Int,
) {
    companion object {
        fun of(t: VisualThresholds) =
            ThresholdsDto(
                t.colorDelta,
                t.shiftRadius,
                t.cell,
                t.cellPixels,
                t.regionCells,
                t.bandRows,
                t.halo,
                t.noiseCellPixels,
                t.noiseGrow,
                t.movingLimit,
                t.ignoredLimit,
                t.heightFact,
                t.maxSamples,
            )
    }
}

/** A frame the comparison read: its [recorded] hash and the one [found] on disk (null: missing). */
@Serializable
internal data class InputFileDto(
    val runId: String,
    val artifactId: String,
    val path: String,
    val recorded: String,
    val found: String?,
)

/** A derived picture in the manifest's directory. */
@Serializable
internal data class OutputFileDto(
    val name: String,
    val sha256: String,
)

@Serializable
internal data class FactDto(
    val kind: String,
    val before: String,
    val after: String,
)

@Serializable
internal data class BoxDto(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    fun toBox() = LookBox(x, y, width, height)

    companion object {
        fun of(box: LookBox) = BoxDto(box.x, box.y, box.width, box.height)
    }
}

@Serializable
internal data class RegionDto(
    val box: BoxDto,
    val pixels: Long,
    val cells: Int,
    val runContent: Boolean,
)

@Serializable
internal data class BandDto(
    val kind: String,
    val y: Int,
    val height: Int,
    val at: Int,
    val liveRows: Int,
    val runContent: Boolean,
    val cutByLimit: Boolean,
    val counted: Boolean,
)

@Serializable
internal data class MaskDto(
    val box: BoxDto,
    val reason: String,
    val source: String,
)

/** A capture used as a sample: its run, tester, look sub-action and frames. */
@Serializable
internal data class SampleDto(
    val runId: String,
    val agentId: String,
    val stepId: String,
    val frames: List<String>,
)

@Serializable
internal data class SideDto(
    val runId: String,
    val agentId: String,
    val stepId: String,
    val artifactId: String,
    val path: String,
    val sha256: String,
    val verified: Boolean,
    val renderer: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val pageHeight: Int,
    val landedPath: String,
    val status: Int?,
    val settled: Boolean,
    /** The representative first. */
    val samples: List<SampleDto>,
    /** The representative's main frame's masks, as recorded. */
    val masks: List<MaskDto>,
)

@Serializable
internal data class CropDto(
    val number: Int,
    val box: BoxDto,
    val before: String,
    val after: String,
    val diff: String,
)

@Serializable
internal data class FilesDto(
    val overlay: String?,
    val before: String?,
    val after: String?,
    val crops: List<CropDto>,
)

@Serializable
internal data class LookDto(
    val index: Int,
    val step: String,
    val page: String,
    val device: String?,
    val change: String,
    val reason: String?,
    val reasonShare: Double?,
    val facts: List<FactDto>,
    val before: SideDto?,
    val after: SideDto?,
    val regions: List<RegionDto>,
    val bands: List<BandDto>,
    val comparedPixels: Long,
    val changedPixels: Long,
    val differingPixels: Long,
    val capturedPixels: Long,
    val ignored: Map<String, Long>,
    val pairs: Int,
    val suggestions: List<String>,
    val files: FilesDto?,
) {
    fun toComparison(): LookComparison =
        LookComparison(
            index = index,
            key = LookKey(step, page, device),
            change = LookChange.valueOf(change),
            reason = reason?.let(LookReason::valueOf),
            reasonShare = reasonShare,
            facts = facts.map { LookFact(LookFactKind.valueOf(it.kind), it.before, it.after) },
            before = before?.toSide(),
            after = after?.toSide(),
            regions = regions.map { ChangedRegion(it.box.toBox(), it.pixels, it.cells, it.runContent) },
            bands =
                bands.map {
                    Band(BandKind.valueOf(it.kind), it.y, it.height, it.at, it.liveRows, it.runContent, it.cutByLimit, it.counted)
                },
            comparedPixels = comparedPixels,
            changedPixels = changedPixels,
            differingPixels = differingPixels,
            capturedPixels = capturedPixels,
            ignored = ignored.mapKeys { IgnoredBy.valueOf(it.key) },
            pairs = pairs,
            suggestions = suggestions,
            files =
                files?.let { f ->
                    LookFiles(
                        f.overlay,
                        f.before,
                        f.after,
                        f.crops.map { LookCrop(it.number, it.box.toBox(), it.before, it.after, it.diff) },
                    )
                },
        )

    private fun SideDto.toSide() =
        LookSide(
            RunId(runId),
            AgentId(agentId),
            StepId(stepId),
            ArtifactId(artifactId),
            path,
            sha256,
            verified,
            renderer,
            viewportWidth,
            viewportHeight,
            pageHeight,
            landedPath,
            status,
            settled,
            samples.size,
        )

    companion object {
        /** [look] with its sides' samples ([before] and [after], representative first) as recorded. */
        fun of(
            look: LookComparison,
            before: List<PageLookRecord>,
            after: List<PageLookRecord>,
        ) = LookDto(
            index = look.index,
            step = look.key.scenarioStep,
            page = look.key.page,
            device = look.key.device,
            change = look.change.name,
            reason = look.reason?.name,
            reasonShare = look.reasonShare,
            facts = look.facts.map { FactDto(it.kind.name, it.before, it.after) },
            before = look.before?.let { side(it, before) },
            after = look.after?.let { side(it, after) },
            regions = look.regions.map { RegionDto(BoxDto.of(it.box), it.pixels, it.cells, it.runContent) },
            bands = look.bands.map { BandDto(it.kind.name, it.y, it.height, it.at, it.liveRows, it.runContent, it.cutByLimit, it.counted) },
            comparedPixels = look.comparedPixels,
            changedPixels = look.changedPixels,
            differingPixels = look.differingPixels,
            capturedPixels = look.capturedPixels,
            ignored = look.ignored.mapKeys { it.key.name },
            pairs = look.pairs,
            suggestions = look.suggestions,
            files =
                look.files?.let { f ->
                    FilesDto(
                        f.overlay,
                        f.before,
                        f.after,
                        f.crops.map { CropDto(it.number, BoxDto.of(it.box), it.before, it.after, it.diff) },
                    )
                },
        )

        private fun side(
            side: LookSide,
            samples: List<PageLookRecord>,
        ) = SideDto(
            runId = side.runId.value,
            agentId = side.agentId.value,
            stepId = side.stepId.value,
            artifactId = side.artifactId.value,
            path = side.relativePath,
            sha256 = side.sha256,
            verified = side.verified,
            renderer = side.renderer,
            viewportWidth = side.viewportWidth,
            viewportHeight = side.viewportHeight,
            pageHeight = side.pageHeight,
            landedPath = side.landedPath,
            status = side.status,
            settled = side.settled,
            samples =
                samples.map {
                    SampleDto(
                        it.runId.value,
                        it.agentId.value,
                        it.stepId.value,
                        it.frames.map { f ->
                            f.artifactId.value
                        },
                    )
                },
            masks =
                samples
                    .firstOrNull()
                    ?.frames
                    ?.firstOrNull()
                    ?.masks
                    .orEmpty()
                    .map(::mask),
        )

        private fun mask(mask: LookMask) = MaskDto(BoxDto.of(mask.box), mask.reason.name, mask.source)
    }
}

/** What the digest covers about one sample: its whole record and its frames' recorded hashes and paths. */
@Serializable
internal data class SampleDigest(
    val runId: String,
    val stepId: String,
    val agentId: String,
    val scenarioStep: String,
    val page: String,
    val device: String?,
    val landedPath: String,
    val status: Int?,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val pageHeight: Int,
    val maxHeight: Int,
    val testers: Int,
    val renderer: String,
    val settled: Boolean,
    val unsettled: List<String>,
    val fonts: List<String>,
    val frames: List<FrameDigest>,
    val anchors: List<AnchorDigest>,
    val recordedAt: String,
)

@Serializable
internal data class FrameDigest(
    val artifactId: String,
    val kind: String,
    val width: Int,
    val height: Int,
    val masks: List<MaskDto>,
    val path: String?,
    val sha256: String?,
)

@Serializable
internal data class AnchorDigest(
    val selector: String,
    val box: BoxDto,
)

@Serializable
internal data class PlanDigest(
    val index: Int,
    val step: String,
    val page: String,
    val device: String?,
    val decided: String?,
    val reason: String?,
    val mismatch: String?,
    val before: List<SampleDigest>,
    val after: List<SampleDigest>,
)

@Serializable
internal data class InputsDigest(
    val algorithm: String,
    val thresholds: ThresholdsDto,
    val baselineRunId: String,
    val currentRunId: String,
    val plans: List<PlanDigest>,
)

/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.infrastructure

import az.petek.browser.domain.RealtimeTransport
import az.petek.core.ids.ArtifactId
import az.petek.core.ids.FindingId
import az.petek.explorer.domain.ActionKind
import az.petek.explorer.domain.ActionModel
import az.petek.explorer.domain.CoveredIdea
import az.petek.explorer.domain.EventHeader
import az.petek.explorer.domain.ExplorationBudget
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationFinding
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.ExplorationPhase
import az.petek.explorer.domain.ExplorationRequest
import az.petek.explorer.domain.ExplorationStatus
import az.petek.explorer.domain.ExplorationSummary
import az.petek.explorer.domain.FieldModel
import az.petek.explorer.domain.FindingKind
import az.petek.explorer.domain.FormModel
import az.petek.explorer.domain.ModelCounts
import az.petek.explorer.domain.PageModel
import az.petek.explorer.domain.Provenance
import az.petek.explorer.domain.RealtimeObservation
import az.petek.explorer.domain.RoleModel
import az.petek.explorer.domain.Severity
import az.petek.explorer.domain.SiteModel
import az.petek.explorer.domain.SkippedIdea
import az.petek.explorer.domain.TestIdea
import az.petek.explorer.domain.TestPattern
import az.petek.explorer.domain.TrialOutcome
import az.petek.explorer.domain.TrialTouch
import az.petek.explorer.domain.Unknown
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/*
 * JSON forms of the explorer's domain for the SQLite columns (the domain itself stays free of serialization).
 * Enums are stored by name and instants as ISO-8601 text, so the stored JSON stays readable with plain tools.
 */

internal val explorationJson: Json =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

@Serializable
internal data class BudgetDto(
    val maxPages: Int,
    val maxMinutes: Int,
    val maxDepth: Int,
)

@Serializable
internal data class RequestDto(
    val target: String,
    val instructions: String?,
    val budget: BudgetDto,
    val phases: List<String>,
    val allowWrites: Boolean,
)

@Serializable
internal data class CountsDto(
    val pages: Int,
    val forms: Int,
    val actions: Int,
    val realtime: Int,
    val unknowns: Int,
    val findings: Int,
)

@Serializable
internal data class SummaryDto(
    val status: String,
    val counts: CountsDto,
    val pagesVisitedByRole: Map<String, Int>,
    val llmCalls: Int,
    val llmAnswersRejected: Int,
    val durationMs: Long,
    val pageBudgetReached: Boolean,
    val phasesRun: List<String>,
    val phasesSkipped: Map<String, String>,
    val notes: List<String>,
)

@Serializable
internal data class FieldDto(
    val label: String,
    val name: String,
    val type: String,
    val required: Boolean,
    val testId: String?,
    val selector: String,
    val options: List<String>,
)

@Serializable
internal data class FormDto(
    val purpose: String,
    val kind: String,
    val fields: List<FieldDto>,
    val submitSelector: String?,
    val method: String,
    val actionPath: String?,
    val provenance: String,
    val evidence: List<String>,
)

@Serializable
internal data class PageDto(
    val id: String,
    val urlPattern: String,
    val title: String,
    val purpose: String,
    val reachableBy: List<String>,
    val forms: List<FormDto>,
    val testIds: List<String>,
    val linkCount: Int,
    val loadMs: Long?,
    val provenance: String,
    val evidence: List<String>,
)

@Serializable
internal data class TrialDto(
    val role: String,
    val outcome: String,
    val marker: String,
    val messages: List<String>,
    val urlPatternAfter: String?,
    val seenLiveBy: List<String>,
    val evidence: List<String>,
)

@Serializable
internal data class ActionDto(
    val id: String,
    val name: String,
    val kind: String,
    val pageId: String,
    val selector: String,
    val allowedRoles: List<String>,
    val forbiddenRoles: List<String>,
    val triggersRealtime: Boolean?,
    val httpMethod: String?,
    val httpPath: String?,
    val trial: TrialDto?,
    val provenance: String,
    val evidence: List<String>,
)

@Serializable
internal data class RoleDto(
    val name: String,
    val anonymous: Boolean,
    val pageIds: List<String>,
    val deniedPatterns: List<String>,
    val provenance: String,
    val evidence: List<String>,
)

@Serializable
internal data class RealtimeDto(
    val transport: String,
    val detail: String,
    val pages: List<String>,
    val roles: List<String>,
    val provenance: String,
    val evidence: List<String>,
)

@Serializable
internal data class UnknownDto(
    val id: String,
    val question: String,
    val context: String,
    val pageId: String?,
    val provenance: String,
    val evidence: List<String>,
)

@Serializable
internal data class ModelDto(
    val version: Int,
    val explorationId: String,
    val target: String,
    val createdAt: String,
    val pages: List<PageDto>,
    val actions: List<ActionDto>,
    val roles: List<RoleDto>,
    val realtime: List<RealtimeDto>,
    val unknowns: List<UnknownDto>,
    val partial: Boolean = false,
)

@Serializable
internal data class FindingDto(
    val id: String,
    val kind: String,
    val severity: String,
    val pageUrl: String,
    val detail: String,
    val role: String,
    val evidence: List<String>,
)

@Serializable
internal data class IdeaDto(
    val pattern: String,
    val actionId: String,
    val rationale: String,
    val priority: Int,
    val roles: List<String>,
)

@Serializable
internal data class CoveredDto(
    val idea: IdeaDto,
    val stepIds: List<String>,
)

@Serializable
internal data class SkippedDto(
    val idea: IdeaDto,
    val reason: String,
)

/** Event payloads; the header (exploration, sequence number, time) lives in columns. */
@Serializable
internal sealed interface EventDto {
    @Serializable
    @SerialName("started")
    data class Started(
        val target: String,
        val phases: List<String>,
        val instructions: String?,
        val budget: BudgetDto,
        val allowWrites: Boolean,
    ) : EventDto

    @Serializable
    @SerialName("phase_started")
    data class PhaseStarted(
        val phase: String,
        val roles: List<String>,
    ) : EventDto

    @Serializable
    @SerialName("phase_skipped")
    data class PhaseSkipped(
        val phase: String,
        val reason: String,
    ) : EventDto

    @Serializable
    @SerialName("page_visited")
    data class PageVisited(
        val role: String,
        val url: String,
        val urlPattern: String,
        val title: String,
        val status: Int?,
        val loadMs: Long?,
        val screenshot: String?,
    ) : EventDto

    @Serializable
    @SerialName("action_discovered")
    data class ActionDiscovered(
        val action: ActionDto,
    ) : EventDto

    @Serializable
    @SerialName("finding_recorded")
    data class FindingRecorded(
        val finding: FindingDto,
    ) : EventDto

    @Serializable
    @SerialName("unknown_raised")
    data class UnknownRaised(
        val unknown: UnknownDto,
    ) : EventDto

    @Serializable
    @SerialName("model_updated")
    data class ModelUpdated(
        val counts: CountsDto,
    ) : EventDto

    @Serializable
    @SerialName("draft_ready")
    data class DraftReady(
        val draftId: String,
        val name: String,
        val covered: Int,
        val skipped: Int,
    ) : EventDto

    @Serializable
    @SerialName("finished")
    data class Finished(
        val summary: SummaryDto,
        val modelVersion: Int?,
    ) : EventDto

    @Serializable
    @SerialName("failed")
    data class Failed(
        val reason: String,
        val modelVersion: Int?,
    ) : EventDto
}

/** Mapping between the domain and its JSON forms. */
internal object ExplorationJsonMapper {
    private val coveredList = ListSerializer(CoveredDto.serializer())
    private val skippedList = ListSerializer(SkippedDto.serializer())
    private val stringList = ListSerializer(String.serializer())

    fun encodeRequest(request: ExplorationRequest): String = explorationJson.encodeToString(RequestDto.serializer(), request.toDto())

    fun decodeRequest(text: String): ExplorationRequest = explorationJson.decodeFromString(RequestDto.serializer(), text).toDomain()

    fun encodeSummary(summary: ExplorationSummary): String = explorationJson.encodeToString(SummaryDto.serializer(), summary.toDto())

    fun decodeSummary(text: String): ExplorationSummary = explorationJson.decodeFromString(SummaryDto.serializer(), text).toDomain()

    fun encodeModel(model: SiteModel): String = explorationJson.encodeToString(ModelDto.serializer(), model.toDto())

    fun decodeModel(text: String): SiteModel = explorationJson.decodeFromString(ModelDto.serializer(), text).toDomain()

    fun encodeEvent(event: ExplorationEvent): String = explorationJson.encodeToString(EventDto.serializer(), event.toDto())

    fun decodeEvent(
        header: EventHeader,
        text: String,
    ): ExplorationEvent = explorationJson.decodeFromString(EventDto.serializer(), text).toDomain(header)

    fun encodeCovered(covered: List<CoveredIdea>): String =
        explorationJson.encodeToString(
            coveredList,
            covered.map {
                CoveredDto(it.idea.toDto(), it.stepIds)
            },
        )

    fun decodeCovered(text: String): List<CoveredIdea> =
        explorationJson.decodeFromString(coveredList, text).map {
            CoveredIdea(it.idea.toDomain(), it.stepIds)
        }

    fun encodeSkipped(skipped: List<SkippedIdea>): String =
        explorationJson.encodeToString(
            skippedList,
            skipped.map {
                SkippedDto(it.idea.toDto(), it.reason)
            },
        )

    fun decodeSkipped(text: String): List<SkippedIdea> =
        explorationJson.decodeFromString(skippedList, text).map {
            SkippedIdea(it.idea.toDomain(), it.reason)
        }

    fun encodeIds(ids: List<ArtifactId>): String = explorationJson.encodeToString(stringList, ids.map { it.value })

    fun decodeIds(text: String): List<ArtifactId> = explorationJson.decodeFromString(stringList, text).map(::ArtifactId)

    // ---- requests and summaries ----

    private fun ExplorationBudget.toDto() = BudgetDto(maxPages, maxMinutes, maxDepth)

    private fun BudgetDto.toDomain() = ExplorationBudget(maxPages, maxMinutes, maxDepth)

    private fun ExplorationRequest.toDto() =
        RequestDto(
            target.toString(),
            instructions,
            budget.toDto(),
            phases.sorted().map {
                it.name
            },
            allowWrites,
        )

    private fun RequestDto.toDomain() =
        ExplorationRequest(
            URI(target),
            instructions,
            budget.toDomain(),
            phases.map { enumValueOf<ExplorationPhase>(it) }.toSet(),
            allowWrites,
        )

    private fun ModelCounts.toDto() = CountsDto(pages, forms, actions, realtime, unknowns, findings)

    private fun CountsDto.toDomain() = ModelCounts(pages, forms, actions, realtime, unknowns, findings)

    private fun ExplorationSummary.toDto() =
        SummaryDto(
            status = status.name,
            counts = counts.toDto(),
            pagesVisitedByRole = pagesVisitedByRole,
            llmCalls = llmCalls,
            llmAnswersRejected = llmAnswersRejected,
            durationMs = durationMs,
            pageBudgetReached = pageBudgetReached,
            phasesRun = phasesRun.map { it.name },
            phasesSkipped = phasesSkipped.mapKeys { it.key.name },
            notes = notes,
        )

    private fun SummaryDto.toDomain() =
        ExplorationSummary(
            status = enumValueOf<ExplorationStatus>(status),
            counts = counts.toDomain(),
            pagesVisitedByRole = pagesVisitedByRole,
            llmCalls = llmCalls,
            llmAnswersRejected = llmAnswersRejected,
            durationMs = durationMs,
            pageBudgetReached = pageBudgetReached,
            phasesRun = phasesRun.map { enumValueOf<ExplorationPhase>(it) },
            phasesSkipped = phasesSkipped.mapKeys { enumValueOf<ExplorationPhase>(it.key) },
            notes = notes,
        )

    // ---- the site model ----

    private fun List<ArtifactId>.ids() = map { it.value }

    private fun List<String>.artifacts() = map(::ArtifactId)

    private fun FieldModel.toDto() = FieldDto(label, name, type, required, testId, selector, options)

    private fun FieldDto.toDomain() = FieldModel(label, name, type, required, testId, selector, options)

    private fun FormModel.toDto() =
        FormDto(
            purpose,
            kind.name,
            fields.map {
                it.toDto()
            },
            submitSelector,
            method,
            actionPath,
            provenance.name,
            evidence.ids(),
        )

    private fun FormDto.toDomain() =
        FormModel(
            purpose,
            enumValueOf<ActionKind>(kind),
            fields.map { it.toDomain() },
            submitSelector,
            method,
            actionPath,
            enumValueOf<Provenance>(provenance),
            evidence.artifacts(),
        )

    private fun PageModel.toDto() =
        PageDto(
            id,
            urlPattern,
            title,
            purpose,
            reachableBy.toList(),
            forms.map { it.toDto() },
            testIds,
            linkCount,
            loadMs,
            provenance.name,
            evidence.ids(),
        )

    private fun PageDto.toDomain() =
        PageModel(
            id,
            urlPattern,
            title,
            purpose,
            reachableBy.toSet(),
            forms.map { it.toDomain() },
            testIds,
            linkCount,
            loadMs,
            enumValueOf<Provenance>(provenance),
            evidence.artifacts(),
        )

    private fun TrialTouch.toDto() = TrialDto(role, outcome.name, marker, messages, urlPatternAfter, seenLiveBy.toList(), evidence.ids())

    private fun TrialDto.toDomain() =
        TrialTouch(role, enumValueOf<TrialOutcome>(outcome), marker, messages, urlPatternAfter, seenLiveBy.toSet(), evidence.artifacts())

    private fun ActionModel.toDto() =
        ActionDto(
            id,
            name,
            kind.name,
            pageId,
            selector,
            allowedRoles.toList(),
            forbiddenRoles.toList(),
            triggersRealtime,
            httpMethod,
            httpPath,
            trial?.toDto(),
            provenance.name,
            evidence.ids(),
        )

    private fun ActionDto.toDomain() =
        ActionModel(
            id,
            name,
            enumValueOf<ActionKind>(kind),
            pageId,
            selector,
            allowedRoles.toSet(),
            forbiddenRoles.toSet(),
            triggersRealtime,
            httpMethod,
            httpPath,
            trial?.toDomain(),
            enumValueOf<Provenance>(provenance),
            evidence.artifacts(),
        )

    private fun RoleModel.toDto() = RoleDto(name, anonymous, pageIds.toList(), deniedPatterns.toList(), provenance.name, evidence.ids())

    private fun RoleDto.toDomain() =
        RoleModel(name, anonymous, pageIds.toSet(), deniedPatterns.toSet(), enumValueOf<Provenance>(provenance), evidence.artifacts())

    private fun RealtimeObservation.toDto() =
        RealtimeDto(transport.name, detail, pages.toList(), roles.toList(), provenance.name, evidence.ids())

    private fun RealtimeDto.toDomain() =
        RealtimeObservation(
            enumValueOf<RealtimeTransport>(transport),
            detail,
            pages.toSet(),
            roles.toSet(),
            enumValueOf<Provenance>(provenance),
            evidence.artifacts(),
        )

    private fun Unknown.toDto() = UnknownDto(id, question, context, pageId, provenance.name, evidence.ids())

    private fun UnknownDto.toDomain() = Unknown(id, question, context, pageId, enumValueOf<Provenance>(provenance), evidence.artifacts())

    private fun SiteModel.toDto() =
        ModelDto(
            version,
            explorationId.value,
            target.toString(),
            InstantText.encode(createdAt),
            pages.map { it.toDto() },
            actions.map { it.toDto() },
            roles.map { it.toDto() },
            realtime.map { it.toDto() },
            unknowns.map { it.toDto() },
            partial,
        )

    private fun ModelDto.toDomain() =
        SiteModel(
            version,
            ExplorationId(explorationId),
            URI(target),
            InstantText.decode(createdAt),
            pages.map { it.toDomain() },
            actions.map { it.toDomain() },
            roles.map { it.toDomain() },
            realtime.map { it.toDomain() },
            unknowns.map { it.toDomain() },
            partial,
        )

    private fun ExplorationFinding.toDto() = FindingDto(id.value, kind.name, severity.name, pageUrl, detail, role, evidence.ids())

    private fun FindingDto.toDomain() =
        ExplorationFinding(
            FindingId(id),
            enumValueOf<FindingKind>(kind),
            enumValueOf<Severity>(severity),
            pageUrl,
            detail,
            role,
            evidence.artifacts(),
        )

    private fun TestIdea.toDto() = IdeaDto(pattern.name, actionId, rationale, priority, roles)

    private fun IdeaDto.toDomain() = TestIdea(enumValueOf<TestPattern>(pattern), actionId, rationale, priority, roles)

    // ---- events ----

    private fun ExplorationEvent.toDto(): EventDto =
        when (this) {
            is ExplorationEvent.Started -> {
                EventDto.Started(
                    target.toString(),
                    phases.map { it.name },
                    instructions,
                    budget.toDto(),
                    allowWrites,
                )
            }

            is ExplorationEvent.PhaseStarted -> {
                EventDto.PhaseStarted(phase.name, roles)
            }

            is ExplorationEvent.PhaseSkipped -> {
                EventDto.PhaseSkipped(phase.name, reason)
            }

            is ExplorationEvent.PageVisited -> {
                EventDto.PageVisited(
                    role,
                    url,
                    urlPattern,
                    title,
                    status,
                    loadMs,
                    screenshotArtifactId?.value,
                )
            }

            is ExplorationEvent.ActionDiscovered -> {
                EventDto.ActionDiscovered(action.toDto())
            }

            is ExplorationEvent.FindingRecorded -> {
                EventDto.FindingRecorded(finding.toDto())
            }

            is ExplorationEvent.UnknownRaised -> {
                EventDto.UnknownRaised(unknown.toDto())
            }

            is ExplorationEvent.ModelUpdated -> {
                EventDto.ModelUpdated(counts.toDto())
            }

            is ExplorationEvent.DraftReady -> {
                EventDto.DraftReady(draftId, name, covered, skipped)
            }

            is ExplorationEvent.Finished -> {
                EventDto.Finished(summary.toDto(), modelVersion)
            }

            is ExplorationEvent.Failed -> {
                EventDto.Failed(reason, modelVersion)
            }
        }

    private fun EventDto.toDomain(header: EventHeader): ExplorationEvent =
        when (this) {
            is EventDto.Started -> {
                ExplorationEvent.Started(
                    header,
                    URI(target),
                    phases.map {
                        enumValueOf<ExplorationPhase>(it)
                    },
                    instructions,
                    budget.toDomain(),
                    allowWrites,
                )
            }

            is EventDto.PhaseStarted -> {
                ExplorationEvent.PhaseStarted(header, enumValueOf<ExplorationPhase>(phase), roles)
            }

            is EventDto.PhaseSkipped -> {
                ExplorationEvent.PhaseSkipped(header, enumValueOf<ExplorationPhase>(phase), reason)
            }

            is EventDto.PageVisited -> {
                ExplorationEvent.PageVisited(header, role, url, urlPattern, title, status, loadMs, screenshot?.let(::ArtifactId))
            }

            is EventDto.ActionDiscovered -> {
                ExplorationEvent.ActionDiscovered(header, action.toDomain())
            }

            is EventDto.FindingRecorded -> {
                ExplorationEvent.FindingRecorded(header, finding.toDomain())
            }

            is EventDto.UnknownRaised -> {
                ExplorationEvent.UnknownRaised(header, unknown.toDomain())
            }

            is EventDto.ModelUpdated -> {
                ExplorationEvent.ModelUpdated(header, counts.toDomain())
            }

            is EventDto.DraftReady -> {
                ExplorationEvent.DraftReady(header, draftId, name, covered, skipped)
            }

            is EventDto.Finished -> {
                ExplorationEvent.Finished(header, summary.toDomain(), modelVersion)
            }

            is EventDto.Failed -> {
                ExplorationEvent.Failed(header, reason, modelVersion)
            }
        }

    /** Kind of an event as stored in the `type` column, e.g. `page_visited` (the JSON class discriminator). */
    fun typeOf(event: ExplorationEvent): String =
        explorationJson
            .encodeToJsonElement(EventDto.serializer(), event.toDto())
            .jsonObject
            .getValue(explorationJson.configuration.classDiscriminator)
            .jsonPrimitive
            .content
}

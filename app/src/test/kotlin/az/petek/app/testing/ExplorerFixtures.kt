/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.testing

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.FindingId
import az.petek.explorer.domain.ActionKind
import az.petek.explorer.domain.ActionModel
import az.petek.explorer.domain.EventHeader
import az.petek.explorer.domain.ExplorationBudget
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationFinding
import az.petek.explorer.domain.ExplorationId
import az.petek.explorer.domain.ExplorationPhase
import az.petek.explorer.domain.ExplorationStatus
import az.petek.explorer.domain.ExplorationSummary
import az.petek.explorer.domain.FieldModel
import az.petek.explorer.domain.FindingKind
import az.petek.explorer.domain.FormModel
import az.petek.explorer.domain.ModelCounts
import az.petek.explorer.domain.PageModel
import az.petek.explorer.domain.Provenance
import az.petek.explorer.domain.Severity
import az.petek.explorer.domain.SiteModel
import az.petek.explorer.domain.Unknown
import java.net.URI
import java.time.Instant

/** Builders for the explorer's events and models in the panel's tests. */
object ExplorerFixtures {
    val TARGET: URI = URI("https://kadro.test")
    val EXPLORATION = ExplorationId("exp_1")
    val T0: Instant = Instant.parse("2026-01-01T10:00:00Z")

    private var seq = 0L

    fun header(
        seconds: Long,
        id: ExplorationId = EXPLORATION,
    ): EventHeader = EventHeader(id, ++seq, T0.plusSeconds(seconds))

    fun started(
        seconds: Long = 0,
        phases: List<ExplorationPhase> = ExplorationPhase.entries,
    ) = ExplorationEvent.Started(header(seconds), TARGET, phases, "Elanları yoxla", ExplorationBudget(), allowWrites = false)

    fun phaseStarted(
        phase: ExplorationPhase,
        roles: List<String> = listOf("anonymous"),
        seconds: Long = 1,
    ) = ExplorationEvent.PhaseStarted(header(seconds), phase, roles)

    fun phaseSkipped(
        phase: ExplorationPhase,
        reason: String,
        seconds: Long = 1,
    ) = ExplorationEvent.PhaseSkipped(header(seconds), phase, reason)

    fun visited(
        pattern: String,
        role: String = "anonymous",
        status: Int? = 200,
        seconds: Long = 2,
        screenshot: String? = "art_$pattern",
    ) = ExplorationEvent.PageVisited(
        header(seconds),
        role,
        TARGET.resolve(pattern.replace("{id}", "7")).toString(),
        pattern,
        "Səhifə $pattern",
        status,
        120,
        screenshot?.let(::ArtifactId),
    )

    fun action(
        id: String,
        pageId: String,
        kind: ActionKind = ActionKind.CREATE,
        name: String = "Elan yarat",
        allowed: Set<String> = setOf("admin"),
        forbidden: Set<String> = emptySet(),
    ) = ActionModel(
        id = id,
        name = name,
        kind = kind,
        pageId = pageId,
        selector = "[data-testid=\"$id\"]",
        allowedRoles = allowed,
        forbiddenRoles = forbidden,
        triggersRealtime = null,
        httpMethod = "POST",
        httpPath = "/$pageId",
        trial = null,
        provenance = Provenance.OBSERVED,
        evidence = emptyList(),
    )

    fun discovered(
        action: ActionModel,
        seconds: Long = 3,
    ) = ExplorationEvent.ActionDiscovered(header(seconds), action)

    fun finding(
        seconds: Long = 3,
        detail: String = "Keçid /old 404 qaytarır",
    ) = ExplorationEvent.FindingRecorded(
        header(seconds),
        ExplorationFinding(
            FindingId("fnd_1"),
            FindingKind.BROKEN_LINK,
            Severity.MEDIUM,
            "https://kadro.test/",
            detail,
            "anonymous",
            emptyList(),
        ),
    )

    fun unknown(
        id: String = "u1",
        question: String = "Şirkət kodu haradan alınır?",
        seconds: Long = 4,
    ) = ExplorationEvent.UnknownRaised(
        header(seconds),
        Unknown(id, question, "Qoşulma formu kod istəyir", "join", Provenance.INFERRED, emptyList()),
    )

    fun summary(
        status: ExplorationStatus = ExplorationStatus.COMPLETED,
        pages: Int = 2,
        durationMs: Long = 42_000,
        notes: List<String> = emptyList(),
        pageBudgetReached: Boolean = false,
    ) = ExplorationSummary(
        status = status,
        counts = ModelCounts(pages = pages, forms = 1, actions = 1, realtime = 0, unknowns = 1, findings = 1),
        pagesVisitedByRole = mapOf("anonymous" to pages),
        llmCalls = pages,
        llmAnswersRejected = 0,
        durationMs = durationMs,
        pageBudgetReached = pageBudgetReached,
        phasesRun = listOf(ExplorationPhase.ANONYMOUS),
        phasesSkipped = emptyMap(),
        notes = notes,
    )

    fun finished(
        summary: ExplorationSummary = summary(),
        seconds: Long = 42,
    ) = ExplorationEvent.Finished(header(seconds), summary, modelVersion = 1)

    fun page(
        pattern: String,
        reachableBy: Set<String> = setOf("anonymous"),
        forms: List<FormModel> = emptyList(),
    ) = PageModel(
        id =
            az.petek.explorer.domain.UrlPatterns
                .pageId(pattern),
        urlPattern = pattern,
        title = "Səhifə $pattern",
        purpose = "Məqsəd $pattern",
        reachableBy = reachableBy,
        forms = forms,
        testIds = emptyList(),
        linkCount = 3,
        loadMs = 100,
        provenance = Provenance.OBSERVED,
        evidence = emptyList(),
    )

    fun form(
        purpose: String = "Elan yaratma",
        fields: List<FieldModel> = listOf(FieldModel("Başlıq", "title", "text", true, "announcement-title", "#title")),
    ) = FormModel(
        purpose,
        ActionKind.CREATE,
        fields,
        "[data-testid=\"announcement-submit\"]",
        "post",
        "/announcements",
        Provenance.OBSERVED,
        emptyList(),
    )

    fun model(
        version: Int = 1,
        pages: List<PageModel> = listOf(page("/login"), page("/announcements", setOf("admin", "employee"), listOf(form()))),
        actions: List<ActionModel> =
            listOf(action("announcement-submit", "announcements", forbidden = setOf("employee"), allowed = setOf("admin"))),
        partial: Boolean = false,
        id: ExplorationId = EXPLORATION,
    ) = SiteModel(
        version = version,
        explorationId = id,
        target = TARGET,
        createdAt = T0,
        pages = pages,
        actions = actions,
        roles = emptyList(),
        realtime = emptyList(),
        unknowns = emptyList(),
        partial = partial,
    )
}

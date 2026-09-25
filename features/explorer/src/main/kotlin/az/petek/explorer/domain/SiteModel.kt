/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.domain

import az.petek.browser.domain.RealtimeTransport
import az.petek.core.ids.ArtifactId
import java.net.URI
import java.time.Instant

/**
 * How the explorer knows something (docs/PLAN.md Faza 6 "observed / inferred ayrımı"). The owner must always be able
 * to tell a fact the browser saw from a conclusion drawn from it.
 */
enum class Provenance {
    /** Seen directly by the harness: a page loaded, an element present in a snapshot, a transport in the traffic. */
    OBSERVED,

    /** Concluded from observations: the LLM's reading of an element, a permission inferred by comparing roles. */
    INFERRED,
}

/**
 * What Pətək learned about a site in one exploration. Versions count up per target (see [TargetKey]); every element
 * carries its [Provenance] and the evidence artifacts (screenshots, DOM snapshots) it is based on. [partial] marks a
 * model whose exploration did not see the whole site (time budget, cancellation, error, or pages left unvisited at
 * the page budget): it is kept, but a missing page there does not mean the page is gone.
 */
data class SiteModel(
    val version: Int,
    val explorationId: ExplorationId,
    val target: URI,
    val createdAt: Instant,
    val pages: List<PageModel>,
    val actions: List<ActionModel>,
    val roles: List<RoleModel>,
    val realtime: List<RealtimeObservation>,
    val unknowns: List<Unknown>,
    val partial: Boolean = false,
) {
    init {
        require(version >= 1) { "A site model version starts at 1, was $version" }
    }

    fun page(id: String): PageModel? = pages.firstOrNull { it.id == id }

    fun pageByPattern(urlPattern: String): PageModel? = pages.firstOrNull { it.urlPattern == urlPattern }

    fun action(id: String): ActionModel? = actions.firstOrNull { it.id == id }

    fun counts(findings: Int): ModelCounts =
        ModelCounts(
            pages = pages.size,
            forms = pages.sumOf { it.forms.size },
            actions = actions.size,
            realtime = realtime.size,
            unknowns = unknowns.size,
            findings = findings,
        )
}

/**
 * One page of the site, identified by its [urlPattern] (ids generalised to `{id}`, see [UrlPatterns]).
 * [reachableBy] names who loaded it: `anonymous` or role names. [purpose] is the LLM's one-line summary (blank when no
 * LLM answer was usable); [testIds] are the `data-testid`s present on the page, which scenarios use as selectors.
 */
data class PageModel(
    val id: String,
    val urlPattern: String,
    val title: String,
    val purpose: String,
    val reachableBy: Set<String>,
    val forms: List<FormModel>,
    val testIds: List<String>,
    val linkCount: Int,
    val loadMs: Long?,
    val provenance: Provenance,
    val evidence: List<ArtifactId>,
)

/**
 * A form found in the page's DOM. [kind] and [purpose] are classified by code from its fields, buttons, test ids and
 * action path; [actionPath] is the generalised path it posts to, when it has one on the same site.
 */
data class FormModel(
    val purpose: String,
    val kind: ActionKind,
    val fields: List<FieldModel>,
    val submitSelector: String?,
    val method: String,
    val actionPath: String?,
    val provenance: Provenance,
    val evidence: List<ArtifactId>,
) {
    /** Identity of the form within its page across versions: its submit button, else its purpose. */
    val key: String get() = submitSelector ?: purpose
}

/** A user-editable field of a form, always observed in the DOM. [selector] addresses it for deterministic steps. */
data class FieldModel(
    val label: String,
    val name: String,
    val type: String,
    val required: Boolean,
    val testId: String?,
    val selector: String,
    val options: List<String> = emptyList(),
) {
    /** Identity of the field within its form across versions. */
    val key: String get() = name.ifBlank { testId ?: label }
}

enum class ActionKind { REGISTER, LOGIN, CREATE, UPDATE, DELETE, APPROVE, REJECT, ASSIGN, SUBMIT, NAVIGATE, OTHER }

/**
 * Something a user can do on the site. [allowedRoles] are the roles (or `anonymous`) that were shown the action;
 * [forbiddenRoles] is always inferred: logged-in roles that loaded the action's page, or were refused it, without being
 * offered the action. [triggersRealtime] is true only when the trial touch saw another role receive the result live,
 * null when that was not observed. [httpMethod]/[httpPath] come from the form the action submits, when there is one.
 */
data class ActionModel(
    val id: String,
    val name: String,
    val kind: ActionKind,
    val pageId: String,
    val selector: String,
    val allowedRoles: Set<String>,
    val forbiddenRoles: Set<String>,
    val triggersRealtime: Boolean?,
    val httpMethod: String?,
    val httpPath: String?,
    val trial: TrialTouch?,
    val provenance: Provenance,
    val evidence: List<ArtifactId>,
)

enum class TrialOutcome {
    /** The submitted marker text appeared for the submitting role. */
    ACCEPTED,

    /** The page answered with what looks like a validation or error message. */
    REJECTED,

    /** Neither the marker nor an error message appeared; the owner should look at the evidence. */
    UNCLEAR,
}

/**
 * The result of submitting a CREATE form once with harmless data (phase TRIAL_TOUCH). [marker] is the unique text the
 * explorer typed; [seenLiveBy] are the other roles whose open page showed it without reloading.
 */
data class TrialTouch(
    val role: String,
    val outcome: TrialOutcome,
    val marker: String,
    val messages: List<String>,
    val urlPatternAfter: String?,
    val seenLiveBy: Set<String>,
    val evidence: List<ArtifactId>,
)

/** A viewpoint the site was explored from: `anonymous` or a logged-in role given by the caller. */
data class RoleModel(
    val name: String,
    val anonymous: Boolean,
    val pageIds: Set<String>,
    val deniedPatterns: Set<String>,
    val provenance: Provenance,
    val evidence: List<ArtifactId>,
)

/** A live-update transport seen in the browser traffic ([pages] are page ids, [roles] who saw it). */
data class RealtimeObservation(
    val transport: RealtimeTransport,
    val detail: String,
    val pages: Set<String>,
    val roles: Set<String>,
    val provenance: Provenance,
    val evidence: List<ArtifactId>,
)

/** A question for the owner: something the explorer could not decide from the site itself. */
data class Unknown(
    val id: String,
    val question: String,
    val context: String,
    val pageId: String?,
    val provenance: Provenance,
    val evidence: List<ArtifactId>,
)

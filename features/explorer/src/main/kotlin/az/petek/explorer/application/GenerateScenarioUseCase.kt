/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.application

import az.petek.campaign.domain.ActorExpressionParser
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignSettings
import az.petek.campaign.domain.CampaignValidator
import az.petek.campaign.domain.DefaultActorExpressionParser
import az.petek.campaign.domain.OnFail
import az.petek.campaign.domain.RegistrationQuota
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.TemplateRenderer
import az.petek.campaign.domain.ValidationIssue
import az.petek.core.error.PetekException
import az.petek.core.ids.IdGenerator
import az.petek.core.time.HarnessClock
import az.petek.explorer.domain.CoveredIdea
import az.petek.explorer.domain.ExplorationEvent
import az.petek.explorer.domain.ExplorationObserver
import az.petek.explorer.domain.ExplorationRepository
import az.petek.explorer.domain.ScenarioDraft
import az.petek.explorer.domain.SiteModel
import az.petek.explorer.domain.SkippedIdea
import az.petek.explorer.domain.Slugs
import az.petek.explorer.domain.TestPatternLibrary
import java.security.MessageDigest

/** A generated campaign failed validation: a bug in the generator, never something the owner has to fix. */
class ScenarioGenerationException(
    val issues: List<ValidationIssue>,
) : PetekException("The generated campaign is invalid:\n" + issues.joinToString("\n") { "  - $it" })

/** A draft before it is stored: the validated campaign, its YAML and the explanation. */
data class ComposedScenario(
    val campaign: Campaign,
    val yaml: String,
    val covered: List<CoveredIdea>,
    val skipped: List<SkippedIdea>,
)

/**
 * Generates a campaign draft from what the explorer learned (docs/PLAN.md Faza 6 "avtomatik ssenari"): the site
 * model's top [az.petek.explorer.domain.TestIdea]s (grounded by the owner's instructions) become steps assembled by
 * code (see [ScenarioComposer]) behind a setup of the deterministic run functions ([ScenarioSettings.setup]). The
 * campaign is validated with [campaignValidator] against [knownRunFunctions] before anything is returned or stored,
 * so every draft is runnable once the owner approves it; a failure is a [ScenarioGenerationException].
 */
class GenerateScenarioUseCase(
    private val campaignValidator: CampaignValidator,
    private val templates: TemplateRenderer,
    private val knownRunFunctions: Set<String>,
    private val repository: ExplorationRepository,
    private val clock: HarnessClock,
    private val ids: IdGenerator,
    private val settings: ScenarioSettings = ScenarioSettings(),
    private val patterns: TestPatternLibrary = TestPatternLibrary(),
    private val actorParser: ActorExpressionParser = DefaultActorExpressionParser(),
) {
    /**
     * Generates, stores and announces ([ExplorationEvent.DraftReady]) a draft from the model of
     * [ScenarioRequest.explorationId]; refused with [IllegalArgumentException] when that exploration saved no model.
     */
    suspend fun execute(
        request: ScenarioRequest,
        observer: ExplorationObserver = ExplorationObserver.NONE,
    ): ScenarioDraft {
        val model =
            requireNotNull(repository.model(request.explorationId)) { "Exploration ${request.explorationId} has no site model" }
        val instructions = request.instructions ?: repository.find(request.explorationId)?.request?.grounding
        val composed = compose(model, request.copy(instructions = instructions))
        val draft =
            ScenarioDraft(
                id = "drf_" + ids.correlationId().value.removePrefix("cor_"),
                explorationId = request.explorationId,
                modelVersion = model.version,
                target = model.target,
                name = composed.campaign.settings.name,
                yaml = composed.yaml,
                covered = composed.covered,
                skipped = composed.skipped,
                createdAt = clock.now().wall,
            )
        repository.saveDraft(draft)
        announce(draft, observer)
        return draft
    }

    /** Composes and validates a draft without storing it (previews, tests). */
    fun compose(
        model: SiteModel,
        request: ScenarioRequest,
    ): ComposedScenario {
        val ideas = patterns.ideas(model, request.instructions).take(request.maxIdeas)
        val composer = ScenarioComposer(model, settings, request.testApi, templates, actorParser)
        val setup = composer.setupSteps()
        val (covered, skipped) = composer.compose(ideas)
        val name = request.name ?: "explorer-${Slugs.of(model.target.host.orEmpty()).ifEmpty { "site" }}-v${model.version}"
        val draft =
            Campaign(
                settings = campaignSettings(model, name),
                target = TargetProfile(emptyMap(), emptyMap(), composer.idSources.toMap()),
                setup = setup,
                steps = composer.mainSteps,
                sourceHash = "",
            )
        val written = CampaignYamlWriter.write(draft, header(model, covered, skipped))
        val campaign =
            draft.copy(
                setup = draft.setup.map { it.copy(line = written.stepLines[it.id] ?: 0) },
                steps = draft.steps.map { it.copy(line = written.stepLines[it.id] ?: 0) },
                sourceHash = sha256(written.yaml),
            )
        val issues = campaignValidator.validate(campaign, knownRunFunctions)
        if (issues.isNotEmpty()) throw ScenarioGenerationException(issues)
        return ComposedScenario(campaign, written.yaml, covered, skipped)
    }

    private fun campaignSettings(
        model: SiteModel,
        name: String,
    ): CampaignSettings {
        val team = settings.team
        val invite = team.manager + team.employee / 2
        return CampaignSettings(
            target = model.target,
            testers = team.total,
            seed = settings.seed,
            names = emptyList(),
            roles = team,
            departments = settings.departments,
            registration = RegistrationQuota(invite = invite, companyCode = team.manager + team.employee - invite),
            budget = settings.budget,
            onFail = OnFail.CONTINUE,
            name = name,
        )
    }

    private fun header(
        model: SiteModel,
        covered: List<CoveredIdea>,
        skipped: List<SkippedIdea>,
    ): List<String> =
        buildList {
            add("Draft generated by the Pətək explorer from site model v${model.version} of ${model.target} (${model.explorationId}).")
            add("Review it before running: `do` texts are made from the site's own labels, assertions are checked by code.")
            covered.forEach { add("covers ${it.idea.pattern} of ${it.idea.actionId}: steps ${it.stepIds.joinToString()}") }
            skipped.forEach { add("skipped ${it.idea.pattern} of ${it.idea.actionId}: ${it.reason}") }
        }.map { it.replace('\n', ' ') }

    private suspend fun announce(
        draft: ScenarioDraft,
        observer: ExplorationObserver,
    ) {
        var attempt = 0
        while (true) {
            val emitter = ExplorationEmitter(draft.explorationId, repository, observer, clock, repository.lastSeq(draft.explorationId))
            try {
                emitter.emit { ExplorationEvent.DraftReady(it, draft.id, draft.name, draft.covered.size, draft.skipped.size) }
                return
            } catch (e: IllegalArgumentException) {
                // Another event of the same exploration took this sequence number meanwhile; number again.
                if (++attempt >= ANNOUNCE_ATTEMPTS) throw e
            }
        }
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).toHexString()

    private companion object {
        const val ANNOUNCE_ATTEMPTS = 3
    }
}

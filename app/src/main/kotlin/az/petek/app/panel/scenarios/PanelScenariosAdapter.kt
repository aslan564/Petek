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

package az.petek.app.panel.scenarios

import az.petek.app.campaign.IdentitySpecs
import az.petek.app.di.AppContainer
import az.petek.app.panel.RunPlans
import az.petek.app.panel.explorer.DraftSettings
import az.petek.app.panel.explorer.PanelExplorerAdapter
import az.petek.core.ids.RunTags
import az.petek.dashboard.domain.DiffView
import az.petek.dashboard.domain.FieldProblem
import az.petek.dashboard.domain.PanelConflictException
import az.petek.dashboard.domain.PanelNotFoundException
import az.petek.dashboard.domain.PanelRequestException
import az.petek.dashboard.domain.PanelScenarios
import az.petek.dashboard.domain.RunPlanView
import az.petek.dashboard.domain.RunRequest
import az.petek.dashboard.domain.ScenarioVersionView
import az.petek.dashboard.domain.ScenarioView
import az.petek.explorer.application.ScenarioGenerationException
import az.petek.explorer.application.ScenarioRequest
import az.petek.scenarios.domain.ScenarioFileException
import az.petek.scenarios.domain.ScenarioInvalidException
import az.petek.scenarios.domain.ScenarioNotFoundException
import az.petek.scenarios.domain.ScenarioSource
import az.petek.scenarios.domain.ScenarioTransitionException
import az.petek.scenarios.domain.ScenarioVersion
import az.petek.scenarios.domain.ScenarioVersionId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

/**
 * The "Ssenarilər" screen's backend over the versioned scenario catalog ([AppContainer.scenarioCatalog]):
 *
 * - **Owner's files.** At start every YAML file of `scenarios/` is imported once: a file whose scenario has no version yet
 *   becomes that scenario's first version and is approved (the owner wrote it); a changed file becomes a new DRAFT of
 *   its scenario for the owner to review as a diff; an unchanged file adds nothing. A file that does not pass the
 *   campaign validator is skipped and logged.
 * - **Drafts from the explorer.** [generateScenario] turns the latest exploration's site model into a campaign
 *   ([az.petek.explorer.application.GenerateScenarioUseCase], validated like `petek run` would) and stores it as a DRAFT
 *   (source EXPLORER); generating the same text again returns the stored version.
 * - **Review.** list, show, diff, approve and freeze map 1:1 to the catalog; its refusals become the owner's words.
 * - **Plan preview.** [runPlan] resolves each step's actors against the identities the scenario's tester count gives,
 *   as `petek plan` does, so the owner sees who will do what before running.
 */
internal class PanelScenariosAdapter(
    private val container: AppContainer,
    private val explorer: PanelExplorerAdapter,
    private val scenarioDirectory: Path,
    scope: CoroutineScope,
) : PanelScenarios {
    private val catalog get() = container.scenarioCatalog

    /** The start-up import; every read waits for it, so the list is never shown half imported. */
    private val imported: Deferred<Unit> = scope.async { importOwnerFiles() }

    /** One generation at a time: a double click must not store the same draft twice. */
    private val generating = Mutex()

    override suspend fun generateScenario(): ScenarioView = generating.withLock { generate() }

    private suspend fun generate(): ScenarioView {
        val source = explorer.draftSource()
        val settings = DraftSettings.of(source.departments)
        val draft =
            try {
                container
                    .scenarioGenerator(settings)
                    .execute(
                        ScenarioRequest(
                            source.explorationId,
                            source.grounding?.ifBlank { null },
                            testApi = explorer.testApi(source.target),
                            tenant = explorer.tenant(source.target),
                        ),
                        explorer.observerFor(source.explorationId),
                    )
            } catch (e: ScenarioGenerationException) {
                logger.error(e) { "The explorer's draft of ${source.explorationId} is invalid" }
                throw PanelConflictException(
                    "Kəşfiyyatçının ssenari layihəsi yoxlamadan keçmədi: ${e.issues.firstOrNull()?.message.orEmpty()}",
                )
            } catch (e: IllegalArgumentException) {
                throw PanelConflictException("Bu kəşfiyyatın sayt modeli yoxdur; saytı yenidən kəşf edin.")
            }
        explorer.drafted(source.explorationId, draft.yaml)
        imported.await()
        val history = catalog.history(draft.name)
        history.lastOrNull { it.yaml == draft.yaml }?.let { return ScenarioViews.scenario(it) }
        val note =
            "Kəşfiyyatçı: ${draft.target} saytının modeli v${draft.modelVersion} (${draft.explorationId}); " +
                "${draft.covered.size} test ideyası əhatə olunub, ${draft.skipped.size} buraxılıb."
        val version =
            try {
                catalog.createDraft(draft.yaml, ScenarioSource.EXPLORER, history.lastOrNull()?.id, note, "${draft.name}.yaml")
            } catch (e: ScenarioInvalidException) {
                throw PanelConflictException("Ssenari layihəsi kataloqa yazılmadı: ${e.issues.firstOrNull()?.message.orEmpty()}")
            }
        return ScenarioViews.scenario(version)
    }

    override suspend fun scenarios(): List<ScenarioVersionView> {
        imported.await()
        return catalog
            .list()
            .sortedWith(compareBy<ScenarioVersion> { it.name }.thenByDescending { it.version })
            .map(ScenarioViews::version)
    }

    override suspend fun scenario(id: String): ScenarioView? {
        imported.await()
        return catalog.find(ScenarioVersionId(id))?.let(ScenarioViews::scenario)
    }

    override suspend fun diff(
        fromId: String,
        toId: String,
    ): DiffView {
        imported.await()
        val from = found(fromId)
        val to = found(toId)
        return ScenarioViews.diff(from, to, catalog.diff(from.id, to.id))
    }

    override suspend fun approve(id: String): ScenarioVersionView {
        imported.await()
        val version = found(id)
        return try {
            ScenarioViews.version(catalog.approve(version.id))
        } catch (e: ScenarioTransitionException) {
            throw PanelConflictException(ScenarioViews.refusal(e))
        } catch (e: ScenarioInvalidException) {
            throw PanelConflictException("${version.label} artıq yoxlamadan keçmir: ${e.issues.firstOrNull()?.message.orEmpty()}")
        }
    }

    override suspend fun freeze(id: String): ScenarioVersionView {
        imported.await()
        val version = found(id)
        return try {
            ScenarioViews.version(catalog.freeze(version.id))
        } catch (e: ScenarioTransitionException) {
            throw PanelConflictException(ScenarioViews.refusal(e))
        }
    }

    override suspend fun runPlan(scenarioId: String): RunPlanView? {
        imported.await()
        val version = catalog.find(ScenarioVersionId(scenarioId)) ?: return null
        val campaign = container.scenarioValidator.check(version.yaml, version.fileName).campaign ?: return null
        val identities =
            try {
                container.identityGenerator
                    .generate(
                        IdentitySpecs.of(campaign.settings, container.config.mailDomain, container.config.mailInbox),
                        RunTags.forPlan(campaign.sourceHash, campaign.settings.seed),
                    ).identities
            } catch (e: Exception) {
                logger.warn(e) { "No identities could be planned for ${version.label}" }
                emptyList()
            }
        return RunPlans.of(null, campaign, identities)
    }

    // --- for runs -------------------------------------------------------------------------------------------------

    /**
     * The version "Run et" names: an APPROVED or FROZEN version by id, or a campaign file of `scenarios/` imported first.
     * Throws [PanelNotFoundException] for an unknown one and [PanelConflictException] for one that is not approved.
     */
    suspend fun runnable(request: RunRequest): ScenarioVersion {
        imported.await()
        val version =
            request.scenarioId?.takeIf { it.isNotBlank() }?.let { found(it) }
                ?: request.campaignPath?.let { importForRun(it) }
                ?: throw PanelNotFoundException("Ssenari tapılmadı.")
        if (!version.runnable) {
            throw PanelConflictException("${version.label} hələ təsdiqlənməyib; əvvəlcə Ssenarilər bölməsində təsdiqləyin.")
        }
        return version
    }

    /** Writes [version]'s exact text to the new file [file] (its SHA-256 stays the version's). */
    suspend fun export(
        version: ScenarioVersion,
        file: Path,
    ) {
        catalog.export(version.id, file)
    }

    /** Catalog versions by the SHA-256 of their text: a run's `campaign_hash` names the version it executed. */
    suspend fun versionsByHash(): Map<String, List<ScenarioVersion>> {
        imported.await()
        return catalog.list().groupBy { it.sha256 }
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    private suspend fun found(id: String): ScenarioVersion =
        try {
            catalog.get(ScenarioVersionId(id))
        } catch (_: ScenarioNotFoundException) {
            throw PanelNotFoundException("Ssenari tapılmadı.")
        }

    /** A campaign file named by a run request; only files inside `scenarios/` are accepted. */
    private suspend fun importForRun(relative: String): ScenarioVersion {
        val directory = scenarioDirectory.toAbsolutePath().normalize()
        val file = directory.parent.resolve(relative).normalize()
        if (!file.startsWith(directory) || !withContext(Dispatchers.IO) { Files.isRegularFile(file) }) {
            throw PanelNotFoundException("Kampaniya faylı tapılmadı (yalnız scenarios/ qovluğundakı fayllar).")
        }
        return try {
            importOwnerFile(file)
        } catch (e: ScenarioInvalidException) {
            throw PanelRequestException(listOf(FieldProblem(RunRequest.SCENARIO, "Fayl yoxlamadan keçmədi: ${e.issues.first().message}")))
        }
    }

    private suspend fun importOwnerFiles() {
        val files =
            withContext(Dispatchers.IO) {
                if (!scenarioDirectory.isDirectory()) return@withContext emptyList()
                Files.list(scenarioDirectory).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.extension in YAML }.sorted().toList()
                }
            }
        files.forEach { file ->
            try {
                importOwnerFile(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScenarioInvalidException) {
                logger.warn { "scenarios/${file.name} is not imported into the catalog: ${e.issues.joinToString("; ")}" }
            } catch (e: ScenarioFileException) {
                logger.warn { "scenarios/${file.name} could not be read: ${e.message}" }
            } catch (e: Exception) {
                logger.warn(e) { "scenarios/${file.name} could not be imported" }
            }
        }
    }

    /** Imports [file]; the first version of a scenario the owner wrote is approved at once. */
    private suspend fun importOwnerFile(file: Path): ScenarioVersion {
        val result = catalog.importFile(file, note = "scenarios/${file.name} faylından")
        if (!result.created) return result.version
        val history = catalog.history(result.version.name)
        if (history.size != 1) {
            logger.info {
                "scenarios/${file.name} changed: ${result.version.label} is a draft for review (history ${history.map { it.label }})"
            }
            return result.version
        }
        val approved = approveFirst(file, result.version)
        logger.info { "scenarios/${file.name} imported and approved as ${approved.label}" }
        return approved
    }

    /**
     * The approval of a first version is retried once: it is the start-up's only write racing with the rest of the
     * panel coming up, and a version left as a draft here would mean "no scenario runs" for the owner.
     */
    private suspend fun approveFirst(
        file: Path,
        version: ScenarioVersion,
    ): ScenarioVersion {
        repeat(APPROVE_ATTEMPTS - 1) { attempt ->
            try {
                return catalog.approve(version.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "scenarios/${file.name}: approving ${version.label} failed (attempt ${attempt + 1}); retrying" }
                delay(APPROVE_RETRY_DELAY)
            }
        }
        return catalog.approve(version.id)
    }

    private companion object {
        val YAML = setOf("yaml", "yml")
        const val APPROVE_ATTEMPTS = 2
        val APPROVE_RETRY_DELAY = 500.milliseconds
    }
}

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

package az.petek.app.panel.explorer

import az.petek.app.di.AppContainer
import az.petek.app.panel.PanelTargets
import az.petek.browser.domain.BrowserSession
import az.petek.browser.domain.BrowserSessionFactory
import az.petek.browser.domain.SessionOptions
import az.petek.campaign.domain.Budget
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignSettings
import az.petek.campaign.domain.DefaultActorExpressionParser
import az.petek.campaign.domain.DefaultCampaignValidator
import az.petek.campaign.domain.OnFail
import az.petek.campaign.domain.RegistrationQuota
import az.petek.campaign.domain.RoleQuota
import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.Tenant
import az.petek.core.ids.RunId
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.explorer.domain.TestTargetCheck
import az.petek.explorer.domain.TestTargetVerdict
import az.petek.identity.domain.IdentityStatus
import az.petek.orchestration.application.TeardownUseCase
import az.petek.orchestration.domain.RunOutcome
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/** What the explorer's role-based walk and trial touch need: who may log in where, and whether writes are allowed. */
internal data class RoleSessionRequest(
    val target: URI,
    val allowWrites: Boolean,
    /** The owner's departments; the first one names the department of the session testers. */
    val departments: List<String>,
)

/**
 * Logged-in browser sessions per role for one exploration (`admin`, `manager`, `employee`), the check that confirms
 * their company as test data, and how to let go of them. Empty [sessions] make the explorer skip ROLE_BASED and
 * TRIAL_TOUCH; [note] then tells the owner why and how to get them.
 */
internal class RoleSessions(
    val sessions: Map<String, BrowserSession>,
    val testCheck: TestTargetCheck,
    val note: String?,
    private val release: suspend () -> Unit = {},
) {
    /** Closes the sessions and removes whatever was created for them. Call it once, also after a failure. */
    suspend fun close() = release()

    companion object {
        fun none(note: String): RoleSessions = RoleSessions(emptyMap(), { TestTargetVerdict.Refused("daxil olmuş sessiya yoxdur") }, note)
    }
}

/** Where the explorer's logged-in sessions come from. */
internal fun interface RoleSessionSource {
    /**
     * Sessions for [request] opened with [sessions] (the explorer's own browser); [progress] receives lines for the
     * owner while they are prepared. Never throws for an unusable target: it returns [RoleSessions.none] with the reason.
     */
    suspend fun open(
        request: RoleSessionRequest,
        sessions: BrowserSessionFactory,
        progress: (String) -> Unit,
    ): RoleSessions
}

/** A setup run that kept its data: its id, and how it ended (null when it was stopped before it finished). */
internal data class SetupRun(
    val runId: RunId,
    val outcome: RunOutcome?,
)

/**
 * Runs a campaign that keeps its test data (no teardown at its end) in the panel's run slot, so the owner sees it on
 * the board and can stop it. Null when another run holds the slot. When the caller is cancelled, the run is stopped
 * and torn down before the cancellation goes on; otherwise tearing it down is the caller's job.
 */
internal fun interface SetupRuns {
    suspend fun runKeepingData(campaign: Campaign): SetupRun?
}

/**
 * Role sessions from a small test company created for the exploration: with the owner's permission to write
 * ([RoleSessionRequest.allowWrites]) on the configured target, whose test API confirms test data, it runs a setup-only
 * campaign (one admin signs up and creates the company, a manager and an employee join, all by the deterministic run
 * functions, never the LLM) as an ordinary run the owner sees on the board, keeping its data. The campaign signs up
 * with the site's own target profile from [profiles] (the flows and selectors of the site's scenario, else the
 * contract defaults). The testers' saved browser states then become the explorer's logged-in sessions, and the
 * company's `is_test` flag is checked through the test API before the trial touch writes anything.
 * [RoleSessions.close] closes the sessions and tears the company down through the same teardown every run uses.
 *
 * Anywhere else (writes not allowed, another site than `PETEK_TARGET`, no test token, a target whose [testApi] does not
 * answer like the test API of docs/TARGET_CONTRACT.md) nothing is written and the sessions are [RoleSessions.none] with
 * the reason.
 */
internal class TestCompanyRoleSessions(
    private val container: AppContainer,
    private val runs: SetupRuns,
    private val testApi: TestApiProbe = OracleTestApiProbe(container.oracle, container.config.mailDomain),
    private val teardown: TeardownUseCase = container.teardown,
    private val profiles: SetupProfileSource = CatalogSetupProfiles(container.scenarioCatalog, container.scenarioValidator),
) : RoleSessionSource {
    override suspend fun open(
        request: RoleSessionRequest,
        sessions: BrowserSessionFactory,
        progress: (String) -> Unit,
    ): RoleSessions {
        refusal(request)?.let { return RoleSessions.none(it) }
        testApi.refusal()?.let { return RoleSessions.none("Rollarla gəzinti buraxıldı: $it") }
        val profile = profiles.profile()
        val campaign = campaign(request, profile.profile)
        progress("Rollarla gəzinti üçün müvəqqəti test şirkəti yaradılır (admin, menecer, işçi); qeydiyyat axınları: ${profile.origin}…")
        val setup =
            try {
                runs.runKeepingData(campaign)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "The explorer's test company could not be created" }
                return RoleSessions.none("Test şirkəti yaradıla bilmədi: ${e.message ?: e::class.simpleName}")
            } ?: return RoleSessions.none("Başqa run gedir; rollarla gəzinti üçün test şirkəti yaradılmadı.")
        return try {
            sessionsOf(setup, request, sessions, progress)
        } catch (e: CancellationException) {
            // Stopped between the setup run and the sessions: the company would stay behind otherwise.
            withContext(NonCancellable) { tearDown(setup.runId) }
            throw e
        }
    }

    /**
     * The explorer's own account (`self_register`): one owner signs up through the site's registration flow, nothing
     * else is created. Its data is removed through the test API when the site has one; otherwise the account stays and
     * the activity says so.
     */
    suspend fun registerOnly(
        request: RoleSessionRequest,
        sessions: BrowserSessionFactory,
        progress: (String) -> Unit,
    ): RoleSessions {
        val profile = profiles.profile()
        val companies = tenantOf(request) == Tenant.COMPANY
        val campaign = if (companies) campaign(request, profile.profile, ownerOnly = true) else selfSignUp(profile.profile)
        progress(
            "Kəşfiyyatçı öz hesabını açır (qeydiyyat axını: ${profile.origin}); kod ${container.config.mailSource.key} poçtundan oxunur…",
        )
        if (!container.oracle.isAvailable) progress("Test API yoxdur: bu hesab sonda silinə bilməyəcək və saytda qalacaq.")
        val setup =
            try {
                runs.runKeepingData(campaign)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "The explorer's own account could not be registered" }
                return RoleSessions.none("Qeydiyyat alınmadı: ${e.message ?: e::class.simpleName}")
            } ?: return RoleSessions.none("Başqa run gedir; kəşfiyyatçı öz hesabını aça bilmədi.")
        return try {
            sessionsOf(setup, request, sessions, progress)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { tearDown(setup.runId) }
            throw e
        }
    }

    /** The logged-in sessions of [setup]'s testers; tears the company down itself unless it returns sessions. */
    private suspend fun sessionsOf(
        setup: SetupRun,
        request: RoleSessionRequest,
        sessions: BrowserSessionFactory,
        progress: (String) -> Unit,
    ): RoleSessions {
        val identities = container.identities.findByRun(setup.runId)
        val active =
            identities
                .filter {
                    it.status == IdentityStatus.ACTIVE &&
                        it.storageStatePath?.let { path -> Files.isRegularFile(Path.of(path)) } == true
                }.groupBy { it.role }
                .mapValues { (_, list) -> list.first() }
        if (setup.outcome == null || setup.outcome == RunOutcome.ABORTED || active.isEmpty()) {
            withContext(NonCancellable) { tearDown(setup.runId) }
            val ended = setup.outcome?.name ?: "dayandırıldı"
            return RoleSessions.none("Test şirkəti yaradıla bilmədi (run ${setup.runId}: $ended); səbəb onun hesabatındadır.")
        }
        val opened = LinkedHashMap<String, BrowserSession>()
        try {
            active.entries.sortedBy { it.key }.forEach { (role, identity) ->
                opened[role.key] =
                    sessions.open(
                        SessionOptions(
                            label = "explorer-${role.key}",
                            baseUrl = request.target,
                            storageState = Path.of(checkNotNull(identity.storageStatePath)),
                        ),
                    )
            }
        } catch (e: Exception) {
            withContext(NonCancellable) { closeAll(opened.values) }
            // A cancellation is torn down by the caller.
            if (e is CancellationException) throw e
            withContext(NonCancellable) { tearDown(setup.runId) }
            logger.warn(e) { "The explorer's role sessions could not be opened" }
            return RoleSessions.none("Rol sessiyaları açıla bilmədi: ${e::class.simpleName}")
        }
        progress("Rol sessiyaları hazırdır: ${opened.keys.joinToString { ExplorerTexts.role(it) }} (run ${setup.runId}).")
        val owner = identities.firstOrNull { it.registration == RegistrationMode.OWNER }?.email
        return RoleSessions(
            sessions = opened,
            testCheck = OracleTestTargetCheck(container.oracle, container.config.target, owner),
            note = null,
        ) {
            withContext(NonCancellable) {
                closeAll(opened.values)
                tearDown(setup.runId)
            }
        }
    }

    /** Why nothing may be created for [request]; null when a test company may be. */
    private fun refusal(request: RoleSessionRequest): String? {
        val configured = container.config.target
        return when {
            !request.allowWrites -> {
                "Rollarla gəzinti daxil olmuş sessiya istəyir. “Sınaq toxunuşu”nu açsanız, Pətək test API-si olan hədəfdə " +
                    "müvəqqəti test şirkəti yaradır, onunla gəzir və sonda silir."
            }

            !PanelTargets.sameSite(request.target, configured) -> {
                "Test şirkəti yalnız konfiqurasiya olunmuş hədəfdə (${PanelTargets.site(configured)}) yaradılır; " +
                    "bu sayt üçün rollarla gəzinti buraxıldı."
            }

            !container.oracle.isAvailable -> {
                "PETEK_TEST_TOKEN qurulmayıb: test şirkəti yaradılmır və silinə bilməz, ona görə rollarla gəzinti buraxıldı."
            }

            else -> {
                null
            }
        }
    }

    /**
     * Whether the site has companies: its profile's `tenant`, else companies when the test API is there to confirm the
     * test company (the contract shape), else none (Faza 13).
     */
    private fun tenantOf(request: RoleSessionRequest): Tenant =
        container.config
            .profileFor(request.target)
            ?.spec
            ?.tenant ?: if (container.oracle.isAvailable) Tenant.COMPANY else Tenant.NONE

    /** A site without companies: the explorer alone signs up through the profile's `sign_up` flow, as role `explorer`. */
    private fun selfSignUp(profile: TargetProfile): Campaign {
        val explorer = checkNotNull(Role.fromKey(EXPLORER))
        val campaign =
            Campaign(
                settings =
                    CampaignSettings(
                        target = container.config.target,
                        testers = 1,
                        seed = SEED,
                        names = emptyList(),
                        roles = RoleQuota.of(mapOf(explorer to 1)),
                        departments = emptyList(),
                        registration = RegistrationQuota.selfSignUp(1),
                        budget = Budget(maxStepsPerAgent = MAX_STEPS, maxMinutes = MAX_MINUTES),
                        onFail = OnFail.ABORT,
                        name = NAME,
                        tenant = Tenant.NONE,
                    ),
                target = profile,
                setup =
                    listOf(
                        ScenarioStep(
                            "sign_up",
                            StepPhase.SETUP,
                            DefaultActorExpressionParser().parse(EXPLORER),
                            StepAction.Run(REGISTER_AND_LOGIN),
                            null,
                            null,
                            false,
                            emptyList(),
                            null,
                            1,
                        ),
                    ),
                steps = emptyList(),
                sourceHash = SOURCE_HASH,
            )
        val issues = DefaultCampaignValidator(container.templateRenderer).validate(campaign, container.knownRunFunctions)
        check(issues.isEmpty()) { "the explorer's sign-up campaign is invalid: $issues" }
        return campaign
    }

    /** Admin, one manager and one employee (by invitation and with the company code), all by run functions over [profile]. */
    private fun campaign(
        request: RoleSessionRequest,
        profile: TargetProfile,
        ownerOnly: Boolean = false,
    ): Campaign {
        val department =
            request.departments.firstOrNull { it.isNotBlank() && it.none { c -> c in DefaultActorExpressionParser.RESERVED_CHARS } }
                ?: DEPARTMENT
        val parser = DefaultActorExpressionParser()
        val allSteps =
            listOf(
                ScenarioStep(
                    "owner_signup",
                    StepPhase.SETUP,
                    parser.parse("admin"),
                    StepAction.Run(REGISTER_OWNER),
                    null,
                    null,
                    false,
                    emptyList(),
                    null,
                    1,
                ),
                ScenarioStep(
                    "seed",
                    StepPhase.SETUP,
                    parser.parse("admin"),
                    StepAction.Run(SEED_COMPANY),
                    null,
                    null,
                    false,
                    emptyList(),
                    null,
                    2,
                ),
                ScenarioStep(
                    "join",
                    StepPhase.SETUP,
                    parser.parse("employee[*] | manager[*]"),
                    StepAction.Run(REGISTER_AND_LOGIN),
                    null,
                    null,
                    false,
                    emptyList(),
                    null,
                    LINE_JOIN,
                ),
            )
        val steps = if (ownerOnly) allSteps.take(1) else allSteps
        val campaign =
            Campaign(
                settings =
                    CampaignSettings(
                        target = container.config.target,
                        testers = if (ownerOnly) 1 else TESTERS,
                        seed = SEED,
                        names = emptyList(),
                        roles =
                            if (ownerOnly) {
                                RoleQuota(
                                    admin = 1,
                                    manager = 0,
                                    employee = 0,
                                )
                            } else {
                                RoleQuota(admin = 1, manager = 1, employee = 1)
                            },
                        departments = listOf(department),
                        registration =
                            if (ownerOnly) {
                                RegistrationQuota(
                                    invite = 0,
                                    companyCode = 0,
                                )
                            } else {
                                RegistrationQuota(invite = 1, companyCode = 1)
                            },
                        budget = Budget(maxStepsPerAgent = MAX_STEPS, maxMinutes = MAX_MINUTES),
                        onFail = OnFail.ABORT,
                        name = NAME,
                    ),
                target = profile,
                setup = steps,
                steps = emptyList(),
                sourceHash = SOURCE_HASH,
            )
        val issues = DefaultCampaignValidator(container.templateRenderer).validate(campaign, container.knownRunFunctions)
        check(issues.isEmpty()) { "the explorer's session campaign is invalid: $issues" }
        return campaign
    }

    private suspend fun closeAll(sessions: Collection<BrowserSession>) {
        sessions.forEach { session ->
            try {
                session.close()
            } catch (e: Exception) {
                logger.warn(e) { "Closing the explorer's ${session.label} session failed" }
            }
        }
    }

    private suspend fun tearDown(runId: RunId) {
        try {
            val result = teardown.teardown(runId)
            if (result.failures.isNotEmpty()) {
                logger.warn {
                    "Teardown of the explorer's test company (run $runId) failed: ${result.failures}"
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Teardown of the explorer's test company (run $runId) failed" }
        }
    }

    companion object {
        /** The run that creates the explorer's test company is called so on the board and in the history. */
        const val NAME = "kesfiyyat-sessiyalari"
        private const val DEPARTMENT = "IT"
        private const val TESTERS = 3
        private const val SEED = 42L
        private const val MAX_STEPS = 20
        private const val MAX_MINUTES = 10
        private const val LINE_JOIN = 3
        private const val REGISTER_OWNER = "register_owner"
        private const val SEED_COMPANY = "seed_company"
        private const val REGISTER_AND_LOGIN = "register_and_login"
        private const val EXPLORER = AppContainer.EXPLORER_ROLE

        /** Identifies the setup-only campaign in run records; it is written in code, not loaded from a file. */
        private const val SOURCE_HASH = "explorer-role-sessions-v1"
    }
}

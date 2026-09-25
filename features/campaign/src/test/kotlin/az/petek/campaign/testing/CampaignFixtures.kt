package az.petek.campaign.testing

import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.Budget
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignSettings
import az.petek.campaign.domain.DefaultActorExpressionParser
import az.petek.campaign.domain.EmitSpec
import az.petek.campaign.domain.IdSource
import az.petek.campaign.domain.OnFail
import az.petek.campaign.domain.RegistrationQuota
import az.petek.campaign.domain.RoleQuota
import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.SourceLines
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.WaitForSpec
import java.net.URI
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Run functions the agent feature provides; the real kadrohr campaign uses some of them. */
val KNOWN_RUN_FUNCTIONS: Set<String> =
    setOf("seed_company", "register_and_login", "register_owner", "login", "read_email_code", "verify_identity")

/** `scenarios/kadrohr.yaml` in the repository (the module's test task passes the repository root). */
fun kadrohrScenario(): Path =
    Path
        .of(requireNotNull(System.getProperty("petek.repoRoot")) { "system property petek.repoRoot is not set" })
        .resolve("scenarios/kadrohr.yaml")

private val parser = DefaultActorExpressionParser()

/** A valid 10-tester setup: 1 admin, 3 managers, 6 employees in IT and HR. */
fun settings(
    testers: Int = 10,
    roles: RoleQuota = RoleQuota(admin = 1, manager = 3, employee = 6),
    registration: RegistrationQuota = RegistrationQuota(invite = 5, companyCode = 4),
    departments: List<String> = listOf("IT", "HR"),
    names: List<String> = listOf("Əli", "Vəli"),
    budget: Budget = Budget(maxStepsPerAgent = 60, maxMinutes = 40),
    target: URI = URI("https://staging.kadrohr.test"),
): CampaignSettings =
    CampaignSettings(
        target = target,
        testers = testers,
        seed = 42,
        names = names,
        roles = roles,
        departments = departments,
        registration = registration,
        budget = budget,
        onFail = OnFail.CONTINUE,
        name = "fixture",
    )

fun step(
    id: String,
    actor: String = "admin",
    action: StepAction = StepAction.Do("Elan yarat"),
    emits: String? = null,
    idSource: IdSource? = null,
    waitFor: String? = null,
    waitTimeout: Duration = 30.seconds,
    parallel: Boolean = false,
    assertions: List<AssertionSpec> = emptyList(),
    phase: StepPhase = StepPhase.MAIN,
    line: Int = 100,
): ScenarioStep =
    ScenarioStep(
        id = id,
        phase = phase,
        actors = parser.parse(actor),
        action = action,
        emits = emits?.let { EmitSpec(it, idSource) },
        waitFor = waitFor?.let { WaitForSpec(it, waitTimeout) },
        parallel = parallel,
        assertions = assertions,
        onFail = null,
        line = line,
    )

fun campaign(
    vararg steps: ScenarioStep,
    setup: List<ScenarioStep> = emptyList(),
    settings: CampaignSettings = settings(),
    target: TargetProfile = TargetProfile.DEFAULT,
    sourceLines: SourceLines = SourceLines.NONE,
): Campaign = Campaign(settings, target, setup, steps.toList(), sourceHash = "0".repeat(64), sourceLines = sourceLines)

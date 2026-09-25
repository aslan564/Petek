package az.petek.campaign.domain

import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import java.net.URI
import kotlin.time.Duration

/** A validated campaign: who tests, against what, and which scenario steps they run. */
data class Campaign(
    val settings: CampaignSettings,
    val target: TargetProfile,
    val setup: List<ScenarioStep>,
    val steps: List<ScenarioStep>,
    /** SHA-256 of the source file; identifies the campaign version in run records. */
    val sourceHash: String,
    /** Where settings, assertions and id sources were declared, for validation messages. Empty for code-built campaigns. */
    val sourceLines: SourceLines = SourceLines.NONE,
) {
    val allSteps: List<ScenarioStep> get() = setup + steps
}

data class CampaignSettings(
    val target: URI,
    val testers: Int,
    val seed: Long,
    /** Names given by the user; used first, then the built-in name catalog. */
    val names: List<String>,
    val roles: RoleQuota,
    val departments: List<String>,
    val registration: RegistrationQuota,
    val budget: Budget,
    val onFail: OnFail,
    /** Campaign name from the YAML (`campaign.name`), default = file name without extension. */
    val name: String = "campaign",
)

data class RoleQuota(
    val admin: Int,
    val manager: Int,
    val employee: Int,
) {
    val total: Int get() = admin + manager + employee

    fun count(role: Role): Int =
        when (role) {
            Role.ADMIN -> admin
            Role.MANAGER -> manager
            Role.EMPLOYEE -> employee
        }
}

/**
 * How non-admin testers join the company. `invite + companyCode` must equal managers + employees.
 * The YAML may omit it; the loader then splits evenly (invite gets the extra one).
 */
data class RegistrationQuota(
    val invite: Int,
    val companyCode: Int,
) {
    fun count(mode: RegistrationMode): Int =
        when (mode) {
            RegistrationMode.OWNER -> 0
            RegistrationMode.INVITE -> invite
            RegistrationMode.COMPANY_CODE -> companyCode
        }
}

data class Budget(
    val maxStepsPerAgent: Int,
    val maxMinutes: Int,
)

enum class OnFail { CONTINUE, ABORT }

enum class StepPhase { SETUP, MAIN }

data class ScenarioStep(
    /** Unique within the campaign. Generated as `setup-1`, `step-3`, ... when the YAML omits it. */
    val id: String,
    val phase: StepPhase,
    val actors: ActorExpression,
    val action: StepAction,
    val emits: EmitSpec?,
    val waitFor: WaitForSpec?,
    val parallel: Boolean,
    val assertions: List<AssertionSpec>,
    val onFail: OnFail?,
    /** 1-based line in the campaign file, for error messages and reports. */
    val line: Int,
)

sealed interface StepAction {
    /** Natural-language task interpreted by the LLM agent through whitelisted tools only. */
    data class Do(
        val instruction: String,
    ) : StepAction

    /** Deterministic function executed by code (no LLM). */
    data class Run(
        val function: String,
        val args: Map<String, String> = emptyMap(),
    ) : StepAction

    /** A step that only waits and/or asserts. */
    data object None : StepAction
}

/** `emits: announcement_created` or the long form with an explicit id source. */
data class EmitSpec(
    val event: String,
    val idSource: IdSource?,
)

/** Where the harness reads the id of the object a step created (so the LLM is not the source of truth). */
sealed interface IdSource {
    /** First capture group of the regex applied to the page URL after the step. */
    data class UrlRegex(
        val regex: String,
    ) : IdSource

    /** Field of an oracle response, e.g. `/test/announcements/latest?by={self.email}` + `id`. */
    data class OracleField(
        val path: String,
        val field: String,
    ) : IdSource

    /** Attribute of an element on the page, e.g. `[data-testid=announcement-item]:first-child` + `data-id`. */
    data class DomAttribute(
        val selector: String,
        val attribute: String,
    ) : IdSource

    /** Fallback: the `object_id` the agent reported with `done`. Marked as agent-reported in evidence. */
    data object AgentReport : IdSource
}

data class WaitForSpec(
    val event: String,
    val timeout: Duration,
)

/** Typed checks executed by code (CLAUDE.md rule 2). */
sealed interface AssertionSpec {
    val type: String

    data class VisibleText(
        val text: String,
        val within: Duration,
    ) : AssertionSpec {
        override val type = "visible_text"
    }

    data class NotVisible(
        val text: String?,
        val selector: String?,
    ) : AssertionSpec {
        override val type = "not_visible"
    }

    data class Oracle(
        val path: String,
        val field: String?,
        val equals: String?,
        val contains: String?,
    ) : AssertionSpec {
        override val type = "oracle"
    }

    data class HttpStatus(
        val path: String,
        val method: String,
        val equals: Int,
    ) : AssertionSpec {
        override val type = "http_status"
    }

    data class Count(
        val selector: String,
        val equals: Int,
    ) : AssertionSpec {
        override val type = "count"
    }

    data class LatencyMax(
        val max: Duration,
    ) : AssertionSpec {
        override val type = "latency_max"
    }

    data object OnlyOneSucceeds : AssertionSpec {
        override val type = "only_one_succeeds"
    }
}

package az.petek.app.panel

import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.core.ids.RunId
import az.petek.dashboard.domain.PlanStepView
import az.petek.dashboard.domain.RunPlanView
import az.petek.identity.domain.Identity
import az.petek.orchestration.domain.ActorResolver
import az.petek.orchestration.domain.DefaultActorResolver

/**
 * The plan a run of a campaign follows, as the orchestrator screen and the scenario preview show it: setup steps first,
 * then the scenario steps, each with its actor expression as written, the agents it resolves to among [Identity]s,
 * what the step does, the events it emits or waits for, whether its actors start together, and its assertions.
 * Pure; the agents come from the same [ActorResolver] the runner uses.
 */
internal object RunPlans {
    fun of(
        runId: RunId?,
        campaign: Campaign,
        identities: List<Identity>,
        resolver: ActorResolver = DefaultActorResolver(),
    ): RunPlanView {
        val roster = identities.sortedBy { it.agentId }
        return RunPlanView(
            runId = runId,
            campaignName = campaign.settings.name,
            steps = campaign.allSteps.map { step -> step(step, resolver.resolve(step.actors, roster)) },
        )
    }

    private fun step(
        step: ScenarioStep,
        actors: List<Identity>,
    ): PlanStepView {
        val action = step.action
        return PlanStepView(
            id = step.id,
            setup = step.phase == StepPhase.SETUP,
            actors = step.actors.raw,
            agentIds = actors.map { it.agentId },
            kind = if (action is StepAction.Do) "do" else "run",
            action = describe(action),
            emits = step.emits?.event,
            waitFor = step.waitFor?.event,
            parallel = step.parallel,
            assertions = step.assertions.map(::describe),
        )
    }

    fun describe(action: StepAction): String =
        when (action) {
            is StepAction.Do -> {
                action.instruction
            }

            is StepAction.Run -> {
                if (action.args.isEmpty()) {
                    action.function
                } else {
                    action.function + action.args.entries.joinToString(", ", "(", ")") { (key, value) -> "$key=$value" }
                }
            }

            StepAction.None -> {
                "yalnız gözləyir və yoxlayır"
            }
        }

    /** One line per assertion, e.g. `visible_text 'Yeni elan' within 10s`. */
    fun describe(assertion: AssertionSpec): String =
        when (assertion) {
            is AssertionSpec.VisibleText -> "visible_text '${assertion.text}' within ${assertion.within}"
            is AssertionSpec.NotVisible -> "not_visible " + (assertion.text?.let { "'$it'" } ?: assertion.selector.orEmpty())
            is AssertionSpec.Oracle -> oracle(assertion)
            is AssertionSpec.HttpStatus -> "http_status ${assertion.method} ${assertion.path} = ${assertion.equals}"
            is AssertionSpec.Count -> "count ${assertion.selector} = ${assertion.equals}"
            is AssertionSpec.LatencyMax -> "latency_max ${assertion.max}"
            is AssertionSpec.OnlyOneSucceeds -> "only_one_succeeds" + (assertion.request?.let { " ${it.describe()}" } ?: "")
        }

    private fun oracle(assertion: AssertionSpec.Oracle): String =
        buildString {
            append("oracle ").append(assertion.path)
            assertion.field?.let { append(' ').append(it) }
            assertion.equals?.let { append(" = '").append(it).append('\'') }
            assertion.contains?.let { append(" contains '").append(it).append('\'') }
        }
}

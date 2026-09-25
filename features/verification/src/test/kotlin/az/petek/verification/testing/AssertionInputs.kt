package az.petek.verification.testing

import az.petek.browser.domain.BrowserSession
import az.petek.campaign.domain.TemplateContext
import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.ids.StepId
import az.petek.core.time.HarnessTimestamp
import az.petek.verification.domain.AssertionInput

/** Templates of a typical receiver: `{last_id}` = 42, `{self.email}`, `{self.name}`, `{event.ticket_created.id}` = t7. */
val DEFAULT_TEMPLATES =
    TemplateContext(
        lastId = "42",
        self = mapOf("email" to "a01.k7x2@test.kadrohr.com", "name" to "Aysel Məmmədova", "agent_id" to "a01"),
        eventIds = mapOf("ticket_created" to "t7"),
    )

fun assertionInput(
    session: BrowserSession?,
    eventEmittedAt: HarnessTimestamp? = null,
    agentId: AgentId? = AgentId("a01"),
    templates: TemplateContext = DEFAULT_TEMPLATES,
    scenarioStep: String = "read_announce",
    stepId: StepId = StepId("stp_read"),
    runId: RunId = RunId("run_test"),
): AssertionInput =
    AssertionInput(
        runId = runId,
        stepId = stepId,
        scenarioStep = scenarioStep,
        agentId = agentId,
        session = session,
        templates = templates,
        eventEmittedAt = eventEmittedAt,
    )

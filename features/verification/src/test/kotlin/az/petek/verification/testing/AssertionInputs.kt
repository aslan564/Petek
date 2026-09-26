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
        self = mapOf("email" to "a01.k7x2@test.portal.example", "name" to "Aysel Məmmədova", "agent_id" to "a01"),
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

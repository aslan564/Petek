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

package az.petek.campaign.domain

import az.petek.core.model.Role
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class ExpectsRefusalTest {
    private fun step(vararg assertions: AssertionSpec) =
        ScenarioStep(
            id = "forbidden",
            phase = StepPhase.MAIN,
            actors = ActorExpression(listOf(ActorSelector(Role.EMPLOYEE)), "employee[*]"),
            action = StepAction.Do("Ticketi approve etməyə çalış"),
            emits = null,
            waitFor = null,
            parallel = false,
            assertions = assertions.toList(),
            onFail = null,
            line = 1,
        )

    @Test
    fun `not_visible and a 401 or 403 http_status test a refusal`() {
        AssertionSpec.NotVisible(null, "[data-testid=\"ticket-approve\"]").expectsRefusal shouldBe true
        AssertionSpec.HttpStatus("/api/tickets/{last_id}/approve", "POST", 403).expectsRefusal shouldBe true
        AssertionSpec.HttpStatus("/api/admin", "GET", 401).expectsRefusal shouldBe true
    }

    @Test
    fun `other assertions do not`() {
        AssertionSpec.HttpStatus("/api/tickets", "GET", 200).expectsRefusal shouldBe false
        AssertionSpec.HttpStatus("/api/tickets/x", "GET", 404).expectsRefusal shouldBe false
        AssertionSpec.VisibleText("Sabah 10:00", 5.seconds).expectsRefusal shouldBe false
        AssertionSpec.Oracle("/test/tickets/{last_id}", "status", "approved", null).expectsRefusal shouldBe false
        AssertionSpec.LatencyMax(5.seconds).expectsRefusal shouldBe false
        AssertionSpec.Count("tr", 3).expectsRefusal shouldBe false
        AssertionSpec.OnlyOneSucceeds().expectsRefusal shouldBe false
    }

    @Test
    fun `a step expects a refusal when any of its assertions does`() {
        step(AssertionSpec.VisibleText("Ticket", 5.seconds), AssertionSpec.HttpStatus("/api/x", "POST", 403)).expectsRefusal shouldBe true
        step(AssertionSpec.NotVisible("Approve", null)).expectsRefusal shouldBe true
        step(AssertionSpec.VisibleText("Ticket", 5.seconds)).expectsRefusal shouldBe false
        step().expectsRefusal shouldBe false
    }
}

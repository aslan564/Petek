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

package az.petek.agent.application

import az.petek.agent.domain.JsonDecisionProtocol
import az.petek.agent.testing.AgentTestData
import az.petek.browser.domain.PageElement
import az.petek.browser.domain.PageSnapshot
import az.petek.browser.testing.FakeBrowserSession
import az.petek.core.model.Role
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class PromptBuilderTest {
    private val protocol = JsonDecisionProtocol()
    private val prompts = PromptBuilder(protocol)
    private val identity = AgentTestData.itManager
    private val runtime = AgentTestData.runtime(FakeBrowserSession(), identity)
    private val password = identity.password.reveal()

    private val snapshot =
        PageSnapshot(
            url = "https://staging.kadrohr.test/tickets/7",
            title = "Ticket 7",
            elements =
                listOf(
                    PageElement(1, "button", "In progress", "button", "ticket-set-in-progress", null, true),
                    PageElement(2, "textbox", "Şifrə", "input", "login-password", password, true),
                ),
            visibleText = "Noutbuk işləmir — status: open",
        )

    private fun turn(history: List<ActionHistoryEntry> = emptyList()) =
        PromptBuilder.Turn(
            task = "Ticketi in-progress et",
            history = history,
            snapshot = snapshot,
            decisionNumber = history.size + 1,
            maxDecisions = 60,
            placeholders = listOf("{self.email}", "{self.password}"),
        )

    @Test
    fun `the system prompt presents the persona without the password`() {
        val system = prompts.system(runtime)
        system shouldContain "You are a QA tester"
        system shouldContain "Name: Vəli Həsənov"
        system shouldContain "Role: manager of the IT department"
        system shouldContain "E-mail: ${identity.email}"
        system shouldContain "Tester id: a02"
        system shouldContain "Write your summaries and reported problems in the language the owner's own text"
        system shouldContain "type {self.password} wherever it is needed"
        system shouldNotContain password
    }

    @Test
    fun `the system prompt states the rules and the tool reference`() {
        val system = prompts.system(runtime)
        system shouldContain "exactly one tool"
        system shouldContain "Use only the tools listed below"
        system shouldContain "{vars.email_code}"
        system shouldContain "{vars.phone_code} for the code sent to your phone (after get_phone_code)"
        system shouldContain "Stay strictly within the task"
        system shouldContain "Never try to bypass permissions"
        system shouldContain "report_problem with kind \"permission_denied\", or done with success=false"
        system shouldContain "object_id"
        system shouldContain protocol.describeTools()
    }

    @Test
    fun `the company context names the departments but no other tester, so none can be leaked`() {
        val system = prompts.system(runtime)

        system shouldContain "Departments: IT, HR, Satış"
        system shouldContain "Other testers use the same site at the same time with their own accounts"
        system shouldNotContain AgentTestData.hrManager.email
        system shouldNotContain AgentTestData.hrManager.displayName
        system shouldNotContain AgentTestData.admin.displayName
    }

    @Test
    fun `the prompt is the same size for a run of 5 testers or 500`() {
        val departments = listOf("IT", "HR", "Satış", "Maliyyə")
        val small = (2..5).map { AgentTestData.identity(it, Role.EMPLOYEE, departments[it % 4], name = "İşçi $it") }
        val large = (2..500).map { AgentTestData.identity(it, Role.EMPLOYEE, departments[it % 4], name = "İşçi $it") }
        val self = small.first()

        val a = prompts.system(AgentTestData.runtime(FakeBrowserSession(), self, listOf(AgentTestData.admin) + small))
        val b = prompts.system(AgentTestData.runtime(FakeBrowserSession(), self, listOf(AgentTestData.admin) + large))

        a shouldBe b
    }

    @Test
    fun `the system prompt is stable across turns so it can be cached`() {
        val first = prompts.system(runtime)
        runtime.variables["email_code"] = "123456"
        prompts.system(runtime) shouldBe first
        first shouldNotContain "2026"
    }

    @Test
    fun `the user turn carries task, progress, placeholders and the rendered page`() {
        val user = prompts.user(runtime, turn())
        user shouldContain "Task: Ticketi in-progress et"
        user shouldContain "Decision 1 of at most 60."
        user shouldContain "Placeholders you can type now: {self.email}, {self.password}"
        user shouldContain "Previous actions: none yet."
        user shouldContain "URL: https://staging.kadrohr.test/tickets/7"
        user shouldContain "[1] button \"In progress\" (testid=ticket-set-in-progress)"
        user shouldContain "Noutbuk işləmir"
    }

    @Test
    fun `a password echoed by the page is redacted from the user turn`() {
        val user = prompts.user(runtime, turn())
        user shouldNotContain password
        user shouldContain "value=\"{self.password}\""
    }

    @Test
    fun `only the last 12 actions are replayed, oldest first`() {
        val history = (1..14).map { ActionHistoryEntry(it, "click [$it]", "OK: clicked #$it.") }
        val user = prompts.user(runtime, turn(history))
        user shouldContain "2 earlier ones omitted"
        val replayed = user.lines().filter { it.contains(". click [") }
        replayed.size shouldBe 12
        replayed.first() shouldBe "3. click [3] -> OK: clicked #3."
        replayed.last() shouldBe "14. click [14] -> OK: clicked #14."
    }

    @Test
    fun `the history limit is configurable and must be positive`() {
        val short = PromptBuilder(protocol, historyLimit = 1)
        val history = listOf(ActionHistoryEntry(1, "navigate /a", "OK"), ActionHistoryEntry(2, "navigate /b", "OK"))
        val user = short.user(runtime, turn(history))
        user shouldNotContain "navigate /a"
        user shouldContain "2. navigate /b -> OK"
        shouldThrow<IllegalArgumentException> { PromptBuilder(protocol, historyLimit = 0) }
    }
}

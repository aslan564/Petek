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
    fun `the company context lists departments and colleagues so the agent can pick the right person`() {
        val system = prompts.system(runtime)
        system shouldContain "Departments: IT, HR, Satış"
        system shouldContain "Sahil Quliyev - manager of the HR department - ${AgentTestData.hrManager.email}"
        system shouldContain "Əli Kərimov - admin (company owner)"
        system shouldContain "Vəli Həsənov - manager of the IT department - ${identity.email} (you)"
    }

    @Test
    fun `a large run lists the most relevant people and counts the rest, so the prompt size stays bounded`() {
        val departments = listOf("IT", "HR", "Satış", "Maliyyə")
        val managers = (2..9).map { AgentTestData.identity(it, Role.MANAGER, departments[(it - 2) % 4], name = "Rəhbər $it") }
        val employees = (10..500).map { AgentTestData.identity(it, Role.EMPLOYEE, departments[it % 4], name = "İşçi $it") }
        val roster = listOf(AgentTestData.admin) + managers + employees
        val self = employees.first { it.agentId.index == 250 && it.department == "Satış" }

        val system = PromptBuilder(protocol, rosterLimit = 20).system(AgentTestData.runtime(FakeBrowserSession(), self, roster))

        val people = system.lines().filter { it.startsWith("  - ") && it.contains("@test.kadrohr.com") }
        people.size shouldBe 20
        system shouldContain "İşçi 250 - employee in the Satış department - ${self.email} (you)"
        system shouldContain "Əli Kərimov - admin (company owner)"
        managers.filter { it.department == "Satış" }.forEach { system shouldContain "${it.displayName} - manager of the Satış" }
        system shouldContain "  - … and 480 more colleagues, not listed here"
        system shouldContain "Departments: IT, HR, Satış, Maliyyə"
        val listedIds = people.mapNotNull { line -> roster.firstOrNull { line.contains(it.email) }?.agentId }
        listedIds shouldBe listedIds.sorted()
        listedIds.size shouldBe 20
        roster.filter { it.agentId in listedIds }.mapNotNull { it.department }.toSet() shouldBe setOf("Satış")
    }

    @Test
    fun `a run within the roster limit lists everyone`() {
        val roster = (1..40).map { AgentTestData.identity(it, if (it == 1) Role.ADMIN else Role.EMPLOYEE, name = "Nəfər $it") }

        val system = prompts.system(AgentTestData.runtime(FakeBrowserSession(), roster[5], roster))

        roster.forEach { system shouldContain "${it.displayName} - " }
        system shouldNotContain "more colleagues"
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

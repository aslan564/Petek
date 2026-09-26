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

package az.petek.dashboard.domain

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PanelInstructionsTest {
    private val valid =
        PanelInstructions(
            target = "https://staging.kadrohr.az",
            instructions = "Elan yaratma axınını yoxla.",
            testers = 30,
            roles = RoleSplit(admins = 1, managers = 5, employees = 24),
            departments = listOf("Satış", "Maliyyə", "IT", "Marketinq", "İnsan resursları"),
            registration = RegistrationSplit(invite = 15, companyCode = 14),
            budget = PanelBudget(maxMinutes = 30, maxStepsPerAgent = 40, maxPages = 40),
        )

    private fun fields(instructions: PanelInstructions) = instructions.problems().map { it.field }

    @Test
    fun `a complete request has no problems`() {
        valid.problems().shouldBeEmpty()
    }

    @Test
    fun `the target must be an absolute http or https address`() {
        listOf("", "staging.kadrohr.az", "ftp://kadrohr.az", "https://", "javascript:alert(1)", "http://exa mple.com").forEach {
            fields(valid.copy(target = it)) shouldContainExactly listOf(PanelInstructions.TARGET)
        }
        fields(valid.copy(target = "http://127.0.0.1:8080/app")).shouldBeEmpty()
    }

    @Test
    fun `the tester count must fit the agent ids and match the roles`() {
        fields(valid.copy(testers = 0, roles = RoleSplit(0, 0, 0), registration = RegistrationSplit(0, 0))) shouldContainExactly
            listOf(PanelInstructions.TESTERS, PanelInstructions.ROLES)
        fields(valid.copy(testers = 31)) shouldContainExactly listOf(PanelInstructions.ROLES)
        fields(valid.copy(testers = 1000, roles = RoleSplit(1, 5, 994), registration = RegistrationSplit(500, 499))) shouldContainExactly
            listOf(PanelInstructions.TESTERS)
    }

    @Test
    fun `there is always an admin and never a negative count`() {
        val noAdmin = valid.copy(roles = RoleSplit(0, 5, 25), registration = RegistrationSplit(15, 15))
        noAdmin.problems().map { it.message } shouldContainExactly listOf("Ən azı bir admin lazımdır: test şirkətini o yaradır.")
        fields(valid.copy(roles = RoleSplit(1, -1, 30), registration = RegistrationSplit(15, 14))) shouldContainExactly
            listOf(PanelInstructions.ROLES)
        fields(valid.copy(registration = RegistrationSplit(30, -1))) shouldContainExactly listOf(PanelInstructions.REGISTRATION)
    }

    @Test
    fun `departments are needed for managers and employees and must be distinct`() {
        fields(valid.copy(departments = emptyList())) shouldContainExactly listOf(PanelInstructions.DEPARTMENTS)
        fields(valid.copy(departments = listOf("IT", "it"))) shouldContainExactly listOf(PanelInstructions.DEPARTMENTS)
        fields(valid.copy(departments = listOf("IT", " "))) shouldContainExactly listOf(PanelInstructions.DEPARTMENTS)
        val adminOnly =
            valid.copy(
                testers = 1,
                roles = RoleSplit(1, 0, 0),
                departments = emptyList(),
                registration = RegistrationSplit(0, 0),
            )
        adminOnly.problems().shouldBeEmpty()
    }

    @Test
    fun `every non-admin joins one way and managers only by invitation`() {
        fields(valid.copy(registration = RegistrationSplit(15, 15))) shouldContainExactly listOf(PanelInstructions.REGISTRATION)
        val problems = valid.copy(registration = RegistrationSplit(4, 25)).problems()
        problems.map { it.message } shouldContainExactly listOf("Menecerlər yalnız dəvətlə qoşulur: dəvət sayı ən azı 5 olmalıdır.")
    }

    @Test
    fun `the budget has sane limits and long instructions are refused`() {
        fields(valid.copy(budget = PanelBudget(0, 0, 0))) shouldContainExactly
            listOf(PanelBudget.MINUTES, PanelBudget.STEPS, PanelBudget.PAGES)
        fields(valid.copy(instructions = "x".repeat(PanelInstructions.MAX_INSTRUCTION_CHARS + 1))) shouldContainExactly
            listOf(PanelInstructions.INSTRUCTIONS)
    }

    @Test
    fun `a run request names exactly one scenario or campaign and a sane tester count`() {
        RunRequest(scenarioId = "scn_1").problems().shouldBeEmpty()
        RunRequest(scenarioId = null).problems().map { it.field } shouldContainExactly listOf(RunRequest.SCENARIO)
        RunRequest(scenarioId = "scn_1", campaignPath = "scenarios/kadrohr.yaml").problems().map { it.field } shouldContainExactly
            listOf(RunRequest.SCENARIO)
        RunRequest(scenarioId = "scn_1", testers = 0).problems().map { it.field } shouldContainExactly listOf(PanelInstructions.TESTERS)
        RunRequest(scenarioId = null, campaignPath = "scenarios/kadrohr.yaml", testers = 30).problems().size shouldBe 0
    }

    @Test
    fun `a run request may name another site to run against, as a full http or https address`() {
        RunRequest(scenarioId = "scn_1", target = "https://kadrohr.com").problems().shouldBeEmpty()
        RunRequest(scenarioId = "scn_1", target = "  ").problems().shouldBeEmpty()
        RunRequest(scenarioId = "scn_1", target = null).problems().shouldBeEmpty()
        listOf("kadrohr.com", "ftp://kadrohr.com", "https://", "http://exa mple.com").forEach { target ->
            RunRequest(scenarioId = "scn_1", target = target).problems().map { it.field } shouldContainExactly
                listOf(PanelInstructions.TARGET)
        }
    }
}

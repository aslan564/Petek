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

package az.petek.agent.application.runs

import az.petek.agent.application.InMemorySharedRunState
import az.petek.agent.domain.SharedRunState
import az.petek.agent.testing.AgentTestData
import az.petek.browser.domain.RunText
import az.petek.browser.testing.FakeBrowserSession
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class RunTextsTest {
    private val session = FakeBrowserSession("a04")

    @Test
    fun `run texts are the testers' names and e-mails, the tester's phone and the company code, never a password`() {
        val shared = InMemorySharedRunState().apply { put(SharedRunState.COMPANY_CODE, "PTK-4821") }
        val self = AgentTestData.itEmployee
        val runtime = AgentTestData.runtime(session, self, shared = shared)

        val texts = RunTexts.of(runtime)

        texts.take(4) shouldBe
            listOf(
                RunText(RunTexts.TESTER_NAME, "Cəmil Əliyev"),
                RunText(RunTexts.TESTER_EMAIL, self.email),
                RunText(RunTexts.TESTER_PHONE, self.phone),
                RunText(RunTexts.COMPANY_CODE, "PTK-4821"),
            )
        texts.drop(4) shouldBe
            AgentTestData.roster.filter { it != self }.flatMap {
                listOf(RunText(RunTexts.COLLEAGUE_NAME, it.displayName), RunText(RunTexts.COLLEAGUE_EMAIL, it.email))
            }
        AgentTestData.roster.forEach { tester -> texts.none { it.text == tester.password.reveal() } shouldBe true }
        texts.toString() shouldNotContain "Cəmil"
    }

    @Test
    fun `texts too short to tell apart and repeated ones are left out, and a large team is capped`() {
        val roster =
            listOf(AgentTestData.itEmployee) +
                AgentTestData.identity(6, name = "Ol") +
                AgentTestData.identity(7, name = "cəmil əliyev") +
                (10 until 200).map { AgentTestData.identity(it) }
        val runtime = AgentTestData.runtime(session, AgentTestData.itEmployee, roster)

        val texts = RunTexts.of(runtime)

        texts.none { it.text == "Ol" } shouldBe true
        texts.count { it.text.lowercase() == "cəmil əliyev" } shouldBe 1
        texts shouldHaveSize RunTexts.MAX_TEXTS
        // Without a company code yet (a setup step before anyone signed up), there is simply none to look for.
        texts.none { it.kind == RunTexts.COMPANY_CODE } shouldBe true
    }
}

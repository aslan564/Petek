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

package az.petek.agent.domain

import az.petek.agent.testing.AgentTestData
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class ContactPolicyTest {
    private val colleagues = AgentTestData.roster.map(Colleague::of)

    /** a04, `tester.k7x2.a04@test.portal.example`, phone `+994501000004`. */
    private val own = AgentTestData.itEmployee

    private val catchAll = ContactPolicy(own, colleagues, TestMail.of("test.portal.example", null))

    @Test
    fun `the tester's own and its colleagues' addresses and its own phone can be typed`() {
        val policy = ContactPolicy(own, colleagues, TestMail.NONE)

        policy.refusal("Invite tester.k7x2.a02@test.portal.example and TESTER.k7x2.a04@TEST.portal.example").shouldBeNull()
        policy.refusal("{self.email} / {self.phone}").shouldBeNull()
        policy.refusal("Call me on +994 50 100 00 04").shouldBeNull()
    }

    @Test
    fun `any address at the catch-all test domain belongs to the team`() {
        catchAll.refusal("new.hire@test.portal.example").shouldBeNull()
    }

    @Test
    fun `an address outside the test team is refused and named, with what may be typed instead`() {
        catchAll.refusal("Invite ceo@company.example to the team") shouldBe
            "Only e-mail addresses and phone numbers of the test team can be typed, never a real person's " +
            "(not the test team's: 'ceo@company.example'). Use {self.email} or {self.phone}, an address your task names, " +
            "any address at @test.portal.example."
    }

    @Test
    fun `with the owner's inbox only its plus addresses belong to the team, not the rest of its domain`() {
        val policy = ContactPolicy(own, colleagues, TestMail.of("test.portal.example", "qa@company.example"))

        policy.refusal("qa+new-a09@company.example").shouldBeNull()
        policy.refusal("qa@company.example").shouldBeNull()
        policy.refusal("boss@company.example").shouldNotBeNull() shouldContain "'boss@company.example'"
        policy.refusal("anyone@test.portal.example").shouldNotBeNull() shouldContain "a + address of the test inbox"
    }

    @Test
    fun `a phone number that is not the tester's own is refused`() {
        catchAll.refusal("Send the code to +1 (202) 555-0147").shouldNotBeNull() shouldContain "'+1 (202) 555-0147'"
    }

    @Test
    fun `text without addresses or international numbers is never refused`() {
        catchAll.refusal("Sabah 10:00 ümumi iclas, otaq 12, 3 nəfər, 050 123 45 67").shouldBeNull()
    }
}

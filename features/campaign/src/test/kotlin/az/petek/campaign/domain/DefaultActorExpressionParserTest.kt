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

import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class DefaultActorExpressionParserTest {
    private val parser = DefaultActorExpressionParser()

    private fun selectors(raw: String) = parser.parse(raw).selectors

    private fun failure(
        raw: String,
        line: Int? = 7,
    ): ValidationIssue = shouldThrow<CampaignValidationException> { parser.parse(raw, line) }.issues.single()

    @Test
    fun `a bare role selects every tester of that role`() {
        selectors("admin") shouldContainExactly listOf(ActorSelector(Role.ADMIN))
        selectors("manager") shouldContainExactly listOf(ActorSelector(Role.MANAGER))
        selectors("employee") shouldContainExactly listOf(ActorSelector(Role.EMPLOYEE))
    }

    @Test
    fun `role names are case-insensitive`() {
        selectors("ADMIN") shouldContainExactly listOf(ActorSelector(Role.ADMIN))
        selectors("Employee[*]") shouldContainExactly listOf(ActorSelector(Role.EMPLOYEE))
    }

    @Test
    fun `a star filter is the same as no filter`() {
        selectors("employee[*]") shouldBe selectors("employee")
        selectors("employee[ * ]") shouldBe selectors("employee")
    }

    @Test
    fun `a bare filter names a department and keeps its spelling`() {
        selectors("manager[IT]") shouldContainExactly listOf(ActorSelector(Role.MANAGER, department = "IT"))
        selectors("manager[Satış]") shouldContainExactly listOf(ActorSelector(Role.MANAGER, department = "Satış"))
        selectors("manager[Human Resources]") shouldContainExactly
            listOf(ActorSelector(Role.MANAGER, department = "Human Resources"))
    }

    @Test
    fun `criteria select department, registration mode and position`() {
        selectors("employee[dept=IT, n=1]") shouldContainExactly
            listOf(ActorSelector(Role.EMPLOYEE, department = "IT", nth = 1))
        selectors("employee[reg=invite]") shouldContainExactly
            listOf(ActorSelector(Role.EMPLOYEE, registration = RegistrationMode.INVITE))
        selectors("manager[reg=company_code,dept=HR,n=2]") shouldContainExactly
            listOf(ActorSelector(Role.MANAGER, department = "HR", registration = RegistrationMode.COMPANY_CODE, nth = 2))
    }

    @Test
    fun `whitespace around tokens is ignored`() {
        selectors("  employee [ dept = IT ,  n = 3 ]  ") shouldContainExactly
            listOf(ActorSelector(Role.EMPLOYEE, department = "IT", nth = 3))
    }

    @Test
    fun `criterion keys are case-insensitive`() {
        selectors("employee[DEPT=IT, N=1, Reg=Invite]") shouldContainExactly
            listOf(ActorSelector(Role.EMPLOYEE, department = "IT", registration = RegistrationMode.INVITE, nth = 1))
    }

    @Test
    fun `a union joins selectors in order`() {
        val expression = parser.parse("employee[*] | manager[*]")
        expression.selectors shouldContainExactly listOf(ActorSelector(Role.EMPLOYEE), ActorSelector(Role.MANAGER))
        expression.raw shouldBe "employee[*] | manager[*]"
    }

    @Test
    fun `the raw text is kept trimmed`() {
        parser.parse("  admin  ").raw shouldBe "admin"
    }

    @Test
    fun `a list of selectors is a union and its raw text is re-parseable`() {
        val expression = parser.parseList(listOf("manager[IT]", " manager[HR] "))
        expression.selectors shouldContainExactly
            listOf(ActorSelector(Role.MANAGER, department = "IT"), ActorSelector(Role.MANAGER, department = "HR"))
        expression.raw shouldBe "manager[IT] | manager[HR]"
        parser.parse(expression.raw).selectors shouldBe expression.selectors
    }

    @Test
    fun `list items may themselves be unions`() {
        parser.parseList(listOf("admin | manager[IT]", "employee[n=1]")).selectors shouldContainExactly
            listOf(ActorSelector(Role.ADMIN), ActorSelector(Role.MANAGER, department = "IT"), ActorSelector(Role.EMPLOYEE, nth = 1))
    }

    @Test
    fun `a text that cannot name a role is rejected with the raw text and line`() {
        val issue = failure("9boss[IT]", line = 12)
        issue.line shouldBe 12
        issue.message shouldContain "invalid actor '9boss[IT]'"
        issue.message shouldContain "'9boss' is not a role name"
    }

    @Test
    fun `a campaign may name its own roles`() {
        parser.parse("editor[n=2] | reader[reg=guest]").selectors shouldContainExactly
            listOf(
                ActorSelector(checkNotNull(Role.fromKey("editor")), nth = 2),
                ActorSelector(checkNotNull(Role.fromKey("reader")), registration = RegistrationMode.GUEST),
            )
    }

    @Test
    fun `a filter without a role is rejected`() {
        failure("[IT]").message shouldContain "does not start with a role"
    }

    @Test
    fun `empty text is rejected`() {
        failure("   ").message shouldContain "empty"
    }

    @Test
    fun `an empty list is rejected`() {
        shouldThrow<CampaignValidationException> { parser.parseList(emptyList(), 4) }.issues.single().line shouldBe 4
    }

    @Test
    fun `a blank list item is rejected`() {
        shouldThrow<CampaignValidationException> { parser.parseList(listOf("admin", " "), 4) }
            .issues
            .single()
            .message shouldContain "empty"
    }

    @Test
    fun `dangling union bars are rejected`() {
        failure("admin |").message shouldContain "empty selector"
        failure("| admin").message shouldContain "empty selector"
        failure("admin || manager").message shouldContain "empty selector"
    }

    @Test
    fun `unbalanced or nested brackets are rejected`() {
        failure("manager[IT").message shouldContain "not closed"
        failure("manager]IT").message shouldContain "without a matching"
        failure("manager[[IT]]").message shouldContain "nested"
        failure("manager[IT]x").message shouldContain "after ']'"
        failure("manager[IT][HR]").message shouldContain "after ']'"
    }

    @Test
    fun `a bar inside brackets is rejected`() {
        failure("manager[IT|HR]").message shouldContain "'|' is not allowed inside"
    }

    @Test
    fun `an empty filter is rejected`() {
        failure("manager[]").message shouldContain "empty filter"
        failure("manager[ ]").message shouldContain "empty filter"
    }

    @Test
    fun `n must be a positive integer`() {
        failure("employee[n=0]").message shouldContain "n must be a positive integer"
        failure("employee[n=-2]").message shouldContain "n must be a positive integer"
        failure("employee[n=first]").message shouldContain "n must be a positive integer"
        failure("employee[n=]").message shouldContain "n must be a positive integer"
    }

    @Test
    fun `reg accepts only the joining modes and the gates`() {
        failure("employee[reg=owner]").message shouldContain "reg must be invite, company_code, self, login or guest"
        failure("employee[reg=email]").message shouldContain "reg must be invite, company_code, self, login or guest"
    }

    @Test
    fun `dept needs a value`() {
        failure("employee[dept=]").message shouldContain "dept needs a department name"
    }

    @Test
    fun `unknown and repeated criteria are rejected`() {
        failure("employee[team=IT]").message shouldContain "unknown criterion 'team'"
        failure("employee[n=1, n=2]").message shouldContain "given twice"
    }

    @Test
    fun `a department mixed with criteria must use dept=`() {
        failure("employee[IT, n=1]").message shouldContain "use dept=IT"
    }

    @Test
    fun `an empty criterion is rejected`() {
        failure("employee[dept=IT,]").message shouldContain "empty criterion"
    }

    @Test
    fun `grammar characters cannot appear in a department`() {
        failure("employee[I*T]").message shouldContain "department 'I*T'"
        failure("employee[dept=a=b]").message shouldContain "department 'a=b'"
    }

    @Test
    fun `the line is optional`() {
        failure("9boss", line = null).line shouldBe null
    }
}

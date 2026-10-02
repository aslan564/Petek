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

package az.petek.identity.domain

import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.security.Secret
import az.petek.identity.IdentityTestData
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class TenantlessIdentityTest {
    private val editor = checkNotNull(Role.fromKey("editor"))
    private val reader = checkNotNull(Role.fromKey("reader"))

    private fun spec(
        gates: Map<RegistrationMode, Int>,
        accounts: List<GivenAccount> = emptyList(),
        names: List<String> = emptyList(),
    ) = IdentityTestData
        .spec(
            testers = 4,
            names = names,
            admins = 0,
            managers = 0,
            employees = 0,
            departments = emptyList(),
            inviteCount = 0,
        ).copy(
            companies = false,
            ownRoles = linkedMapOf(editor to 1, reader to 3),
            gates = gates,
            accounts = accounts,
        )

    @Test
    fun `roles follow the campaign and nobody is an admin or in a department`() {
        val plan =
            IdentityTestData.generator().generate(
                spec(mapOf(RegistrationMode.SELF to 3, RegistrationMode.GUEST to 1)),
                IdentityTestData.RUN_TAG,
            )

        plan.identities.map { it.role } shouldContainExactly listOf(editor, reader, reader, reader)
        plan.identities.map { it.department }.toSet() shouldBe setOf(null)
        plan.identities.map { it.registration } shouldContainExactly
            listOf(RegistrationMode.SELF, RegistrationMode.SELF, RegistrationMode.SELF, RegistrationMode.GUEST)
    }

    @Test
    fun `login testers take the owner's accounts of their role`() {
        val account = GivenAccount(reader, "owner-reader@example.com", Secret("given-password"), "Reader One")

        val plan =
            IdentityTestData.generator().generate(
                spec(mapOf(RegistrationMode.SELF to 3, RegistrationMode.LOGIN to 1), listOf(account)),
                IdentityTestData.RUN_TAG,
            )

        val login = plan.identities.single { it.registration == RegistrationMode.LOGIN }
        login.role shouldBe reader
        login.email shouldBe "owner-reader@example.com"
        login.displayName shouldBe "Reader One"
        login.password.reveal() shouldBe "given-password"
    }

    @Test
    fun `more login testers than accounts cannot start`() {
        shouldThrow<IdentityConflictException> {
            IdentityTestData.generator().generate(
                spec(mapOf(RegistrationMode.SELF to 2, RegistrationMode.LOGIN to 2)),
                IdentityTestData.RUN_TAG,
            )
        }.message shouldContain "2 testers sign in with the owner's accounts, but only 0 accounts match their roles"
    }

    @Test
    fun `a login tester whose account has the name another tester is given cannot start`() {
        // a01, the editor, is given the name; a02, the first reader, signs in with the account and keeps its name.
        val account = GivenAccount(reader, "owner-reader@example.com", Secret("given-password"), "Leyla Əliyeva")

        val error =
            shouldThrow<IdentityConflictException> {
                IdentityTestData.generator().generate(
                    spec(mapOf(RegistrationMode.SELF to 3, RegistrationMode.LOGIN to 1), listOf(account), names = listOf("Leyla Əliyeva")),
                    IdentityTestData.RUN_TAG,
                )
            }

        error.message shouldContain "Identity registry cannot be built: duplicate display name Leyla Əliyeva (a01, a02)"
        error.message shouldContain "testers who sign in with the owner's accounts keep the accounts' names and e-mails"
        error.message shouldNotContain "given-password"
    }

    @Test
    fun `two login testers on accounts with the same e-mail in another case cannot start`() {
        val accounts =
            listOf(
                GivenAccount(editor, "Owner@Example.com", Secret("editor-password")),
                GivenAccount(reader, "owner@example.com", Secret("reader-password")),
            )

        val error =
            shouldThrow<IdentityConflictException> {
                IdentityTestData.generator().generate(
                    spec(mapOf(RegistrationMode.SELF to 2, RegistrationMode.LOGIN to 2), accounts),
                    IdentityTestData.RUN_TAG,
                )
            }

        error.message shouldContain "Identity registry cannot be built: duplicate e-mail owner@example.com (a01, a02)"
        error.message shouldNotContain "display name"
    }
}

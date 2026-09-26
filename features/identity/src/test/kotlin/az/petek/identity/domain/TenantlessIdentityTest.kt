/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
import org.junit.jupiter.api.Test

class TenantlessIdentityTest {
    private val editor = checkNotNull(Role.fromKey("editor"))
    private val reader = checkNotNull(Role.fromKey("reader"))

    private fun spec(
        gates: Map<RegistrationMode, Int>,
        accounts: List<GivenAccount> = emptyList(),
    ) = IdentityTestData
        .spec(
            testers = 4,
            names = emptyList(),
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
}

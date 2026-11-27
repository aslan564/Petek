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

package az.petek.identity

import az.petek.core.ids.RunTag
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import az.petek.core.security.Secret
import az.petek.identity.domain.AzerbaijaniNameCatalog
import az.petek.identity.domain.DefaultIdentityRegistryGenerator
import az.petek.identity.domain.GivenAccount
import az.petek.identity.domain.HmacPasswordDeriver
import az.petek.identity.domain.IdentitySpec
import az.petek.identity.domain.NameCatalog

/**
 * The company portal example's identity settings (docs/examples/company-portal.yaml) and helpers to vary them. By default half of the
 * non-admins (the odd one included) are invited, but never fewer than the managers, who always join by invitation.
 */
object IdentityTestData {
    val RUN_TAG = RunTag("k7x2")
    val OTHER_RUN_TAG = RunTag("m3q9")
    val DEPARTMENTS = listOf("IT", "HR", "Satış", "Maliyyə", "Əməliyyat")
    val GIVEN_NAMES = listOf("Əli", "Vəli", "Sahil", "Cəmil", "Amil")
    const val MAIL_DOMAIN = "test.portal.example"

    fun spec(
        testers: Int = 30,
        seed: Long = 42,
        names: List<String> = GIVEN_NAMES,
        admins: Int = 1,
        managers: Int = 5,
        employees: Int = testers - admins - managers,
        departments: List<String> = DEPARTMENTS,
        inviteCount: Int = maxOf(managers, (managers + employees + 1) / 2),
        companyCodeCount: Int = managers + employees - inviteCount,
        mailDomain: String = MAIL_DOMAIN,
    ) = IdentitySpec(
        testers = testers,
        seed = seed,
        names = names,
        admins = admins,
        managers = managers,
        employees = employees,
        departments = departments,
        inviteCount = inviteCount,
        companyCodeCount = companyCodeCount,
        mailDomain = mailDomain,
    )

    const val OWNER_EMAIL = "owner-reader@example.com"
    const val OWNER_PASSWORD = "owner-given-password"
    val READER: Role = checkNotNull(Role.fromKey("reader"))

    /**
     * Four testers of a site without companies: an editor and three readers, one of whom signs in with the owner's
     * reader account ([OWNER_EMAIL]); the others sign up with e-mails of their run.
     */
    fun ownAccountSpec(): IdentitySpec =
        spec(testers = 4, names = emptyList(), admins = 0, managers = 0, employees = 0, departments = emptyList(), inviteCount = 0)
            .copy(
                companies = false,
                ownRoles = linkedMapOf(checkNotNull(Role.fromKey("editor")) to 1, READER to 3),
                gates = mapOf(RegistrationMode.SELF to 3, RegistrationMode.LOGIN to 1),
                accounts = listOf(GivenAccount(READER, OWNER_EMAIL, Secret(OWNER_PASSWORD), "Reader One")),
            )

    fun generator(
        catalog: NameCatalog = AzerbaijaniNameCatalog,
        secret: String = "unit-test-secret",
    ) = DefaultIdentityRegistryGenerator(catalog, HmacPasswordDeriver(secret.toByteArray()))

    /** A catalog small enough to reason about in tests. */
    class SmallCatalog(
        override val firstNames: List<String>,
        override val surnames: List<String>,
    ) : NameCatalog
}

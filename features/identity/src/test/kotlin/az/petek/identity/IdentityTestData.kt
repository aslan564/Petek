/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.identity

import az.petek.core.ids.RunTag
import az.petek.identity.domain.AzerbaijaniNameCatalog
import az.petek.identity.domain.DefaultIdentityRegistryGenerator
import az.petek.identity.domain.HmacPasswordDeriver
import az.petek.identity.domain.IdentitySpec
import az.petek.identity.domain.NameCatalog

/**
 * The KadroHR campaign's identity settings (scenarios/kadrohr.yaml) and helpers to vary them. By default half of the
 * non-admins (the odd one included) are invited, but never fewer than the managers, who always join by invitation.
 */
object IdentityTestData {
    val RUN_TAG = RunTag("k7x2")
    val OTHER_RUN_TAG = RunTag("m3q9")
    val DEPARTMENTS = listOf("IT", "HR", "Satış", "Maliyyə", "Əməliyyat")
    val GIVEN_NAMES = listOf("Əli", "Vəli", "Sahil", "Cəmil", "Amil")
    const val MAIL_DOMAIN = "test.kadrohr.com"

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

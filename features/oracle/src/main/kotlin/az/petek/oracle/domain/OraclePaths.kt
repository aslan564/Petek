/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.oracle.domain

/**
 * Where the target's test API answers the oracle's own questions (docs/TARGET_CONTRACT.md §4 by default). A site
 * whose test API lives elsewhere names its paths in its target profile (`test_api.paths`); `{phone}`, `{owner}` and
 * `{id}` are filled in (percent-encoded) by the oracle.
 */
data class OraclePaths(
    val otp: String = "/test/otp/{phone}",
    val companyByOwner: String = "/test/companies?owner={owner}",
    val company: String = "/test/companies/{id}",
    val seedCompany: String = "/test/companies/seed",
) {
    init {
        listOf(otp, companyByOwner, company, seedCompany).forEach {
            require(it.startsWith("/")) { "oracle paths are paths on the target, starting with '/', was '$it'" }
        }
        require("{phone}" in otp) { "the otp path needs {phone}" }
        require("{owner}" in companyByOwner) { "the company_by_owner path needs {owner}" }
        require("{id}" in company) { "the company path needs {id}" }
    }

    companion object {
        /** docs/TARGET_CONTRACT.md §4. */
        val CONTRACT: OraclePaths = OraclePaths()

        /** Keys a target profile may set, as written in YAML. */
        val KEYS: Set<String> = setOf("otp", "company_by_owner", "company", "seed_company")

        /** [CONTRACT] with the profile's [overrides] (keys of [KEYS]). */
        fun of(overrides: Map<String, String>): OraclePaths =
            OraclePaths(
                otp = overrides["otp"] ?: CONTRACT.otp,
                companyByOwner = overrides["company_by_owner"] ?: CONTRACT.companyByOwner,
                company = overrides["company"] ?: CONTRACT.company,
                seedCompany = overrides["seed_company"] ?: CONTRACT.seedCompany,
            )
    }
}

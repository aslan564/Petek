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

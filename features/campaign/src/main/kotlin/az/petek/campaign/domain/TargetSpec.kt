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

import az.petek.core.error.PetekException
import java.net.URI

/**
 * One site Pətək knows (`targets/<name>.yaml`, ADR-0010): where it lives, where its test API is, which hosts are
 * production, how mail reaches the testers, how the explorer and testers get in, and which campaign profile holds its
 * selectors and flows. Several profiles let one panel test several sites; `PETEK_TARGET` names the default one.
 *
 * Secrets are never values here: [testToken] and account passwords are [SecretRef]s to `.env` variables, resolved by
 * the composition root (CLAUDE.md rule 10).
 */
data class TargetSpec(
    val name: String,
    val url: URI,
    val apiUrl: URI? = null,
    val productionHosts: Set<String> = emptySet(),
    val mail: TargetMail = TargetMail(),
    val testToken: SecretRef? = null,
    val signIn: List<SignInMethod> = SignInMethod.DEFAULT_CHAIN,
    val accounts: List<OwnAccount> = emptyList(),
    /** A campaign file (optionally `#target_profile`) whose `target_profile` holds this site's selectors and flows. */
    val profile: String? = null,
    /** `test_api.mode: none`: the site has no test API; oracle checks are "N/A (no oracle)" and nothing is seeded. */
    val oracle: Boolean = true,
    /** `test_api.paths`: where the site's test API answers the oracle (keys `otp`, `company_by_owner`, `company`, `seed_company`). */
    val oraclePaths: Map<String, String> = emptyMap(),
    /** `tenant: company | none`; null lets Pətək decide (companies when the site's test API can seed one). */
    val tenant: Tenant? = null,
) {
    init {
        require(NAME.matches(name)) { "a target name is lower-case letters, digits and '-', was '$name'" }
        require(signIn.isNotEmpty() && signIn.distinct() == signIn) { "sign_in lists each method once" }
    }

    companion object {
        val NAME = Regex("[a-z0-9][a-z0-9-]{0,62}")
    }
}

/** How mail reaches this site's testers; null keeps the `.env` setting (`PETEK_MAIL_*`). */
data class TargetMail(
    /** `mailpit`, `test-api`, `imap` or `manual`. */
    val source: String? = null,
    val domain: String? = null,
    /** The owner's own box; testers get its `+` addresses. */
    val inbox: String? = null,
)

/** A `${NAME}` reference to a variable of `.env` or the environment; the value itself never appears in a profile. */
@JvmInline
value class SecretRef(
    val variable: String,
) {
    init {
        require(VARIABLE.matches(variable)) { "a secret reference names an environment variable, was '$variable'" }
    }

    override fun toString(): String = "\${$variable}"

    companion object {
        private val VARIABLE = Regex("[A-Z_][A-Z0-9_]{0,127}")

        /** `${NAME}` → [SecretRef]; anything else (such as a password written out) → null. */
        fun parse(text: String): SecretRef? =
            Regex("\\$\\{([A-Z_][A-Z0-9_]{0,127})}").matchEntire(text.trim())?.let { SecretRef(it.groupValues[1]) }
    }
}

/**
 * How the explorer and testers get into the site, tried in the order of the profile's `sign_in` list (ADR-0010):
 * a test company created through the test API, accounts the owner gave, registering by itself (reading the code from
 * the mail source), or looking around without an account.
 */
enum class SignInMethod(
    val key: String,
) {
    TEST_COMPANY("test_company"),
    OWN_ACCOUNTS("own_accounts"),
    SELF_REGISTER("self_register"),
    ANONYMOUS("anonymous"),
    ;

    companion object {
        val DEFAULT_CHAIN: List<SignInMethod> = entries.toList()

        fun fromKey(key: String): SignInMethod? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/**
 * An account the owner gave for [role]: an e-mail and a password reference, or a saved browser session
 * ([storageState], a Playwright `storage_state` file) for sites whose login a script cannot pass (SSO, CAPTCHA).
 */
data class OwnAccount(
    val role: String,
    val email: String? = null,
    val password: SecretRef? = null,
    val storageState: String? = null,
    /** The name the site shows for the account; a tester signing in with it checks its identity against it. */
    val name: String? = null,
) {
    init {
        require(role.isNotBlank()) { "an account names its role" }
        require((email != null && password != null) || storageState != null) {
            "an account needs an e-mail with a password reference, or a storage_state file"
        }
    }
}

/** A target profile that cannot be used; lists every problem with its line. */
class TargetSpecException(
    val file: String,
    val issues: List<ValidationIssue>,
) : PetekException("Target profile $file is invalid:\n" + issues.joinToString("\n") { "  - $it" })

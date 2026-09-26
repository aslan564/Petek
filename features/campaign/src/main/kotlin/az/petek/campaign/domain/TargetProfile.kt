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

/**
 * What the harness needs to know about the target site for deterministic `run` steps: page paths, selectors, the flows
 * the run functions execute and how to get past the site's overlays. Defaults follow the `data-testid` contract in
 * docs/TARGET_CONTRACT.md; the campaign YAML's `target_profile:` section overrides them, so any site can be described
 * without code (docs/KADROHR_READINESS.md: the real KadroHR differs from the contract in almost every flow).
 */
data class TargetProfile(
    val paths: Map<String, String>,
    val selectors: Map<String, String>,
    /** Event name -> where the created object's id is read from. */
    val idSources: Map<String, IdSource>,
    /**
     * Flows by name ([FlowNames]). A campaign's flows replace the defaults of the same name; the other defaults stay,
     * so a campaign for a contract-like site only writes the flows that differ.
     */
    val flows: Map<String, Flow> = DEFAULT_FLOWS,
    /**
     * Seeded into the target origin's localStorage in every tester's browser before any page script runs, e.g. a
     * "don't ask again" flag of a first-visit dialog.
     */
    val localStorage: Map<String, String> = emptyMap(),
    /**
     * Selector references of overlays (consent or "use your own server?" dialogs) clicked away whenever they are
     * visible before a flow step.
     */
    val dismiss: List<String> = emptyList(),
    /** Path prefix of the target's regular API; `{api}` in campaign paths stands for it (expanded when loaded). */
    val apiPrefix: String = DEFAULT_API_PREFIX,
) {
    fun path(key: String): String = paths[key] ?: DEFAULT_PATHS[key] ?: error("Unknown target path key '$key'")

    fun selector(key: String): String = selectors[key] ?: DEFAULT_SELECTORS[key] ?: error("Unknown target selector key '$key'")

    fun idSource(event: String): IdSource? = idSources[event]

    /** The flow run by that name: the campaign's own, else the contract default; null when neither exists. */
    fun flow(name: String): Flow? = flows[name] ?: DEFAULT_FLOWS[name]

    /** Whether [ref] names a selector key of this profile (its own or a default) rather than a literal selector. */
    fun isSelectorKey(ref: String): Boolean = ref in selectors || ref in DEFAULT_SELECTORS

    /** The selector a flow's selector reference stands for: a key's selector, or [ref] itself (see [Flow]). */
    fun resolveSelector(ref: String): String = selectors[ref] ?: DEFAULT_SELECTORS[ref] ?: ref

    /** Whether [ref] names a path key of this profile rather than a literal path. */
    fun isPathKey(ref: String): Boolean = ref in paths || ref in DEFAULT_PATHS

    /** The path a flow's `goto` stands for: a key's path, or [ref] itself. */
    fun resolvePath(ref: String): String = paths[ref] ?: DEFAULT_PATHS[ref] ?: ref

    companion object {
        /** The regular API prefix of docs/TARGET_CONTRACT.md §5 (`/api/tickets/...`). */
        const val DEFAULT_API_PREFIX = "/api"

        val DEFAULT_PATHS: Map<String, String> =
            mapOf(
                "login" to "/login",
                "register" to "/register",
                "verify" to "/verify",
                "verify_phone" to "/verify/phone",
                "join" to "/join",
                "company" to "/company",
                "announcements" to "/announcements",
                "tickets" to "/tickets",
                "home" to "/",
            )

        private fun tid(id: String) = "[data-testid=\"$id\"]"

        val DEFAULT_SELECTORS: Map<String, String> =
            mapOf(
                "login.email" to tid("login-email"),
                "login.password" to tid("login-password"),
                "login.submit" to tid("login-submit"),
                "login.error" to tid("login-error"),
                "register.name" to tid("register-name"),
                "register.email" to tid("register-email"),
                "register.phone" to tid("register-phone"),
                "register.password" to tid("register-password"),
                "register.company" to tid("register-company"),
                "register.confirm_password" to tid("register-confirm-password"),
                "register.terms" to tid("register-terms"),
                "register.submit" to tid("register-submit"),
                "verify.code" to tid("verify-code"),
                "verify.submit" to tid("verify-submit"),
                "verify.phone_code" to tid("verify-phone-code"),
                "verify.phone_submit" to tid("verify-phone-submit"),
                "join.code" to tid("join-company-code"),
                "join.name" to tid("join-name"),
                "join.email" to tid("join-email"),
                "join.phone" to tid("join-phone"),
                "join.password" to tid("join-password"),
                "join.department" to tid("join-department"),
                "join.submit" to tid("join-submit"),
                "invite.name" to tid("invite-name"),
                "invite.phone" to tid("invite-phone"),
                "invite.password" to tid("invite-password"),
                "invite.submit" to tid("invite-submit"),
                "session.user_name" to tid("current-user-name"),
                "session.user_role" to tid("current-user-role"),
                "session.logout" to tid("logout"),
                "company.code" to tid("company-code"),
                "notification.item" to tid("notification-item"),
            )

        /** The contract flows (see [ContractFlows]); they address elements through the keys above. */
        val DEFAULT_FLOWS: Map<String, Flow> = ContractFlows.ALL

        val DEFAULT: TargetProfile = TargetProfile(emptyMap(), emptyMap(), emptyMap())
    }
}

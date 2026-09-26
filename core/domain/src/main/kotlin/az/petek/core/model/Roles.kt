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

package az.petek.core.model

/**
 * A tester role on the target system, named by the campaign (`campaign.roles`) or the target profile: any lowercase
 * key (`editor`, `buyer`). [ADMIN], [MANAGER] and [EMPLOYEE] are the roles of a site with companies (`tenant: company`,
 * the contract's profile); a site without companies uses its own. Shared by campaign quotas, identity registry and actor
 * selection.
 */
@JvmInline
value class Role private constructor(
    val key: String,
) : Comparable<Role> {
    /** Order in rosters and reports: the company roles first (admin, manager, employee), then the others by key. */
    val rank: Int get() = entries.indexOf(this).takeIf { it >= 0 } ?: entries.size

    /** Whether this is one of the roles of a site with companies ([entries]). */
    val isCompanyRole: Boolean get() = this in entries

    override fun compareTo(other: Role): Int = compareValuesBy(this, other, Role::rank, Role::key)

    override fun toString(): String = key

    companion object {
        val ADMIN: Role = Role("admin")
        val MANAGER: Role = Role("manager")
        val EMPLOYEE: Role = Role("employee")

        /** The roles of a site with companies, in rank order. */
        val entries: List<Role> = listOf(ADMIN, MANAGER, EMPLOYEE)

        private val KEY = Regex("[a-z][a-z0-9_-]{0,31}")

        /** The role called [key] (trimmed, lowercased), or null when it is not a valid role key. */
        fun fromKey(key: String): Role? =
            key
                .trim()
                .lowercase()
                .takeIf { KEY.matches(it) }
                ?.let(::Role)

        /** Whether [key] can name a role. */
        fun isValidKey(key: String): Boolean = fromKey(key) != null
    }
}

/**
 * How a tester gets onto the site (its gate). On a site with companies: the admin creates it ([OWNER]), others join
 * by invitation ([INVITE]) or by company code ([COMPANY_CODE]). On a site without companies: the tester signs up on
 * its own ([SELF]), signs in with an account the owner gave ([LOGIN]), or stays a visitor ([GUEST]).
 */
@JvmInline
value class RegistrationMode private constructor(
    val key: String,
) {
    /** Whether this mode belongs to a site with companies. */
    val isCompanyMode: Boolean get() = this in COMPANY_MODES

    override fun toString(): String = key

    companion object {
        val OWNER: RegistrationMode = RegistrationMode("owner")
        val INVITE: RegistrationMode = RegistrationMode("invite")
        val COMPANY_CODE: RegistrationMode = RegistrationMode("company_code")
        val SELF: RegistrationMode = RegistrationMode("self")
        val LOGIN: RegistrationMode = RegistrationMode("login")
        val GUEST: RegistrationMode = RegistrationMode("guest")

        val COMPANY_MODES: List<RegistrationMode> = listOf(OWNER, INVITE, COMPANY_CODE)

        /** The gates of a site without companies, in the order testers are dealt to them. */
        val GATES: List<RegistrationMode> = listOf(SELF, LOGIN, GUEST)

        val entries: List<RegistrationMode> = COMPANY_MODES + GATES

        fun fromKey(key: String): RegistrationMode? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

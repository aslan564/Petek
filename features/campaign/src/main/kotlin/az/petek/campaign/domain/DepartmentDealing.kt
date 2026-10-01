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

import az.petek.core.model.Role

/**
 * How many testers of a role the identity registry puts into a department, from the quotas alone (before any identity
 * exists). The registry (`DefaultIdentityRegistryGenerator`, docs/PLAN.md "Kimlik reyestri") deals the departments in
 * turn:
 * - with companies the admin has none, and the managers, then the employees, take `departments[j % d]` in one
 *   rotation (`j` from 0 over the non-admins), so three managers over five departments sit in the first three and the
 *   other two have none;
 * - without companies every tester, role after role in the order the campaign lists them, takes `departments[i % d]`
 *   when departments are given.
 *
 * The validator bounds a department selector by it, so `manager[<a department no manager is dealt to>]` is reported
 * before any run instead of matching nobody in it. Department names compare like the actor resolver does: trimmed,
 * ignoring case.
 */
object DepartmentDealing {
    /** Testers of [role] that [settings] put into [department]; 0 for a department the campaign does not list. */
    fun testers(
        settings: CampaignSettings,
        role: Role,
        department: String,
    ): Int {
        val departments = settings.departments
        val wanted = department.trim()
        val slots = departments.indices.filter { departments[it].trim().equals(wanted, ignoreCase = true) }
        val seats = seatsOf(settings, role) ?: return 0
        return slots.sumOf { slot -> inSlot(seats.last + 1, slot, departments.size) - inSlot(seats.first, slot, departments.size) }
    }

    /** The places of [role] in the dealing order; null when the role is never dealt a department. */
    private fun seatsOf(
        settings: CampaignSettings,
        role: Role,
    ): IntRange? {
        val roles = settings.roles
        if (settings.tenant == Tenant.COMPANY) {
            val managers = roles.manager.coerceAtLeast(0)
            return when (role) {
                Role.MANAGER -> 0 until managers
                Role.EMPLOYEE -> managers until managers + roles.employee.coerceAtLeast(0)
                else -> null
            }
        }
        var first = 0
        roles.counts.forEach { (each, count) ->
            val seats = count.coerceAtLeast(0)
            if (each == role) return first until first + seats
            first += seats
        }
        return null
    }

    /** Places `j` in `[0, until)` with `j % size == slot`. */
    private fun inSlot(
        until: Int,
        slot: Int,
        size: Int,
    ): Int = if (until <= slot) 0 else (until - 1 - slot) / size + 1
}

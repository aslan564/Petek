/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.identity.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunTag
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import kotlin.random.Random

/**
 * The registry generator of docs/PLAN.md "Kimlik reyestri". For a valid spec (see [IdentitySpecValidator]):
 * - `a01` is the admin ([RegistrationMode.OWNER], no department), then the managers, then the employees.
 * - Non-admins are dealt to departments in one round-robin: manager `i` gets `departments[i % d]` (one manager per
 *   department when the counts match) and employees continue the rotation, so every department ends up within
 *   ±1 of the others, for employees and for head count.
 * - Names: the spec's names first, then the catalog ([NameAllocator]), which never runs out.
 * - E-mail `<ascii first name>.<run tag>.<agent id>@<mail domain>`, unique because the agent id is.
 * - Phones: distinct fake numbers outside the allocated subscriber ranges ([FakePhoneNumbers]).
 *
 * There is no fixed maximum of testers (agent ids grow past `a999`); every part is generated in time linear in
 * the number of testers, so thousands of identities take well under a second.
 * - Registration modes: every manager joins by invitation, because the target's company-code form (`/join`) has no
 *   role field and makes everyone who uses it an employee. The remaining `inviteCount - managers` invitations go to
 *   employees: the employees of each department are shuffled, departments are interleaved and the first ones are
 *   invited, the rest join by company code, so the employees of every department get a mix when both their
 *   invitations and the company codes reach the number of departments. Company-code identities are always employees.
 *
 * Names, roles, departments and registration modes depend only on the spec (its seed), so every run of a campaign
 * tests the same people. E-mails, passwords and phones also depend on the run tag, so concurrent or leftover runs
 * never collide on the target.
 */
class DefaultIdentityRegistryGenerator(
    nameCatalog: NameCatalog,
    private val passwordDeriver: PasswordDeriver,
) : IdentityRegistryGenerator {
    private val nameAllocator = NameAllocator(nameCatalog)
    private val validator = IdentitySpecValidator(nameAllocator)

    override fun generate(
        spec: IdentitySpec,
        runTag: RunTag,
    ): IdentityPlan {
        val valid = validator.validate(spec)
        val seats = seats(spec, valid.departments)
        val names = nameAllocator.allocate(valid.names, spec.testers, spec.seed)
        val registrations = registrationModes(seats, valid.departments, spec)
        val phones = phoneNumbers(spec.testers, spec.seed, runTag)
        val identities =
            seats.mapIndexed { i, seat ->
                Identity(
                    agentId = seat.agentId,
                    displayName = names[i].displayName,
                    email = email(names[i].firstName, runTag, seat.agentId, valid.mailDomain),
                    password = passwordDeriver.derive(runTag, seat.agentId),
                    phone = phones[i],
                    role = seat.role,
                    department = seat.department,
                    registration = registrations.getValue(seat.agentId),
                )
            }
        return IdentityPlan(runTag, identities)
    }

    private fun seats(
        spec: IdentitySpec,
        departments: List<String>,
    ): List<Seat> {
        val admin = Seat(AgentId.of(1), Role.ADMIN, department = null)
        val others =
            (0 until spec.managers + spec.employees).map { j ->
                val role = if (j < spec.managers) Role.MANAGER else Role.EMPLOYEE
                Seat(AgentId.of(j + 2), role, departments[j % departments.size])
            }
        return listOf(admin) + others
    }

    private fun registrationModes(
        seats: List<Seat>,
        departments: List<String>,
        spec: IdentitySpec,
    ): Map<AgentId, RegistrationMode> {
        val fixed =
            seats.mapNotNull { seat ->
                when (seat.role) {
                    Role.ADMIN -> seat.agentId to RegistrationMode.OWNER
                    Role.MANAGER -> seat.agentId to RegistrationMode.INVITE
                    Role.EMPLOYEE -> null
                }
            }
        val employeeInvites = spec.inviteCount - spec.managers
        val employees =
            stratifiedOrder(seats.filter { it.role == Role.EMPLOYEE }, departments, spec.seed).withIndex().map { (i, seat) ->
                seat.agentId to if (i < employeeInvites) RegistrationMode.INVITE else RegistrationMode.COMPANY_CODE
            }
        return (fixed + employees).toMap()
    }

    /**
     * [members] in a seeded order that takes one member of each department per round (departments and the members
     * within each one shuffled), so any prefix of it is spread over the departments within one of each other.
     */
    private fun stratifiedOrder(
        members: List<Seat>,
        departments: List<String>,
        seed: Long,
    ): List<Seat> {
        val random = Random(seed xor REGISTRATION_SALT)
        val byDepartment = members.groupBy { it.department }
        val groups = departments.shuffled(random).map { byDepartment[it].orEmpty().shuffled(random) }
        val rounds = groups.maxOfOrNull { it.size } ?: 0
        return (0 until rounds).flatMap { round -> groups.mapNotNull { it.getOrNull(round) } }
    }

    private fun phoneNumbers(
        count: Int,
        seed: Long,
        runTag: RunTag,
    ): List<String> = FakePhoneNumbers.sample(count, Random(seed * PHONE_SEED_MULTIPLIER + runTag.value.hashCode()))

    private fun email(
        firstName: String,
        runTag: RunTag,
        agentId: AgentId,
        mailDomain: String,
    ): String = "${AsciiSlug.of(firstName)}.${runTag.value}.${agentId.value}@$mailDomain"

    private data class Seat(
        val agentId: AgentId,
        val role: Role,
        val department: String?,
    )

    private companion object {
        /** Keeps the registration shuffle independent of the name choice, which also draws from the seed. */
        const val REGISTRATION_SALT = 0x5245_4749_5354_4552L
        const val PHONE_SEED_MULTIPLIER = 31L
    }
}

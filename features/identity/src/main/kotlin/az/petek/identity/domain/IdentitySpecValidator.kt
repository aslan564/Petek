/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.identity.domain

/**
 * Checks an [IdentitySpec] before anything is generated and reports every problem at once, so the user fixes the
 * campaign in one go. A spec that fails here must not start a run ([IdentityConflictException]).
 */
internal class IdentitySpecValidator(
    private val names: NameAllocator,
) {
    /** Normalized inputs the generator works with. */
    data class Valid(
        val names: List<String>,
        val departments: List<String>,
        val mailDomain: String,
        val mailbox: String? = null,
    )

    fun validate(spec: IdentitySpec): Valid {
        val problems = mutableListOf<String>()
        val givenNames = spec.names.map(NameAllocator::normalize)
        val departments = spec.departments.map(NameAllocator::normalize)
        val mailDomain = spec.mailDomain.trim().lowercase()
        checkCounts(spec, problems)
        checkDepartments(departments, problems)
        checkNames(givenNames, spec.testers, problems)
        checkCatalog(givenNames, spec.testers, problems)
        if (mailDomain.length > MAX_DOMAIN_LENGTH || !DOMAIN.matches(mailDomain)) {
            problems += "mail domain '${spec.mailDomain}' is not a valid domain name"
        }
        val mailbox = spec.mailbox?.trim()?.lowercase()
        if (mailbox != null && !MAILBOX.matches(mailbox)) problems += "mailbox '${spec.mailbox}' is not a plain e-mail address"
        if (problems.isNotEmpty()) {
            throw IdentityConflictException("${NameAllocator.CANNOT_BUILD} " + problems.joinToString("; "))
        }
        return Valid(givenNames, departments, mailDomain, mailbox)
    }

    private fun checkCounts(
        spec: IdentitySpec,
        problems: MutableList<String>,
    ) {
        val counts =
            listOf(
                "admins" to spec.admins,
                "managers" to spec.managers,
                "employees" to spec.employees,
                "invite count" to spec.inviteCount,
                "company code count" to spec.companyCodeCount,
            )
        counts.filter { it.second < 0 }.forEach { (label, value) -> problems += "$label must not be negative, was $value" }
        if (spec.testers < 1) problems += "testers must be at least 1, was ${spec.testers}"
        if (spec.testers > FakePhoneNumbers.CAPACITY) {
            problems += "testers must not exceed ${FakePhoneNumbers.CAPACITY}, the number of distinct fake phone numbers " +
                "(one per tester), was ${spec.testers}"
        }
        if (spec.admins != 1) problems += "exactly 1 admin is supported, was ${spec.admins}"
        val roles = spec.admins + spec.managers + spec.employees
        if (roles != spec.testers) {
            problems += "roles add up to $roles (${spec.admins} admin + ${spec.managers} managers + " +
                "${spec.employees} employees) but testers is ${spec.testers}"
        }
        val joiners = spec.managers + spec.employees
        val registrations = spec.inviteCount + spec.companyCodeCount
        if (registrations != joiners) {
            problems += "registration quota adds up to $registrations (${spec.inviteCount} invite + " +
                "${spec.companyCodeCount} company code) but there are $joiners managers and employees"
        }
        if (spec.managers >= 0 && spec.inviteCount in 0 until spec.managers) {
            problems += "invite count ${spec.inviteCount} is less than the ${spec.managers} managers; managers always join " +
                "by invitation, because a company-code sign-up makes an employee"
        }
    }

    private fun checkDepartments(
        departments: List<String>,
        problems: MutableList<String>,
    ) {
        if (departments.isEmpty()) problems += "at least one department is required"
        if (departments.any { it.isEmpty() }) problems += "department names must not be blank"
        departments.filter { it.length > MAX_TEXT_LENGTH }.forEach {
            problems += "department '$it' is longer than $MAX_TEXT_LENGTH characters"
        }
        duplicates(departments).forEach { problems += "department '$it' is listed more than once" }
    }

    private fun checkNames(
        givenNames: List<String>,
        testers: Int,
        problems: MutableList<String>,
    ) {
        if (givenNames.any { it.isEmpty() }) problems += "tester names must not be blank"
        givenNames.filter { it.length > MAX_TEXT_LENGTH }.forEach {
            problems += "name '$it' is longer than $MAX_TEXT_LENGTH characters"
        }
        duplicates(givenNames).forEach { problems += "name '$it' is given more than once" }
        if (givenNames.size > testers) {
            problems += "${givenNames.size} names are given but there are only $testers testers"
        }
    }

    private fun checkCatalog(
        givenNames: List<String>,
        testers: Int,
        problems: MutableList<String>,
    ) {
        val missing = testers - givenNames.size
        if (missing > 0 && !names.canInventNames) {
            problems += "the name catalog has no first names or no surnames, but $missing more names are needed"
        }
        val needsSurname = givenNames.any { it.isNotEmpty() && !NameAllocator.isFullName(it) }
        if (needsSurname && !names.hasSurnames) {
            problems += "the name catalog has no surnames for the given first names"
        }
    }

    /** Non-blank values that occur more than once, compared case-insensitively; reported in their first spelling. */
    private fun duplicates(values: List<String>): List<String> =
        values
            .filter { it.isNotEmpty() }
            .groupBy(NameAllocator::key)
            .values
            .filter { it.size > 1 }
            .map { it.first() }

    companion object {
        /** A box whose local part may take a `+` tag: no `+` of its own, one `@`, a dotted domain. */
        private val MAILBOX = Regex("[a-z0-9._-]{1,48}@[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")

        /** Keeps names and departments readable on screen and within the target's form limits. */
        const val MAX_TEXT_LENGTH = 100

        /** Leaves room for the local part within the 254-character limit of an e-mail address. */
        private const val MAX_DOMAIN_LENGTH = 200

        private val DOMAIN = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*")
    }
}

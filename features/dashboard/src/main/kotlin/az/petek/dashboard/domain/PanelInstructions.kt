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

package az.petek.dashboard.domain

import java.net.URI
import java.net.URISyntaxException

/**
 * What the owner asks for on the "Təlimat" screen: which site, what to test in plain words, and the team and budget a
 * campaign gets. The panel hands it to the backend as is; [problems] are the checks the page shows next to the fields
 * before anything starts, the same rules the campaign validator applies later (docs/ARCHITECTURE.md decisions).
 *
 * The team is the owner's only when given (Faza 25): without [roles] and [registration] the draft takes the roles and
 * the ways in the explorer saw on the site, without [departments] the departments it saw.
 */
data class PanelInstructions(
    /** The site under test, an absolute http(s) URL. The target policy of the backend still decides if it may be used. */
    val target: String,
    /** Free text: what to test, what matters, what to avoid. May be empty. */
    val instructions: String,
    val testers: Int,
    /** The owner's split of a company's team; null: the roles the explorer saw. */
    val roles: RoleSplit? = null,
    /** The owner's departments; empty: the ones the explorer saw. */
    val departments: List<String> = emptyList(),
    /** How the owner's team joins a company; null: the ways in the explorer saw. Only with [roles]. */
    val registration: RegistrationSplit? = null,
    val budget: PanelBudget,
    /** Let the explorer submit each create form once (TRIAL_TOUCH); only honoured on an `is_test` target. */
    val allowWrites: Boolean = false,
) {
    /** Everything wrong with the request, in page order; empty when it may be sent. Messages are in Azerbaijani. */
    fun problems(): List<FieldProblem> =
        buildList {
            targetProblem()?.let(::add)
            if (instructions.length > MAX_INSTRUCTION_CHARS) {
                add(FieldProblem(INSTRUCTIONS, "Təlimat çox uzundur (ən çox 20 000 simvol)."))
            }
            if (testers !in 1..MAX_TESTERS) {
                add(FieldProblem(TESTERS, "Tester sayı 1 ilə $MAX_TESTERS arasında olmalıdır."))
            }
            addAll(roleProblems())
            addAll(departmentProblems())
            addAll(registrationProblems())
            addAll(budget.problems())
        }

    private fun targetProblem(): FieldProblem? {
        val uri =
            try {
                URI(target.trim())
            } catch (_: URISyntaxException) {
                null
            }
        val valid = uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
        return if (valid) null else FieldProblem(TARGET, "Hədəf http:// və ya https:// ilə başlayan tam ünvan olmalıdır.")
    }

    private fun roleProblems(): List<FieldProblem> =
        buildList {
            val team = roles ?: return@buildList
            val negative = minOf(team.admins, team.managers, team.employees) < 0
            if (negative) {
                add(FieldProblem(ROLES, "Rol sayları mənfi ola bilməz."))
            }
            if (team.admins < 1) {
                add(FieldProblem(ROLES, "Ən azı bir admin lazımdır: test şirkətini o yaradır."))
            }
            if (team.total != testers) {
                add(FieldProblem(ROLES, "Rolların cəmi (${team.total}) tester sayına ($testers) bərabər olmalıdır."))
            }
        }

    /** Departments are optional: without them the draft takes the ones the explorer saw, else its own one (Faza 25.2). */
    private fun departmentProblems(): List<FieldProblem> =
        buildList {
            val names = departments.map { it.trim() }
            if (names.any { it.isEmpty() || it.length > MAX_DEPARTMENT_CHARS }) {
                add(FieldProblem(DEPARTMENTS, "Şöbə adı boş ola bilməz və $MAX_DEPARTMENT_CHARS simvoldan uzun olmamalıdır."))
            }
            if (names.map { it.lowercase() }.toSet().size != names.size) {
                add(FieldProblem(DEPARTMENTS, "Şöbə adları təkrarlanmamalıdır."))
            }
        }

    private fun registrationProblems(): List<FieldProblem> =
        buildList {
            val split = registration ?: return@buildList
            val team = roles
            if (team == null) {
                add(FieldProblem(REGISTRATION, "Qeydiyyat bölgüsü yalnız rollarla birlikdə verilir."))
                return@buildList
            }
            val joining = team.managers + team.employees
            val negative = minOf(split.invite, split.companyCode) < 0
            if (negative) {
                add(FieldProblem(REGISTRATION, "Qeydiyyat sayları mənfi ola bilməz."))
            }
            if (split.total != joining) {
                add(FieldProblem(REGISTRATION, "Dəvətlə və şirkət kodu ilə qoşulanların cəmi $joining olmalıdır (admin olmayanlar)."))
            }
            if (split.invite < team.managers) {
                add(FieldProblem(REGISTRATION, "Menecerlər yalnız dəvətlə qoşulur: dəvət sayı ən azı ${team.managers} olmalıdır."))
            }
        }

    companion object {
        const val TARGET = "target"
        const val INSTRUCTIONS = "instructions"
        const val TESTERS = "testers"
        const val ROLES = "roles"
        const val DEPARTMENTS = "departments"
        const val REGISTRATION = "registration"

        /** Agent ids run from a01 to a999. */
        const val MAX_TESTERS = 999
        const val MAX_INSTRUCTION_CHARS = 20_000
        const val MAX_DEPARTMENT_CHARS = 60
    }
}

/** How many testers play each role; the admin creates the test company. */
data class RoleSplit(
    val admins: Int,
    val managers: Int,
    val employees: Int,
) {
    val total: Int get() = admins + managers + employees
}

/** How the non-admins join: by invitation (every manager, some employees) or with the company code. */
data class RegistrationSplit(
    val invite: Int,
    val companyCode: Int,
) {
    val total: Int get() = invite + companyCode
}

/** Limits of a campaign: run time, actions an agent may take per run, and pages the explorer may visit. */
data class PanelBudget(
    val maxMinutes: Int,
    val maxStepsPerAgent: Int,
    val maxPages: Int,
) {
    fun problems(): List<FieldProblem> =
        buildList {
            if (maxMinutes !in 1..MAX_MINUTES) {
                add(FieldProblem(MINUTES, "Vaxt büdcəsi 1–$MAX_MINUTES dəqiqə olmalıdır."))
            }
            if (maxStepsPerAgent !in 1..MAX_STEPS) {
                add(FieldProblem(STEPS, "Agent başına addım 1–$MAX_STEPS olmalıdır."))
            }
            if (maxPages !in 1..MAX_PAGES) {
                add(FieldProblem(PAGES, "Kəşfiyyat üçün səhifə sayı 1–$MAX_PAGES olmalıdır."))
            }
        }

    companion object {
        const val MINUTES = "budget.maxMinutes"
        const val STEPS = "budget.maxStepsPerAgent"
        const val PAGES = "budget.maxPages"
        const val MAX_MINUTES = 480
        const val MAX_STEPS = 500
        const val MAX_PAGES = 1_000
    }
}

/** One problem of a request, tied to the form field ([field]) the page shows it under. */
data class FieldProblem(
    val field: String,
    val message: String,
)

package az.petek.dashboard.domain

import java.net.URI
import java.net.URISyntaxException

/**
 * What the owner asks for on the "Təlimat" screen: which site, what to test in plain words, and the team and budget a
 * campaign gets. The panel hands it to the backend as is; [problems] are the checks the page shows next to the fields
 * before anything starts, the same rules the campaign validator applies later (docs/ARCHITECTURE.md decisions).
 */
data class PanelInstructions(
    /** The site under test, an absolute http(s) URL. The target policy of the backend still decides if it may be used. */
    val target: String,
    /** Free text: what to test, what matters, what to avoid. May be empty. */
    val instructions: String,
    val testers: Int,
    val roles: RoleSplit,
    val departments: List<String>,
    val registration: RegistrationSplit,
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
            val negative = minOf(roles.admins, roles.managers, roles.employees) < 0
            if (negative) {
                add(FieldProblem(ROLES, "Rol sayları mənfi ola bilməz."))
            }
            if (roles.admins < 1) {
                add(FieldProblem(ROLES, "Ən azı bir admin lazımdır: test şirkətini o yaradır."))
            }
            if (roles.total != testers) {
                add(FieldProblem(ROLES, "Rolların cəmi (${roles.total}) tester sayına ($testers) bərabər olmalıdır."))
            }
        }

    private fun departmentProblems(): List<FieldProblem> =
        buildList {
            val names = departments.map { it.trim() }
            if (roles.managers + roles.employees > 0 && names.none { it.isNotEmpty() }) {
                add(FieldProblem(DEPARTMENTS, "Menecer və işçilər üçün ən azı bir şöbə yazın."))
            }
            if (names.any { it.isEmpty() || it.length > MAX_DEPARTMENT_CHARS }) {
                add(FieldProblem(DEPARTMENTS, "Şöbə adı boş ola bilməz və $MAX_DEPARTMENT_CHARS simvoldan uzun olmamalıdır."))
            }
            if (names.map { it.lowercase() }.toSet().size != names.size) {
                add(FieldProblem(DEPARTMENTS, "Şöbə adları təkrarlanmamalıdır."))
            }
        }

    private fun registrationProblems(): List<FieldProblem> =
        buildList {
            val joining = roles.managers + roles.employees
            val negative = minOf(registration.invite, registration.companyCode) < 0
            if (negative) {
                add(FieldProblem(REGISTRATION, "Qeydiyyat sayları mənfi ola bilməz."))
            }
            if (registration.total != joining) {
                add(FieldProblem(REGISTRATION, "Dəvətlə və şirkət kodu ilə qoşulanların cəmi $joining olmalıdır (admin olmayanlar)."))
            }
            if (registration.invite < roles.managers) {
                add(FieldProblem(REGISTRATION, "Menecerlər yalnız dəvətlə qoşulur: dəvət sayı ən azı ${roles.managers} olmalıdır."))
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

package az.petek.faketarget.service

import az.petek.faketarget.mail.MailAddress
import az.petek.faketarget.model.Company
import az.petek.faketarget.model.Department
import az.petek.faketarget.model.Invitation
import az.petek.faketarget.model.User
import az.petek.faketarget.model.UserRole
import az.petek.faketarget.store.FakeTargetStore
import az.petek.faketarget.store.StoreState
import java.time.Clock
import java.time.Instant

internal data class InviteRequest(
    val email: String,
    val name: String,
    val role: String,
    val department: String?,
)

internal data class SeedRequest(
    val companyId: String,
    val departments: List<String>,
    val invites: List<InviteRequest>,
)

internal data class SeedResult(
    val company: Company,
    /** Department name -> id, for every department of the company. */
    val departments: Map<String, String>,
    /** Invitation link per requested invite, in request order. */
    val inviteLinks: List<Pair<String, String>>,
)

internal data class CompanyOverview(
    val company: Company,
    val departments: List<Department>,
    val members: List<User>,
    val invitations: List<Invitation>,
)

/** Companies: seeding and teardown for the test API (test companies only) and the admin's company page. */
internal class CompanyService(
    private val store: FakeTargetStore,
    private val mailer: Mailer,
    private val secrets: SecretGenerator,
    private val hub: NotificationHub,
    private val clock: Clock,
) {
    /**
     * Adds departments and invitations. Idempotent per department name and per invite e-mail: repeating a seed
     * returns the same ids and links and sends no second e-mail. A department named only by an invite is created too.
     */
    fun seed(
        request: SeedRequest,
        baseUrl: String,
    ): Outcome<SeedResult> {
        val invites = request.invites.map { it.parse() ?: return Failure.EMAIL_INVALID.failed() }
        if (invites.any { it.role == null }) return Failure.INVALID_ROLE.failed()
        val departmentNames = request.departments.map(String::trim)
        if (departmentNames.any(String::isEmpty)) return Failure.INVALID_REQUEST.failed()
        val now = clock.instant()
        val outcome =
            store.transaction {
                val company = companies[request.companyId] ?: return@transaction Failure.COMPANY_NOT_FOUND.failed()
                if (!company.isTest) return@transaction Failure.NOT_A_TEST_COMPANY.failed()
                (departmentNames + invites.mapNotNull { it.department }).distinct().forEach { ensureDepartment(company.id, it) }
                Seeded(
                    company = company,
                    departments = departmentsOf(company.id).associate { it.name to it.id },
                    invitations = invites.map { invite -> ensureInvitation(company.id, invite, now) },
                ).ok()
            }
        return when (outcome) {
            is Outcome.Failed -> {
                outcome
            }

            is Outcome.Ok -> {
                val seeded = outcome.value
                seeded.invitations.filter { it.second }.forEach { (invitation, _) -> sendInvitation(invitation, seeded.company, baseUrl) }
                val links = seeded.invitations.map { (invitation, _) -> invitation.email to link(baseUrl, invitation) }
                SeedResult(seeded.company, seeded.departments, links).ok()
            }
        }
    }

    /** Deletes a test company with everything in it and ends its members' live streams. */
    fun delete(companyId: String): Outcome<Unit> {
        val outcome =
            store.transaction {
                val company = companies[companyId] ?: return@transaction Failure.COMPANY_NOT_FOUND.failed()
                if (!company.isTest) return@transaction Failure.NOT_A_TEST_COMPANY.failed()
                deleteCompany(companyId).ok()
            }
        return when (outcome) {
            is Outcome.Failed -> {
                outcome
            }

            is Outcome.Ok -> {
                hub.disconnect(outcome.value)
                Unit.ok()
            }
        }
    }

    fun company(user: User): Company = checkNotNull(store.company(user.companyId)) { "user ${user.id} has no company" }

    fun overview(admin: User): Outcome<CompanyOverview> {
        if (admin.role != UserRole.ADMIN) return Failure.FORBIDDEN.failed()
        return store.transaction {
            val company = companies[admin.companyId] ?: return@transaction Failure.COMPANY_NOT_FOUND.failed()
            CompanyOverview(
                company = company,
                departments = departmentsOf(company.id),
                members = membersOf(company.id),
                invitations = invitations.values.filter { it.companyId == company.id },
            ).ok()
        }
    }

    fun departments(user: User): List<Department> = store.departments(user.companyId)

    fun addDepartment(
        admin: User,
        name: String,
    ): Outcome<Department> {
        if (admin.role != UserRole.ADMIN) return Failure.FORBIDDEN.failed()
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > Inputs.MAX_NAME_LENGTH) return Failure.INVALID_REQUEST.failed()
        return store.transaction { ensureDepartment(admin.companyId, clean).ok() }
    }

    /** The admin invites one person from the company page; the e-mail is (re)sent every time. */
    fun invite(
        admin: User,
        request: InviteRequest,
        baseUrl: String,
    ): Outcome<Invitation> {
        if (admin.role != UserRole.ADMIN) return Failure.FORBIDDEN.failed()
        val invite = request.parse() ?: return Failure.EMAIL_INVALID.failed()
        if (invite.role == null) return Failure.INVALID_ROLE.failed()
        val now = clock.instant()
        val outcome =
            store.transaction {
                if (users.containsKey(invite.email)) return@transaction Failure.EMAIL_TAKEN.failed()
                val company = companies[admin.companyId] ?: return@transaction Failure.COMPANY_NOT_FOUND.failed()
                if (invite.department != null && department(company.id, invite.department) == null) {
                    return@transaction Failure.UNKNOWN_DEPARTMENT.failed()
                }
                (company to ensureInvitation(company.id, invite, now).first).ok()
            }
        return when (outcome) {
            is Outcome.Failed -> {
                outcome
            }

            is Outcome.Ok -> {
                val (company, invitation) = outcome.value
                sendInvitation(invitation, company, baseUrl)
                invitation.ok()
            }
        }
    }

    /** What one seed transaction produced; each invitation carries whether it is new (and needs an e-mail). */
    private data class Seeded(
        val company: Company,
        val departments: Map<String, String>,
        val invitations: List<Pair<Invitation, Boolean>>,
    )

    private data class ParsedInvite(
        val email: String,
        val name: String,
        val role: UserRole?,
        val department: String?,
    )

    private fun InviteRequest.parse(): ParsedInvite? {
        val normalized = Inputs.email(email)
        if (!Inputs.isValidEmail(normalized)) return null
        return ParsedInvite(normalized, name.trim(), UserRole.fromKey(role), department?.trim()?.takeIf { it.isNotEmpty() })
    }

    private fun StoreState.ensureDepartment(
        companyId: String,
        name: String,
    ): Department =
        departmentsOf(companyId).firstOrNull { it.name == name }
            ?: Department(nextId("d"), companyId, name).also { departments[it.id] = it }

    /** Returns the invitation for the e-mail in this company and whether it was just created. */
    private fun StoreState.ensureInvitation(
        companyId: String,
        invite: ParsedInvite,
        now: Instant,
    ): Pair<Invitation, Boolean> {
        invitations.values.firstOrNull { it.companyId == companyId && it.email == invite.email }?.let { return it to false }
        val invitation =
            Invitation(
                token = secrets.letterToken(),
                companyId = companyId,
                email = invite.email,
                name = invite.name,
                role = checkNotNull(invite.role),
                department = invite.department?.let { department(companyId, it) },
                createdAt = now,
                acceptedAt = null,
            )
        invitations[invitation.token] = invitation
        return invitation to true
    }

    private fun sendInvitation(
        invitation: Invitation,
        company: Company,
        baseUrl: String,
    ) {
        mailer.sendInvitation(MailAddress(invitation.name, invitation.email), company.name, link(baseUrl, invitation))
    }

    private fun link(
        baseUrl: String,
        invitation: Invitation,
    ): String = "${baseUrl.trimEnd('/')}/invite/${invitation.token}"
}

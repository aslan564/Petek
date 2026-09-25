package az.petek.faketarget.store

import az.petek.faketarget.model.Announcement
import az.petek.faketarget.model.Company
import az.petek.faketarget.model.Department
import az.petek.faketarget.model.Invitation
import az.petek.faketarget.model.Notification
import az.petek.faketarget.model.Receipt
import az.petek.faketarget.model.Ticket
import az.petek.faketarget.model.User
import java.time.Instant

/** A code sent by e-mail or SMS. Wrong guesses are counted so a code cannot be brute-forced. */
internal data class OneTimeCode(
    val code: String,
    val expiresAt: Instant,
    val failedAttempts: Int = 0,
)

/** Latest phone OTP per phone number, as `/test/otp/{phone}` reports it. */
internal data class PhoneOtp(
    val phone: String,
    val email: String,
    val code: String,
)

/**
 * The mutable maps behind [FakeTargetStore]. Only touched inside [FakeTargetStore.transaction], so plain collections
 * are enough; insertion order doubles as creation order ("latest" = last inserted).
 */
internal class StoreState {
    private val sequences = HashMap<String, Long>()

    val companies = LinkedHashMap<String, Company>()
    val departments = LinkedHashMap<String, Department>()

    /** By lower-case e-mail. */
    val users = LinkedHashMap<String, User>()
    val credentials = HashMap<String, PasswordHash>()
    val emailCodes = HashMap<String, OneTimeCode>()
    val phoneCodes = HashMap<String, OneTimeCode>()

    /** By the digits of the phone number. */
    val latestPhoneOtps = HashMap<String, PhoneOtp>()

    /** By token. */
    val invitations = LinkedHashMap<String, Invitation>()

    /** Session token -> e-mail. */
    val sessions = HashMap<String, String>()
    val announcements = LinkedHashMap<String, Announcement>()

    /** By [receiptKey]. */
    val receipts = LinkedHashMap<String, Receipt>()
    val notifications = LinkedHashMap<String, Notification>()
    val tickets = LinkedHashMap<String, Ticket>()

    fun nextSequence(prefix: String): Long = (sequences[prefix] ?: 0L).plus(1).also { sequences[prefix] = it }

    fun nextId(prefix: String): String = "$prefix${nextSequence(prefix)}"

    fun companyByCode(code: String): Company? = companies.values.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }

    /** The first of [candidates] no company uses yet. */
    fun unusedCompanyCode(candidates: Sequence<String>): String = candidates.first { companyByCode(it) == null }

    fun departmentsOf(companyId: String): List<Department> = departments.values.filter { it.companyId == companyId }

    /** Resolves a department of [companyId] by name (exact, trimmed) or by id. */
    fun department(
        companyId: String,
        nameOrId: String,
    ): Department? {
        val wanted = nameOrId.trim()
        return departmentsOf(companyId).firstOrNull { it.name == wanted } ?: departments[wanted]?.takeIf { it.companyId == companyId }
    }

    fun membersOf(companyId: String): List<User> = users.values.filter { it.companyId == companyId }

    fun receiptKey(
        announcementId: String,
        email: String,
    ): String = "$announcementId|$email"

    /** Removes the company and everything that belongs to it; returns the e-mails of its former members. */
    fun deleteCompany(companyId: String): List<String> {
        val members = membersOf(companyId).map { it.email }
        val memberSet = members.toSet()
        companies.remove(companyId)
        departments.values.removeIf { it.companyId == companyId }
        members.forEach { email ->
            users.remove(email)
            credentials.remove(email)
            emailCodes.remove(email)
            phoneCodes.remove(email)
        }
        latestPhoneOtps.values.removeIf { it.email in memberSet }
        invitations.values.removeIf { it.companyId == companyId }
        sessions.values.removeIf { it in memberSet }
        val announcementIds =
            announcements.values
                .filter { it.companyId == companyId }
                .map { it.id }
                .toSet()
        announcements.keys.removeAll(announcementIds)
        receipts.values.removeIf { it.announcementId in announcementIds }
        notifications.values.removeIf { it.companyId == companyId }
        tickets.values.removeIf { it.companyId == companyId }
        return members
    }
}

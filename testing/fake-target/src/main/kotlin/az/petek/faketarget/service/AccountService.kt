/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.service

import az.petek.faketarget.FakeTargetConfig
import az.petek.faketarget.mail.MailAddress
import az.petek.faketarget.model.Company
import az.petek.faketarget.model.Department
import az.petek.faketarget.model.Invitation
import az.petek.faketarget.model.User
import az.petek.faketarget.model.UserRole
import az.petek.faketarget.store.FakeTargetStore
import az.petek.faketarget.store.OneTimeCode
import az.petek.faketarget.store.PasswordHash
import az.petek.faketarget.store.PhoneOtp
import az.petek.faketarget.store.StoreState
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

internal data class OwnerSignUp(
    val name: String,
    val email: String,
    val phone: String,
    val password: String,
    val companyName: String,
)

internal data class CodeSignUp(
    val companyCode: String,
    val name: String,
    val email: String,
    val phone: String,
    val password: String,
    val department: String,
)

internal data class InviteSignUp(
    val name: String,
    val phone: String,
    val password: String,
)

internal data class InvitationView(
    val invitation: Invitation,
    val company: Company,
)

/** Where a person goes after a sign-up, verification or login step. */
internal sealed interface NextStep {
    data class VerifyEmail(
        val email: String,
    ) : NextStep

    data class VerifyPhone(
        val email: String,
    ) : NextStep

    data class LoggedIn(
        val sessionToken: String,
        val user: User,
    ) : NextStep
}

/**
 * Sign-up (owner, company code, invitation), e-mail and phone verification, login and sessions.
 * Every sign-up ends at `/verify`; a session exists only after all required verifications.
 */
internal class AccountService(
    private val store: FakeTargetStore,
    private val config: FakeTargetConfig,
    private val mailer: Mailer,
    private val secrets: SecretGenerator,
    private val clock: Clock,
) {
    fun registerOwner(input: OwnerSignUp): Outcome<String> {
        val email = Inputs.email(input.email)
        val phone = Inputs.phone(input.phone)
        Inputs.validateProfile(input.name, email, phone, input.password)?.let { return it.failed() }
        val companyName = input.companyName.trim()
        if (companyName.isEmpty() || companyName.length > Inputs.MAX_NAME_LENGTH) return Failure.COMPANY_NAME_REQUIRED.failed()
        val hash = PasswordHash.of(input.password, secrets.random)
        val code = secrets.sixDigitCode()
        val now = clock.instant()
        val user =
            store.transaction {
                if (users.containsKey(email)) return@transaction null
                val company =
                    Company(
                        id = nextId("c"),
                        name = companyName,
                        code = uniqueCompanyCode(),
                        ownerEmail = email,
                        isTest = Inputs.isTestEmail(email, config.testMailDomain),
                        createdAt = now,
                    )
                companies[company.id] = company
                createUser(company.id, input.name, email, phone, UserRole.ADMIN, department = null, hash, now).also {
                    emailCodes[email] = OneTimeCode(code, now + CODE_TTL)
                }
            } ?: return Failure.EMAIL_TAKEN.failed()
        mailer.sendVerificationCode(MailAddress(user.name, email), code)
        return email.ok()
    }

    /** Departments offered by the join form once the company code is known; null when the code is unknown. */
    fun departmentsForCode(code: String): List<Department>? = store.transaction { companyByCode(code)?.let { departmentsOf(it.id) } }

    /**
     * Joins by company code. A pending invitation for the same e-mail in that company wins: its role and department
     * apply (this is how an admin pre-provisions managers who join with the code) and it is marked accepted.
     */
    fun joinWithCode(input: CodeSignUp): Outcome<String> {
        if (input.companyCode.isBlank()) return Failure.COMPANY_CODE_REQUIRED.failed()
        val email = Inputs.email(input.email)
        val phone = Inputs.phone(input.phone)
        store.transaction { companyByCode(input.companyCode) } ?: return Failure.UNKNOWN_COMPANY_CODE.failed()
        Inputs.validateProfile(input.name, email, phone, input.password)?.let { return it.failed() }
        val hash = PasswordHash.of(input.password, secrets.random)
        val code = secrets.sixDigitCode()
        val now = clock.instant()
        val outcome =
            store.transaction {
                val company = companyByCode(input.companyCode) ?: return@transaction Failure.UNKNOWN_COMPANY_CODE.failed()
                if (users.containsKey(email)) return@transaction Failure.EMAIL_TAKEN.failed()
                val invitation = invitations.values.firstOrNull { it.companyId == company.id && it.email == email && it.acceptedAt == null }
                val department =
                    when (val chosen = joinDepartment(company.id, invitation, input.department)) {
                        is Outcome.Ok -> chosen.value
                        is Outcome.Failed -> return@transaction chosen
                    }
                invitation?.let { invitations[it.token] = it.copy(acceptedAt = now) }
                val role = invitation?.role ?: UserRole.EMPLOYEE
                emailCodes[email] = OneTimeCode(code, now + CODE_TTL)
                createUser(company.id, input.name, email, phone, role, department, hash, now).ok()
            }
        return outcome.sendCodeOnSuccess(code)
    }

    fun invitation(token: String): Outcome<InvitationView> =
        store.transaction {
            val invitation = invitations[token] ?: return@transaction Failure.INVITATION_NOT_FOUND.failed()
            val company = companies[invitation.companyId] ?: return@transaction Failure.INVITATION_NOT_FOUND.failed()
            if (invitation.acceptedAt != null || users.containsKey(invitation.email)) {
                Failure.INVITATION_USED.failed()
            } else {
                InvitationView(invitation, company).ok()
            }
        }

    /** Registers the invitee with the invited role and department; the e-mail comes from the invitation. */
    fun acceptInvitation(
        token: String,
        input: InviteSignUp,
    ): Outcome<String> {
        val view =
            when (val found = invitation(token)) {
                is Outcome.Ok -> found.value
                is Outcome.Failed -> return found
            }
        val email = view.invitation.email
        val phone = Inputs.phone(input.phone)
        Inputs.validateProfile(input.name, email, phone, input.password)?.let { return it.failed() }
        val hash = PasswordHash.of(input.password, secrets.random)
        val code = secrets.sixDigitCode()
        val now = clock.instant()
        val outcome =
            store.transaction {
                val invitation = invitations[token] ?: return@transaction Failure.INVITATION_NOT_FOUND.failed()
                if (invitation.acceptedAt != null) return@transaction Failure.INVITATION_USED.failed()
                if (users.containsKey(email)) return@transaction Failure.EMAIL_TAKEN.failed()
                invitations[token] = invitation.copy(acceptedAt = now)
                emailCodes[email] = OneTimeCode(code, now + CODE_TTL)
                createUser(invitation.companyId, input.name, email, phone, invitation.role, invitation.department, hash, now).ok()
            }
        return outcome.sendCodeOnSuccess(code)
    }

    /** The person a verification page talks about; null when nobody signed up with [email]. */
    fun user(email: String): User? = store.user(Inputs.email(email))

    /** Whether [user] still has to confirm the phone before a session is opened. */
    fun phoneStepPending(user: User): Boolean = config.requirePhoneOtp && !user.phoneVerified

    fun verifyEmail(
        email: String,
        code: String,
    ): Outcome<NextStep> {
        val normalized = Inputs.email(email)
        val now = clock.instant()
        return store.transaction {
            val user = users[normalized] ?: return@transaction Failure.NO_PENDING_VERIFICATION.failed()
            if (user.emailVerified) return@transaction Failure.ALREADY_VERIFIED.failed()
            consumeCode(emailCodes, normalized, code, now)?.let { return@transaction it.failed() }
            val verified = user.copy(emailVerified = true)
            users[normalized] = verified
            stepAfterEmail(verified, now).ok()
        }
    }

    fun resendEmailCode(email: String): Outcome<Unit> {
        val normalized = Inputs.email(email)
        val code = secrets.sixDigitCode()
        val now = clock.instant()
        val outcome =
            store.transaction {
                val user = users[normalized] ?: return@transaction Failure.NO_PENDING_VERIFICATION.failed()
                if (user.emailVerified) return@transaction Failure.ALREADY_VERIFIED.failed()
                emailCodes[normalized] = OneTimeCode(code, now + CODE_TTL)
                user.ok()
            }
        return when (outcome) {
            is Outcome.Ok -> {
                mailer.sendVerificationCode(MailAddress(outcome.value.name, normalized), code)
                Unit.ok()
            }

            is Outcome.Failed -> {
                outcome
            }
        }
    }

    /** Makes sure a phone code is pending (issued on first visit of the phone step) and returns the person. */
    fun ensurePhoneCode(email: String): Outcome<User> {
        val normalized = Inputs.email(email)
        val now = clock.instant()
        return store.transaction {
            val user = phoneStepUser(normalized)
            if (user is Outcome.Ok && phoneCodes[normalized].let { it == null || now.isAfter(it.expiresAt) }) {
                issuePhoneCode(user.value, now)
            }
            user
        }
    }

    fun resendPhoneCode(email: String): Outcome<Unit> {
        val normalized = Inputs.email(email)
        val now = clock.instant()
        return store.transaction {
            when (val user = phoneStepUser(normalized)) {
                is Outcome.Ok -> {
                    issuePhoneCode(user.value, now)
                    Unit.ok()
                }

                is Outcome.Failed -> {
                    user
                }
            }
        }
    }

    fun verifyPhone(
        email: String,
        code: String,
    ): Outcome<NextStep> {
        val normalized = Inputs.email(email)
        val now = clock.instant()
        return store.transaction {
            val user =
                when (val found = phoneStepUser(normalized)) {
                    is Outcome.Ok -> found.value
                    is Outcome.Failed -> return@transaction found
                }
            consumeCode(phoneCodes, normalized, code, now)?.let { return@transaction it.failed() }
            val verified = user.copy(phoneVerified = true)
            users[normalized] = verified
            openSession(verified).ok()
        }
    }

    /** Checks the password, then continues where the person left off (unverified e-mail or phone first). */
    fun login(
        email: String,
        password: String,
    ): Outcome<NextStep> {
        val normalized = Inputs.email(email)
        val hash = store.transaction { credentials[normalized] }
        if (hash == null || !hash.matches(password)) return Failure.BAD_CREDENTIALS.failed()
        val code = secrets.sixDigitCode()
        val now = clock.instant()
        val outcome =
            store.transaction {
                val user = users[normalized] ?: return@transaction Failure.BAD_CREDENTIALS.failed()
                if (!user.emailVerified) emailCodes[normalized] = OneTimeCode(code, now + CODE_TTL)
                (user to stepAfterEmail(user, now)).ok()
            }
        return when (outcome) {
            is Outcome.Failed -> {
                outcome
            }

            is Outcome.Ok -> {
                val (user, next) = outcome.value
                // An unverified e-mail gets a fresh code, since the first one may be long gone.
                if (next is NextStep.VerifyEmail) mailer.sendVerificationCode(MailAddress(user.name, normalized), code)
                next.ok()
            }
        }
    }

    fun logout(sessionToken: String) {
        store.transaction { sessions.remove(sessionToken) }
    }

    fun userBySession(sessionToken: String?): User? =
        sessionToken?.let { token -> store.transaction { sessions[token]?.let { users[it] } } }

    private fun Outcome<User>.sendCodeOnSuccess(code: String): Outcome<String> =
        when (this) {
            is Outcome.Ok -> {
                mailer.sendVerificationCode(MailAddress(value.name, value.email), code)
                value.email.ok()
            }

            is Outcome.Failed -> {
                this
            }
        }

    private fun StoreState.createUser(
        companyId: String,
        name: String,
        email: String,
        phone: String,
        role: UserRole,
        department: Department?,
        hash: PasswordHash,
        now: Instant,
    ): User {
        val user =
            User(
                id = nextId("u"),
                companyId = companyId,
                name = name.trim(),
                email = email,
                phone = phone,
                role = role,
                department = department,
                emailVerified = false,
                phoneVerified = false,
                createdAt = now,
            )
        users[email] = user
        credentials[email] = hash
        return user
    }

    /**
     * The invitation's department wins; otherwise the one picked in the form, which is required without an invitation
     * unless the company has no departments yet (the select is then empty, and refusing would be a dead end).
     */
    private fun StoreState.joinDepartment(
        companyId: String,
        invitation: Invitation?,
        picked: String,
    ): Outcome<Department?> =
        when {
            invitation?.department != null -> invitation.department.ok()
            picked.isNotBlank() -> department(companyId, picked)?.ok() ?: Failure.UNKNOWN_DEPARTMENT.failed()
            invitation != null || departmentsOf(companyId).isEmpty() -> null.ok()
            else -> Failure.DEPARTMENT_REQUIRED.failed()
        }

    /** `PTK-` and four digits; should those run out, six digits (so the loop under the store lock always ends). */
    private fun StoreState.uniqueCompanyCode(): String {
        val fourDigits = generateSequence { "PTK-${secrets.fourDigits()}" }.take(FOUR_DIGIT_ATTEMPTS)
        val sixDigits = generateSequence { "PTK-${secrets.sixDigitCode()}" }
        return unusedCompanyCode(fourDigits + sixDigits)
    }

    /** Next step once the e-mail is verified: the phone OTP (issued here) or a new session. */
    private fun StoreState.stepAfterEmail(
        user: User,
        now: Instant,
    ): NextStep =
        when {
            !user.emailVerified -> {
                NextStep.VerifyEmail(user.email)
            }

            config.requirePhoneOtp && !user.phoneVerified -> {
                issuePhoneCode(user, now)
                NextStep.VerifyPhone(user.email)
            }

            else -> {
                openSession(user)
            }
        }

    private fun StoreState.phoneStepUser(email: String): Outcome<User> {
        val user = users[email] ?: return Failure.NO_PENDING_VERIFICATION.failed()
        return when {
            !user.emailVerified -> Failure.EMAIL_NOT_VERIFIED.failed()
            user.phoneVerified || !config.requirePhoneOtp -> Failure.ALREADY_VERIFIED.failed()
            else -> user.ok()
        }
    }

    private fun StoreState.issuePhoneCode(
        user: User,
        now: Instant,
    ) {
        val code = secrets.sixDigitCode()
        phoneCodes[user.email] = OneTimeCode(code, now + CODE_TTL)
        latestPhoneOtps[Inputs.phoneDigits(user.phone)] = PhoneOtp(user.phone, user.email, code)
        // The fake's "SMS gateway": nothing is sent; the code is readable via /test/otp and, for manual demos, here.
        logger.info { "Fake SMS to ${user.phone}: Sizin təsdiq kodunuz: $code" }
    }

    private fun StoreState.openSession(user: User): NextStep.LoggedIn {
        val token = secrets.sessionToken()
        sessions[token] = user.email
        return NextStep.LoggedIn(token, user)
    }

    /** Null when [entered] is the pending code (which is then used up); otherwise why it was refused. */
    private fun consumeCode(
        codes: MutableMap<String, OneTimeCode>,
        email: String,
        entered: String,
        now: Instant,
    ): Failure? {
        val pending = codes[email] ?: return Failure.CODE_EXPIRED
        return when {
            pending.failedAttempts >= MAX_ATTEMPTS -> {
                Failure.TOO_MANY_ATTEMPTS
            }

            now.isAfter(pending.expiresAt) -> {
                Failure.CODE_EXPIRED
            }

            !MessageDigest.isEqual(pending.code.toByteArray(), entered.trim().toByteArray()) -> {
                val attempts = pending.failedAttempts + 1
                codes[email] = pending.copy(failedAttempts = attempts)
                if (attempts >= MAX_ATTEMPTS) Failure.TOO_MANY_ATTEMPTS else Failure.CODE_INVALID
            }

            else -> {
                codes.remove(email)
                null
            }
        }
    }

    private companion object {
        val CODE_TTL: Duration = Duration.ofMinutes(30)
        const val MAX_ATTEMPTS = 5
        const val FOUR_DIGIT_ATTEMPTS = 50
    }
}

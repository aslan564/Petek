/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.web

import az.petek.faketarget.service.AccountService
import az.petek.faketarget.service.CodeSignUp
import az.petek.faketarget.service.Failure
import az.petek.faketarget.service.InviteSignUp
import az.petek.faketarget.service.NextStep
import az.petek.faketarget.service.Outcome
import az.petek.faketarget.service.OwnerSignUp
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
internal data class DepartmentOptionJson(
    val id: String,
    val name: String,
)

@Serializable
internal data class DepartmentOptionsJson(
    val departments: List<DepartmentOptionJson>,
)

/** Routes a visitor uses before having a session: sign-up, verification, login and logout. */
internal class AuthRoutes(
    private val accounts: AccountService,
) {
    fun install(route: Route) {
        route.registerRoutes()
        route.joinRoutes()
        route.inviteRoutes()
        route.verifyRoutes()
        route.loginRoutes()
    }

    private fun Route.registerRoutes() {
        get("/register") {
            if (loggedIn(call)) return@get call.seeOther("/")
            call.respondHtml { registerPage(RegisterForm(), error = null) }
        }
        post("/register") {
            val params = call.receiveParameters()
            val form = RegisterForm(params.text("name"), params.text("email"), params.text("phone"), params.text("company"))
            val signUp = OwnerSignUp(form.name, form.email, form.phone, params.text("password"), form.company)
            when (val outcome = accounts.registerOwner(signUp)) {
                is Outcome.Ok -> call.seeOther(verifyLocation(outcome.value))
                is Outcome.Failed -> call.respondHtml { registerPage(form, outcome.failure.message) }
            }
        }
    }

    private fun Route.joinRoutes() {
        get("/join") {
            if (loggedIn(call)) return@get call.seeOther("/")
            val code =
                call.request.queryParameters["code"]
                    .orEmpty()
                    .trim()
            val departments = if (code.isEmpty()) null else accounts.departmentsForCode(code)
            val error = if (code.isNotEmpty() && departments == null) Failure.UNKNOWN_COMPANY_CODE.message else null
            call.respondHtml { joinPage(JoinForm(code = code), departments.orEmpty(), error) }
        }
        get("/join/departments") {
            val departments = accounts.departmentsForCode(call.request.queryParameters["code"].orEmpty())
            val body = DepartmentOptionsJson(departments.orEmpty().map { DepartmentOptionJson(it.id, it.name) })
            call.respond(if (departments == null) HttpStatusCode.NotFound else HttpStatusCode.OK, body)
        }
        post("/join") {
            val params = call.receiveParameters()
            val form =
                JoinForm(
                    code = params.text("code").trim(),
                    name = params.text("name"),
                    email = params.text("email"),
                    phone = params.text("phone"),
                    department = params.text("department"),
                )
            val signUp = CodeSignUp(form.code, form.name, form.email, form.phone, params.text("password"), form.department)
            when (val outcome = accounts.joinWithCode(signUp)) {
                is Outcome.Ok -> {
                    call.seeOther(verifyLocation(outcome.value))
                }

                is Outcome.Failed -> {
                    val departments = accounts.departmentsForCode(form.code).orEmpty()
                    call.respondHtml { joinPage(form, departments, outcome.failure.message) }
                }
            }
        }
    }

    private fun Route.inviteRoutes() {
        get("/invite/{token}") {
            val token = call.parameters["token"].orEmpty()
            when (val found = accounts.invitation(token)) {
                is Outcome.Ok -> call.respondHtml { invitePage(token, found.value, found.value.invitation.name, phone = "", error = null) }
                is Outcome.Failed -> call.respondHtml(found.failure.status) { invalidInvitePage(found.failure.message) }
            }
        }
        post("/invite/{token}") {
            val token = call.parameters["token"].orEmpty()
            val params = call.receiveParameters()
            val signUp = InviteSignUp(params.text("name"), params.text("phone"), params.text("password"))
            when (val outcome = accounts.acceptInvitation(token, signUp)) {
                is Outcome.Ok -> {
                    call.seeOther(verifyLocation(outcome.value))
                }

                is Outcome.Failed -> {
                    val view = accounts.invitation(token)
                    if (view is Outcome.Ok) {
                        call.respondHtml { invitePage(token, view.value, signUp.name, signUp.phone, outcome.failure.message) }
                    } else {
                        call.respondHtml(outcome.failure.status) { invalidInvitePage(outcome.failure.message) }
                    }
                }
            }
        }
    }

    private fun Route.verifyRoutes() {
        get("/verify") {
            val email = call.request.queryParameters["email"].orEmpty()
            val user = accounts.user(email)
            when {
                user == null -> call.respondHtml { verificationProblemPage("verify-error", Failure.NO_PENDING_VERIFICATION.message) }
                user.emailVerified && accounts.phoneStepPending(user) -> call.seeOther(verifyPhoneLocation(user.email))
                user.emailVerified -> call.seeOther("/login")
                else -> call.respondHtml { verifyPage(user.email, error = null) }
            }
        }
        post("/verify") {
            val params = call.receiveParameters()
            val email = params.text("email")
            when (val outcome = accounts.verifyEmail(email, params.text("code"))) {
                is Outcome.Ok -> {
                    call.proceed(outcome.value, next = null)
                }

                is Outcome.Failed -> {
                    when (outcome.failure) {
                        Failure.NO_PENDING_VERIFICATION -> {
                            call.respondHtml {
                                verificationProblemPage(
                                    "verify-error",
                                    outcome.failure.message,
                                )
                            }
                        }

                        Failure.ALREADY_VERIFIED -> {
                            call.alreadyVerified(email)
                        }

                        else -> {
                            call.respondHtml { verifyPage(email, outcome.failure.message) }
                        }
                    }
                }
            }
        }
        post("/verify/resend") {
            val email = call.receiveParameters().text("email")
            when (val outcome = accounts.resendEmailCode(email)) {
                is Outcome.Ok -> call.respondHtml { verifyPage(email, error = null, info = "Yeni kod göndərildi.") }
                is Outcome.Failed -> call.respondHtml { verificationProblemPage("verify-error", outcome.failure.message) }
            }
        }
        get("/verify/phone") {
            val email = call.request.queryParameters["email"].orEmpty()
            when (val outcome = accounts.ensurePhoneCode(email)) {
                is Outcome.Ok -> call.respondHtml { verifyPhonePage(outcome.value.email, outcome.value.phone, error = null) }
                is Outcome.Failed -> call.phoneStepUnavailable(email, outcome.failure)
            }
        }
        post("/verify/phone") {
            val params = call.receiveParameters()
            val email = params.text("email")
            when (val outcome = accounts.verifyPhone(email, params.text("code"))) {
                is Outcome.Ok -> {
                    call.proceed(outcome.value, next = null)
                }

                is Outcome.Failed -> {
                    val user = accounts.user(email)
                    if (user == null || outcome.failure in STEP_UNAVAILABLE) {
                        call.phoneStepUnavailable(email, outcome.failure)
                    } else {
                        call.respondHtml { verifyPhonePage(user.email, user.phone, outcome.failure.message) }
                    }
                }
            }
        }
        post("/verify/phone/resend") {
            val email = call.receiveParameters().text("email")
            val outcome = accounts.resendPhoneCode(email)
            val user = accounts.user(email)
            if (outcome is Outcome.Failed || user == null) {
                call.phoneStepUnavailable(email, (outcome as? Outcome.Failed)?.failure ?: Failure.NO_PENDING_VERIFICATION)
            } else {
                call.respondHtml { verifyPhonePage(user.email, user.phone, error = null, info = "Yeni SMS kodu göndərildi.") }
            }
        }
    }

    private fun Route.loginRoutes() {
        get("/login") {
            if (loggedIn(call)) return@get call.seeOther("/")
            val next = safeNext(call.request.queryParameters["next"])
            call.respondHtml { loginPage(email = "", next = next, error = null) }
        }
        post("/login") {
            val params = call.receiveParameters()
            val email = params.text("email")
            val next = safeNext(params["next"])
            when (val outcome = accounts.login(email, params.text("password"))) {
                is Outcome.Ok -> call.proceed(outcome.value, next)
                is Outcome.Failed -> call.respondHtml { loginPage(email, next, outcome.failure.message) }
            }
        }
        post("/logout") { call.logout() }
        get("/logout") { call.logout() }
    }

    private suspend fun ApplicationCall.logout() {
        SessionCookie.read(this)?.let(accounts::logout)
        SessionCookie.clear(this)
        seeOther("/login")
    }

    private suspend fun ApplicationCall.proceed(
        step: NextStep,
        next: String?,
    ) {
        when (step) {
            is NextStep.VerifyEmail -> {
                seeOther(verifyLocation(step.email))
            }

            is NextStep.VerifyPhone -> {
                seeOther(verifyPhoneLocation(step.email))
            }

            is NextStep.LoggedIn -> {
                SessionCookie.write(this, step.sessionToken)
                seeOther(next ?: "/")
            }
        }
    }

    private suspend fun ApplicationCall.phoneStepUnavailable(
        email: String,
        failure: Failure,
    ) {
        when (failure) {
            Failure.EMAIL_NOT_VERIFIED -> seeOther(verifyLocation(email))
            Failure.ALREADY_VERIFIED -> alreadyVerified(email)
            else -> respondHtml { verificationProblemPage("verify-phone-error", failure.message) }
        }
    }

    /**
     * A step submitted again after it succeeded (a double click, a reload): with a session go home, otherwise offer the
     * login, rather than showing a verification error to someone who is done.
     */
    private suspend fun ApplicationCall.alreadyVerified(email: String) {
        if (loggedIn(this)) return seeOther("/")
        respondHtml { loginPage(email, next = null, error = null, info = Failure.ALREADY_VERIFIED.message) }
    }

    private fun loggedIn(call: ApplicationCall): Boolean = accounts.userBySession(SessionCookie.read(call)) != null

    private fun Parameters.text(name: String): String = this[name].orEmpty()

    private companion object {
        /** Phone-step failures that are not about the entered code, so the code form would not help. */
        val STEP_UNAVAILABLE = setOf(Failure.NO_PENDING_VERIFICATION, Failure.EMAIL_NOT_VERIFIED, Failure.ALREADY_VERIFIED)
    }
}

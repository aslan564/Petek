package az.petek.faketarget.web

import az.petek.faketarget.model.Department
import az.petek.faketarget.model.UserRole
import az.petek.faketarget.service.InvitationView
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.hiddenInput
import kotlinx.html.p
import kotlinx.html.strong

// Pages a visitor sees before having a session: sign-up (three ways), verification and login.

internal data class RegisterForm(
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val company: String = "",
)

internal data class JoinForm(
    val code: String = "",
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val department: String = "",
)

internal fun HTML.registerPage(
    form: RegisterForm,
    error: String?,
) = page("Qeydiyyat", chrome = null) {
    h1 { +"Şirkət qeydiyyatı" }
    p { +"Şirkətinizi yaradın — siz onun admini olacaqsınız." }
    alert("register-error", error)
    form(action = "/register", method = FormMethod.post) {
        inputField("Ad Soyad", "register-name", "name", value = form.name, autocomplete = "name")
        inputField("E-poçt", "register-email", "email", InputType.email, form.email, "email")
        inputField("Telefon", "register-phone", "phone", InputType.tel, form.phone, "tel")
        inputField("Parol", "register-password", "password", InputType.password, autocomplete = "new-password")
        inputField("Şirkətin adı", "register-company", "company", value = form.company, autocomplete = "organization")
        submitButton("register-submit", "Qeydiyyatdan keç")
    }
    signUpLinks()
}

internal fun HTML.joinPage(
    form: JoinForm,
    departments: List<Department>,
    error: String?,
) = page("Şirkətə qoşul", chrome = null, pageScript = Assets.JOIN_DEPARTMENTS) {
    h1 { +"Şirkət kodu ilə qoşulun" }
    p { +"Şirkət kodunu (məsələn, PTK-4821) adminizdən alın." }
    alert("join-error", error)
    form(action = "/join", method = FormMethod.post) {
        inputField("Şirkət kodu", "join-company-code", "code", value = form.code, autocomplete = "off")
        inputField("Ad Soyad", "join-name", "name", value = form.name, autocomplete = "name")
        inputField("E-poçt", "join-email", "email", InputType.email, form.email, "email")
        inputField("Telefon", "join-phone", "phone", InputType.tel, form.phone, "tel")
        inputField("Parol", "join-password", "password", InputType.password, autocomplete = "new-password")
        selectField(
            label = "Departament",
            id = "join-department",
            name = "department",
            options = departments.map { it.name to it.name },
            selected = form.department,
            placeholder = "Departament seçin",
        )
        submitButton("join-submit", "Qoşul")
    }
    signUpLinks()
}

internal fun HTML.invitePage(
    token: String,
    view: InvitationView,
    name: String,
    phone: String,
    error: String?,
) = page("Dəvət", chrome = null) {
    val invitation = view.invitation
    h1 { +"Dəvəti qəbul edin" }
    p {
        strong { +view.company.name }
        +" sizi ${roleNoun(invitation.role)} kimi dəvət edir"
        invitation.department?.let { +" (${it.name} departamenti)" }
        +"."
    }
    alert("invite-error", error)
    form(action = "/invite/$token", method = FormMethod.post) {
        inputField("E-poçt", "invite-email", "invited_email", InputType.email, invitation.email) { readonly = true }
        inputField("Ad Soyad", "invite-name", "name", value = name, autocomplete = "name")
        inputField("Telefon", "invite-phone", "phone", InputType.tel, phone, "tel")
        inputField("Parol", "invite-password", "password", InputType.password, autocomplete = "new-password")
        submitButton("invite-submit", "Qəbul et")
    }
}

internal fun HTML.invalidInvitePage(message: String) =
    page("Dəvət", chrome = null) {
        h1 { +"Dəvət" }
        alert("invite-error", message)
        p { a(href = "/login") { +"Daxil olun" } }
    }

internal fun HTML.verifyPage(
    email: String,
    error: String?,
    info: String? = null,
) = page("E-poçt təsdiqi", chrome = null) {
    h1 { +"E-poçtu təsdiqləyin" }
    p {
        +"6 rəqəmli təsdiq kodunu "
        strong { +email }
        +" ünvanına göndərdik."
    }
    alert("verify-error", error)
    notice("verify-info", info)
    form(action = "/verify", method = FormMethod.post) {
        hiddenInput(name = "email") { value = email }
        inputField("Təsdiq kodu", "verify-code", "code", autocomplete = "one-time-code") { attributes["inputmode"] = "numeric" }
        submitButton("verify-submit", "Təsdiqlə")
    }
    resendForm("/verify/resend", email, "verify-resend")
}

internal fun HTML.verifyPhonePage(
    email: String,
    phone: String,
    error: String?,
    info: String? = null,
) = page("Telefon təsdiqi", chrome = null) {
    h1 { +"Telefonu təsdiqləyin" }
    p {
        +"SMS kodunu "
        strong { +phone }
        +" nömrəsinə göndərdik."
    }
    alert("verify-phone-error", error)
    notice("verify-phone-info", info)
    form(action = "/verify/phone", method = FormMethod.post) {
        hiddenInput(name = "email") { value = email }
        inputField("SMS kodu", "verify-phone-code", "code", autocomplete = "one-time-code") { attributes["inputmode"] = "numeric" }
        submitButton("verify-phone-submit", "Təsdiqlə")
    }
    resendForm("/verify/phone/resend", email, "verify-phone-resend")
}

/** A verification step that cannot continue (unknown e-mail and the like). */
internal fun HTML.verificationProblemPage(
    id: String,
    message: String,
) = page("Təsdiq", chrome = null) {
    h1 { +"Təsdiq" }
    alert(id, message)
    signUpLinks()
}

internal fun HTML.loginPage(
    email: String,
    next: String?,
    error: String?,
    info: String? = null,
) = page("Daxil ol", chrome = null) {
    h1 { +"Daxil olun" }
    alert("login-error", error)
    notice("login-info", info)
    form(action = "/login", method = FormMethod.post) {
        if (next != null) hiddenInput(name = "next") { value = next }
        inputField("E-poçt", "login-email", "email", InputType.email, email, "username")
        inputField("Parol", "login-password", "password", InputType.password, autocomplete = "current-password")
        submitButton("login-submit", "Daxil ol")
    }
    signUpLinks()
}

private fun FlowContent.resendForm(
    action: String,
    email: String,
    id: String,
) {
    form(action = action, method = FormMethod.post) {
        hiddenInput(name = "email") { value = email }
        button(type = ButtonType.submit, classes = "secondary") {
            testId(id)
            +"Kodu yenidən göndər"
        }
    }
}

private fun roleNoun(role: UserRole): String =
    when (role) {
        UserRole.ADMIN -> "admin"
        UserRole.MANAGER -> "menecer"
        UserRole.EMPLOYEE -> "işçi"
    }

private fun FlowContent.signUpLinks() {
    p {
        a(href = "/login") { +"Daxil ol" }
        +" · "
        a(href = "/register") { +"Şirkət qeydiyyatı" }
        +" · "
        a(href = "/join") { +"Şirkət kodu ilə qoşul" }
    }
}

/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.domain

/**
 * What kind of site the explorer walked (docs/PLAN.md Faza 17, `LINK_ONLY_SWARM.md` §0 point 2): a shop, a news site,
 * a showcase and a sign-in system are tested differently, so the kind picks the bug cards (Faza 19).
 */
enum class SiteKind {
    SHOP,
    NEWS,
    SHOWCASE,
    SIGN_IN_SYSTEM,
    OTHER,
}

/** The kind and why: the words or the shape of the anonymous pages that decided it. */
data class SiteKindVerdict(
    val kind: SiteKind,
    val reason: String,
)

/**
 * Decides the kind from what the explorer saw, by code: page titles, purposes, addresses, test ids, form purposes and
 * action names. Shop and news words win when they are there; a site whose pages are mostly its gate (sign-in, sign-up,
 * verification) is a sign-in system; a site with content pages and no gate is a showcase.
 */
object SiteKinds {
    private val SHOP_WORDS =
        listOf(
            "cart",
            "basket",
            "checkout",
            "səbət",
            "sebet",
            "sifariş",
            "sifaris",
            "product",
            "məhsul",
            "shop",
            "mağaza",
            "coupon",
            "kupon",
        )
    private val NEWS_WORDS =
        listOf("news", "xəbər", "xeber", "article", "məqalə", "meqale", "blog", "comment", "şərh", "serh", "editorial", "manşet", "yazılar")

    fun of(model: SiteModel): SiteKindVerdict {
        val text = words(model)
        val shop = SHOP_WORDS.filter { it in text }
        val news = NEWS_WORDS.filter { it in text }
        if (shop.size >= 2 && shop.size >= news.size) return SiteKindVerdict(SiteKind.SHOP, "shop words: ${shop.joinToString()}")
        if (news.size >= 2) return SiteKindVerdict(SiteKind.NEWS, "news words: ${news.joinToString()}")
        val gates = GateMaps.of(model)
        val content = contentPages(model)
        if ((gates.register != null || gates.login != null) && content.isEmpty()) {
            return SiteKindVerdict(SiteKind.SIGN_IN_SYSTEM, "an anonymous visitor sees only the sign-in and sign-up pages")
        }
        if (gates.register == null && gates.login == null && content.isNotEmpty()) {
            return SiteKindVerdict(SiteKind.SHOWCASE, "${content.size} content pages and no sign-in")
        }
        if (gates.register != null || gates.login != null) {
            return SiteKindVerdict(SiteKind.SIGN_IN_SYSTEM, "a sign-in gate in front of ${content.size} public pages")
        }
        return SiteKindVerdict(SiteKind.OTHER, "nothing typical of a shop, news site, showcase or sign-in system")
    }

    /** Pages an anonymous visitor reached that are not part of the gate. */
    internal fun contentPages(model: SiteModel): List<PageModel> {
        val anonymous =
            model.roles
                .filter { it.anonymous }
                .flatMap { it.pageIds }
                .toSet()
        return model.pages.filter { it.id in anonymous && !GateMaps.isGatePage(it) }
    }

    private fun words(model: SiteModel): String =
        buildList {
            model.pages.forEach { page ->
                add(page.title)
                add(page.purpose)
                add(page.urlPattern)
                addAll(page.testIds)
                page.forms.forEach { add(it.purpose) }
            }
            model.actions.forEach { add(it.name) }
        }.joinToString(" ").lowercase()
}

/** How a tester proves who they are after signing up, as far as the anonymous pages show. */
enum class OtpKind { EMAIL_CODE, EMAIL_LINK, SMS, NOT_SEEN }

/** Why the gate cannot be passed by code, said honestly instead of retrying (`LINK_ONLY_SWARM.md` §7). */
enum class GateBlocker {
    /** A CAPTCHA is on the sign-up or sign-in form. */
    CAPTCHA,

    /** Neither a sign-up nor a sign-in form was found. */
    NO_GATE,

    /** The sign-up form asks for an invitation or company code the testers do not have. */
    INVITE_ONLY,

    /** There is a sign-in form but no sign-up: testers need the owner's accounts. */
    NO_SIGN_UP,
}

/**
 * One form of the gate, mapped onto the profile's selector keys (`register.email`, `login.password`, …) so the
 * default `sign_up` and `login` flows pass it by code. [unmapped] are required fields no key covers.
 */
data class GateForm(
    val path: String,
    val selectors: Map<String, String>,
    val unmapped: List<String>,
)

/**
 * The gate of the site (Faza 17 "Qapının xəritəsi"): sign-up, sign-in, whether a visitor sees anything, the kind of
 * verification, password reset, CAPTCHA and invitations. Learned once from the explorer's model; testers then pass it
 * by code through [profilePaths] and [profileSelectors] (Faza 18).
 */
data class GateMap(
    val register: GateForm?,
    val login: GateForm?,
    val guest: Boolean,
    val otp: OtpKind,
    val forgotPassword: Boolean,
    val captcha: Boolean,
    val invite: Boolean,
) {
    /** Why testers cannot get in by code; empty when the gate is open. */
    val blockers: List<GateBlocker>
        get() =
            buildList {
                if (captcha) add(GateBlocker.CAPTCHA)
                if (register == null && login == null) add(GateBlocker.NO_GATE)
                if (register != null && invite) add(GateBlocker.INVITE_ONLY)
                if (register == null && login != null) add(GateBlocker.NO_SIGN_UP)
            }

    /** The profile's path keys for the gate pages (`register`, `login`). */
    val profilePaths: Map<String, String>
        get() = listOfNotNull(register?.let { "register" to it.path }, login?.let { "login" to it.path }).toMap()

    /** The profile's selector keys for the gate forms. */
    val profileSelectors: Map<String, String> get() = register?.selectors.orEmpty() + login?.selectors.orEmpty()
}

/** Builds the [GateMap] from a site model, by code. */
object GateMaps {
    private val GATE_PATH =
        Regex("(?i)(login|sign-?in|sign-?up|register|registration|verify|confirm|otp|forgot|reset|password|giris|daxil|qeydiyyat)")
    private val FORGOT = Regex("(?i)(forgot|reset|unutdum|bərpa|berpa|recover)")
    private val CAPTCHA = Regex("(?i)(captcha|recaptcha|hcaptcha|turnstile)")
    private val INVITE = Regex("(?i)(invite|invitation|dəvət|devet|company.?code|join.?code)")
    private val CODE_FIELD = Regex("(?i)^(code|otp|token|verification.?code|kod)$")

    fun isGatePage(page: PageModel): Boolean =
        GATE_PATH.containsMatchIn(page.urlPattern) || page.forms.any { it.kind == ActionKind.LOGIN || it.kind == ActionKind.REGISTER }

    fun of(model: SiteModel): GateMap {
        val pages = model.pages.filter { UrlPatterns.ID !in it.urlPattern }
        val registerPage = pages.firstOrNull { page -> page.forms.any { it.kind == ActionKind.REGISTER } }
        val loginPage = pages.firstOrNull { page -> page.forms.any { it.kind == ActionKind.LOGIN } }
        val registerForm = registerPage?.forms?.first { it.kind == ActionKind.REGISTER }
        val loginForm = loginPage?.forms?.first { it.kind == ActionKind.LOGIN }
        val allFields = pages.flatMap { page -> page.forms.flatMap { it.fields } }
        val text = pages.joinToString(" ") { "${it.urlPattern} ${it.title} ${it.purpose} ${it.testIds.joinToString(" ")}" }
        return GateMap(
            register = registerPage?.let { page -> registerForm?.let { gateForm(page, it, REGISTER) } },
            login = loginPage?.let { page -> loginForm?.let { gateForm(page, it, LOGIN) } },
            guest = SiteKinds.contentPages(model).isNotEmpty(),
            otp = otpOf(pages),
            forgotPassword = FORGOT.containsMatchIn(text) || model.actions.any { FORGOT.containsMatchIn(it.name) },
            captcha =
                CAPTCHA.containsMatchIn(text) ||
                    allFields.any { CAPTCHA.containsMatchIn(it.name) || CAPTCHA.containsMatchIn(it.testId.orEmpty()) },
            invite =
                registerForm?.fields?.any {
                    it.required && (
                        INVITE.containsMatchIn(
                            it.name,
                        ) || INVITE.containsMatchIn(it.label)
                    )
                } == true ||
                    (registerForm == null && INVITE.containsMatchIn(text)),
        )
    }

    private fun otpOf(pages: List<PageModel>): OtpKind {
        val verify =
            pages.filter {
                GATE_PATH.containsMatchIn(it.urlPattern) &&
                    Regex("(?i)(verify|confirm|otp)").containsMatchIn(it.urlPattern)
            }
        val fields = verify.flatMap { page -> page.forms.flatMap { it.fields } }
        return when {
            fields.any { it.type == "tel" || it.name.contains("phone", ignoreCase = true) } -> OtpKind.SMS
            fields.any { CODE_FIELD.matches(it.name) } -> OtpKind.EMAIL_CODE
            verify.any { it.urlPattern.contains("token", ignoreCase = true) } -> OtpKind.EMAIL_LINK
            else -> OtpKind.NOT_SEEN
        }
    }

    /** Maps [form]'s fields onto `<prefix>.<key>` selector keys; required fields no key covers stay [GateForm.unmapped]. */
    private fun gateForm(
        page: PageModel,
        form: FormModel,
        prefix: String,
    ): GateForm {
        val selectors = linkedMapOf<String, String>()
        val unmapped = mutableListOf<String>()
        var passwords = 0
        form.fields.forEach { field ->
            val name = "${field.name} ${field.testId.orEmpty()} ${field.label}".lowercase()
            val key =
                when {
                    field.type == "password" -> if (passwords++ == 0) "password" else "confirm_password".takeIf { prefix == REGISTER }
                    field.type == "email" || "mail" in name || "poçt" in name -> "email"
                    prefix == LOGIN && ("user" in name || "login" in name) -> "email"
                    field.type == "tel" || "phone" in name || "telefon" in name -> "phone".takeIf { prefix == REGISTER }
                    field.type == "checkbox" && field.required -> "terms".takeIf { prefix == REGISTER }
                    prefix == REGISTER && ("name" in name || "ad" == field.name.lowercase() || "ad soyad" in name) -> "name"
                    else -> null
                }
            when {
                key != null && "$prefix.$key" !in selectors -> selectors["$prefix.$key"] = field.selector
                field.required -> unmapped += field.name.ifBlank { field.label }
            }
        }
        form.submitSelector?.let { selectors["$prefix.submit"] = it }
        return GateForm(page.urlPattern, selectors, unmapped)
    }

    private const val REGISTER = "register"
    private const val LOGIN = "login"
}

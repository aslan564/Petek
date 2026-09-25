package az.petek.campaign.domain

/**
 * What the harness needs to know about the target UI for deterministic `run` steps: page paths and selectors.
 * Defaults follow the `data-testid` contract in docs/TARGET_CONTRACT.md; the campaign YAML `target:` section overrides them.
 */
data class TargetProfile(
    val paths: Map<String, String>,
    val selectors: Map<String, String>,
    /** Event name -> where the created object's id is read from. */
    val idSources: Map<String, IdSource>,
) {
    fun path(key: String): String = paths[key] ?: DEFAULT_PATHS[key] ?: error("Unknown target path key '$key'")

    fun selector(key: String): String = selectors[key] ?: DEFAULT_SELECTORS[key] ?: error("Unknown target selector key '$key'")

    fun idSource(event: String): IdSource? = idSources[event]

    companion object {
        val DEFAULT_PATHS: Map<String, String> =
            mapOf(
                "login" to "/login",
                "register" to "/register",
                "verify" to "/verify",
                "verify_phone" to "/verify/phone",
                "join" to "/join",
                "company" to "/company",
                "announcements" to "/announcements",
                "tickets" to "/tickets",
                "home" to "/",
            )

        private fun tid(id: String) = "[data-testid=\"$id\"]"

        val DEFAULT_SELECTORS: Map<String, String> =
            mapOf(
                "login.email" to tid("login-email"),
                "login.password" to tid("login-password"),
                "login.submit" to tid("login-submit"),
                "login.error" to tid("login-error"),
                "register.name" to tid("register-name"),
                "register.email" to tid("register-email"),
                "register.phone" to tid("register-phone"),
                "register.password" to tid("register-password"),
                "register.company" to tid("register-company"),
                "register.submit" to tid("register-submit"),
                "verify.code" to tid("verify-code"),
                "verify.submit" to tid("verify-submit"),
                "verify.phone_code" to tid("verify-phone-code"),
                "verify.phone_submit" to tid("verify-phone-submit"),
                "join.code" to tid("join-company-code"),
                "join.name" to tid("join-name"),
                "join.email" to tid("join-email"),
                "join.phone" to tid("join-phone"),
                "join.password" to tid("join-password"),
                "join.department" to tid("join-department"),
                "join.submit" to tid("join-submit"),
                "invite.name" to tid("invite-name"),
                "invite.phone" to tid("invite-phone"),
                "invite.password" to tid("invite-password"),
                "invite.submit" to tid("invite-submit"),
                "session.user_name" to tid("current-user-name"),
                "session.user_role" to tid("current-user-role"),
                "session.logout" to tid("logout"),
                "company.code" to tid("company-code"),
                "notification.item" to tid("notification-item"),
            )

        val DEFAULT: TargetProfile = TargetProfile(emptyMap(), emptyMap(), emptyMap())
    }
}

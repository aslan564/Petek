package az.petek.agent.application.runs

/** Where the sign-up / sign-in journey currently is, as read from the target's `data-testid`s. */
internal enum class PageState(
    val label: String,
) {
    EMAIL_CODE("the e-mail code step"),
    PHONE_CODE("the phone code step"),
    LOGIN_PAGE("the login page"),
    LOGGED_IN("a signed-in page"),
    OTHER("an unrecognised page"),
}

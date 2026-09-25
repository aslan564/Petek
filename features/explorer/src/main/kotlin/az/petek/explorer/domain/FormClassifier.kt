package az.petek.explorer.domain

/**
 * Classifies a form (or a lone button) by code from its test ids, ids, names, button texts, method and action path, in
 * English and Azerbaijani. The rules favour caution, because [ActionKind.CREATE] is what the trial touch may submit:
 * a form with editable fields is never taken for an approval (the e-mail code form's button also says "Təsdiqlə"),
 * anything that looks like logging out or posts to another site is [ActionKind.OTHER], a form sent with the DELETE
 * method is [ActionKind.DELETE], and sign-up and sign-in forms stay [ActionKind.REGISTER]/[ActionKind.LOGIN] also
 * without a password field (magic links, invitation codes).
 */
object FormClassifier {
    private val LOGOUT = setOf("logout", "logoff", "signout", "çıxış", "cixis")
    private val DELETE = setOf("delete", "remove", "destroy", "erase", "purge", "sil", "silin", "silmək", "silmek")
    private val APPROVE = setOf("approve", "accept", "təsdiq", "təsdiqlə", "qəbul")
    private val REJECT = setOf("reject", "decline", "deny", "rədd", "imtina")
    private val ASSIGN = setOf("assign", "təyin", "reassign")
    private val UPDATE =
        setOf(
            "update",
            "edit",
            "save",
            "progress",
            "status",
            "start",
            "reopen",
            "close",
            "resolve",
            "rename",
            "icra",
            "icraya",
            "yadda",
            "dəyiş",
            "redaktə",
            "yenilə",
        )
    private val CREATE =
        setOf(
            "create",
            "new",
            "add",
            "post",
            "publish",
            "send",
            "submit",
            "compose",
            "write",
            "yarat",
            "əlavə",
            "dərc",
            "göndər",
            "yeni",
            "yaz",
        )
    private val LOGIN = setOf("login", "signin", "daxil")
    private val REGISTER = setOf("register", "signup", "join", "invite", "qeydiyyat", "qoşul", "dəvət", "registration")

    /**
     * Sign-up and sign-in words that hold without a password field. Narrower than [REGISTER]/[LOGIN]: an admin's
     * "invite" form creates an invitation, and `daxil et` (enter data) is not `daxil ol` (sign in).
     */
    private val PASSWORDLESS_REGISTER = setOf("register", "signup", "registration", "join", "qeydiyyat", "qoşul")
    private val PASSWORDLESS_REGISTER_PHRASES = listOf("sign up")
    private val PASSWORDLESS_LOGIN = setOf("login", "signin")
    private val PASSWORDLESS_LOGIN_PHRASES = listOf("log in", "sign in", "daxil ol")
    private val VERIFY = setOf("verify", "verification", "code", "otp", "confirm", "təsdiq")
    private val SEARCH = setOf("search", "find", "filter", "query", "axtar", "axtarış")
    private val CODE_FIELDS = setOf("code", "otp", "token", "pin")
    private val SEARCH_FIELDS = setOf("q", "search", "query", "s")

    /** A kind and a short human purpose, e.g. `LOGIN` and `login form 'Daxil ol'`. */
    data class Classification(
        val kind: ActionKind,
        val purpose: String,
    )

    /**
     * [actionPattern] is the generalised same-site path the form is sent to; [offSite] says it is sent to another
     * origin (then the form is [ActionKind.OTHER] whatever it says). [ScannedForm.method] is taken as the method that
     * is really sent, `_method` overrides included.
     */
    fun classify(
        form: ScannedForm,
        actionPattern: String?,
        offSite: Boolean = false,
    ): Classification {
        val submit = form.buttons.firstOrNull { it.type == "submit" || it.type == "image" } ?: form.buttons.firstOrNull()
        val label = submit?.text?.takeIf { it.isNotBlank() }
        if (offSite) {
            val purpose = label?.let { "form '$it' sent to another site" } ?: "form sent to another site"
            return Classification(ActionKind.OTHER, purpose)
        }
        val words =
            listOfNotNull(form.testId, form.id, actionPattern, label, submit?.testId, submit?.id, submit?.name)
                .joinToString(" ")
        val editable = form.fields.filter { it.type !in setOf("checkbox", "radio") }
        val kind =
            when {
                form.fields.any { it.type == "password" } -> passwordForm(words, editable.size)
                else -> kindOf(words, editable, form, actionPattern)
            }
        val noun =
            when {
                kind == ActionKind.OTHER && Keywords.containsStem(words, LOGOUT) -> "logout"
                kind == ActionKind.SUBMIT && isVerification(words, editable) -> "verification"
                kind == ActionKind.SUBMIT && isSearch(words, editable, form) -> "search"
                else -> kind.name.lowercase()
            }
        return Classification(kind, if (label != null) "$noun form '$label'" else "$noun form")
    }

    /** Kind of a button outside any form, or null when its words say nothing about what it does. */
    fun buttonKind(words: String): ActionKind? =
        when {
            Keywords.containsStem(words, LOGOUT) -> ActionKind.OTHER
            Keywords.containsStem(words, DELETE) -> ActionKind.DELETE
            Keywords.containsStem(words, APPROVE) -> ActionKind.APPROVE
            Keywords.containsStem(words, REJECT) -> ActionKind.REJECT
            Keywords.containsStem(words, ASSIGN) -> ActionKind.ASSIGN
            Keywords.containsStem(words, CREATE) -> ActionKind.CREATE
            Keywords.containsStem(words, UPDATE) -> ActionKind.UPDATE
            else -> null
        }

    private fun passwordForm(
        words: String,
        editableFields: Int,
    ): ActionKind =
        when {
            Keywords.containsStem(words, REGISTER) -> ActionKind.REGISTER

            Keywords.containsStem(words, LOGIN) -> ActionKind.LOGIN

            // e-mail + password (+ the password itself) is a sign-in; more fields ask for a new account.
            editableFields <= 2 -> ActionKind.LOGIN

            else -> ActionKind.REGISTER
        }

    private fun kindOf(
        words: String,
        editable: List<ScannedField>,
        form: ScannedForm,
        actionPattern: String?,
    ): ActionKind {
        if (Keywords.containsStem(words, LOGOUT)) return ActionKind.OTHER
        if (Keywords.containsStem(words, DELETE) || form.method == "DELETE") return ActionKind.DELETE
        if (editable.isEmpty() || editable.all { it.tag == "select" }) {
            buttonOnlyKind(words, editable)?.let { return it }
        }
        return when {
            isVerification(words, editable) -> ActionKind.SUBMIT
            isSearch(words, editable, form) -> ActionKind.SUBMIT
            isPasswordlessSignUp(words) -> ActionKind.REGISTER
            isPasswordlessSignIn(words) -> ActionKind.LOGIN
            Keywords.containsStem(words, ASSIGN) -> ActionKind.ASSIGN
            Keywords.containsStem(words, REJECT) -> ActionKind.REJECT
            Keywords.containsStem(words, UPDATE) && actionPattern?.let(UrlPatterns::hasId) == true -> ActionKind.UPDATE
            editable.isEmpty() -> ActionKind.SUBMIT
            Keywords.containsStem(words, CREATE) -> ActionKind.CREATE
            form.method == "POST" && actionPattern != null && !actionPattern.endsWith(UrlPatterns.ID) -> ActionKind.CREATE
            Keywords.containsStem(words, UPDATE) -> ActionKind.UPDATE
            else -> ActionKind.SUBMIT
        }
    }

    private fun isPasswordlessSignUp(words: String): Boolean =
        Keywords.containsStem(words, PASSWORDLESS_REGISTER) || PASSWORDLESS_REGISTER_PHRASES.any { Keywords.containsPhrase(words, it) }

    private fun isPasswordlessSignIn(words: String): Boolean =
        Keywords.containsStem(words, PASSWORDLESS_LOGIN) || PASSWORDLESS_LOGIN_PHRASES.any { Keywords.containsPhrase(words, it) }

    /** Forms that are just a button (and maybe a choice): approve, reject, assign, a status change. */
    private fun buttonOnlyKind(
        words: String,
        editable: List<ScannedField>,
    ): ActionKind? =
        when {
            Keywords.containsStem(words, APPROVE) -> ActionKind.APPROVE
            Keywords.containsStem(words, REJECT) -> ActionKind.REJECT
            Keywords.containsStem(words, ASSIGN) -> ActionKind.ASSIGN
            Keywords.containsStem(words, UPDATE) -> ActionKind.UPDATE
            editable.isEmpty() -> ActionKind.SUBMIT
            else -> null
        }

    private fun isVerification(
        words: String,
        editable: List<ScannedField>,
    ): Boolean =
        editable.isNotEmpty() &&
            editable.size <= 2 &&
            editable.any { field ->
                listOfNotNull(field.name, field.id, field.autocomplete).any {
                    it.lowercase() in CODE_FIELDS ||
                        it == "one-time-code"
                }
            } &&
            (Keywords.containsStem(words, VERIFY) || editable.size == 1)

    private fun isSearch(
        words: String,
        editable: List<ScannedField>,
        form: ScannedForm,
    ): Boolean =
        editable.size == 1 &&
            (
                editable.single().type == "search" ||
                    editable.single().name?.lowercase() in SEARCH_FIELDS ||
                    (form.method == "GET" && Keywords.containsStem(words, SEARCH))
            )
}

package az.petek.campaign.domain

import kotlin.time.Duration

/**
 * A deterministic flow that a `run` function executes on the tester's own browser, written as data so that Pətək fits
 * any site: a sign-up with an e-mail link, a login with a company code or a consent dialog are YAML under
 * `target_profile.flows`, not code. The run functions look flows up by name ([FlowNames], [TargetProfile.flow]); the
 * defaults ([TargetProfile.DEFAULT_FLOWS]) follow docs/TARGET_CONTRACT.md, so a campaign for the fake target needs none.
 *
 * Steps address elements by *selector reference*: a key of [TargetProfile.selectors] or
 * [TargetProfile.DEFAULT_SELECTORS] (`login.email`) stands for that key's selector (and evidence shows the key), anything
 * else is a selector as written: CSS, or Playwright syntax such as `role=button[name="Daxil ol"]`. Paths work the same
 * with [TargetProfile.paths].
 *
 * Values, selectors, paths and failure messages are templates rendered by the harness right before the step:
 * `{self.<field>}` ([Placeholder.FLOW_SELF_FIELDS]; `{self.password}` only in `fill` values), `{shared.<key>}`
 * (published for the whole run: `company_code`, `company_id`, `invite_link` = this tester's invitation; awaited until
 * published), `{vars.<key>}` (this tester's own values: `email_code` and whatever `email_link` or `read` stored) and
 * `{campaign.company}`. Regular expressions (`expect_url`, `read.regex`, `email_link.pattern`) are not templates.
 * Flow values never reach the LLM.
 */
data class Flow(
    val steps: List<FlowStep>,
)

/** Names of the flows the built-in `run` functions execute. */
object FlowNames {
    /** `register_owner`: the company owner's sign-up (and, when it ends signed in, nothing else to do). */
    const val REGISTER_OWNER = "register_owner"

    /** `register_and_login` for testers invited by the admin (their identity's registration mode is `invite`). */
    const val JOIN_BY_INVITE = "join_by_invite"

    /** `register_and_login` for testers joining with the company code. */
    const val JOIN_BY_CODE = "join_by_code"

    /** `login`, and every sign-in the other run functions still need after their own flow. */
    const val LOGIN = "login"

    /** `verify_identity`: proves the session belongs to this tester; must contain an `assert_identity` step. */
    const val VERIFY_IDENTITY = "verify_identity"

    val ALL: Set<String> = linkedSetOf(REGISTER_OWNER, JOIN_BY_INVITE, JOIN_BY_CODE, LOGIN, VERIFY_IDENTITY)
}

/** One step of a [Flow]. [key] is its YAML key, as written in campaign files and shown in messages. */
sealed interface FlowStep {
    val key: String

    /** Opens a page: a path key (`login`), a `/path` on the target, or a template rendering to one (`{vars.verify_link}`). */
    data class Goto(
        val path: String,
    ) : FlowStep {
        override val key: String get() = "goto"
    }

    data class Fill(
        val selector: String,
        val value: String,
    ) : FlowStep {
        override val key: String get() = "fill"
    }

    /** Chooses an option of a `<select>` by its label (or value). */
    data class Select(
        val selector: String,
        val option: String,
    ) : FlowStep {
        override val key: String get() = "select"
    }

    /** Ticks a checkbox or radio: clicks it unless it is already checked (`aria-checked="true"` or `checked`). */
    data class Check(
        val selector: String,
    ) : FlowStep {
        override val key: String get() = "check"
    }

    data class Click(
        val selector: String,
    ) : FlowStep {
        override val key: String get() = "click"
    }

    /** Clicks [selector] when it is visible right now and does nothing otherwise (optional banners, "skip" buttons). */
    data class ClickIfVisible(
        val selector: String,
    ) : FlowStep {
        override val key: String get() = "click_if_visible"
    }

    /**
     * Waits until one of [selectors] (any of them) or [text] is visible; exactly one of the two is given. [timeout] null
     * means the harness's UI timeout. [failure] replaces the default report when nothing appears.
     */
    data class WaitFor(
        val selectors: List<String>,
        val text: String?,
        val timeout: Duration? = null,
        val failure: FlowFailure? = null,
    ) : FlowStep {
        override val key: String get() = "wait_for"
    }

    /** Waits until the page URL contains a match of [regex], at most [timeout] (null: the harness's page timeout). */
    data class ExpectUrl(
        val regex: String,
        val timeout: Duration? = null,
        val failure: FlowFailure? = null,
    ) : FlowStep {
        override val key: String get() = "expect_url"
    }

    /**
     * Awaits this tester's newest e-mail with a link of [purpose] (only mail received after the run started, each used
     * once), stores the link in [into] (default [LinkPurpose.defaultTarget]) and opens it when [open]. [pattern] is a
     * regular expression the link must contain a match of; without it the inbox's own heuristic picks the link. For
     * [LinkPurpose.INVITE] the link the admin's `seed_company` published for this tester is used first, and a link
     * stored by an earlier attempt is reused (an invitation link does not change).
     */
    data class EmailLink(
        val purpose: LinkPurpose,
        val pattern: String? = null,
        val open: Boolean = true,
        val into: ValueTarget? = null,
    ) : FlowStep {
        override val key: String get() = "email_link"

        /** Where the link is stored. */
        val target: ValueTarget get() = into ?: purpose.defaultTarget
    }

    /**
     * Awaits this tester's newest verification code, stores it as `{vars.email_code}` and types it into [selector].
     * With [submit] it also clicks that and checks the answer: a code whose field is still shown afterwards counts as
     * rejected, a newer code is awaited once, and a second rejection fails the flow with `otp_rejected`.
     */
    data class EmailCode(
        val selector: String,
        val submit: String? = null,
    ) : FlowStep {
        override val key: String get() = "email_code"
    }

    /**
     * Reads this tester's newest phone code from the target's test API (the test mode sends no SMS), stores it as
     * `{vars.phone_code}` and types it into [selector]; with [submit] a code whose field stays is `otp_rejected`.
     */
    data class PhoneCode(
        val selector: String,
        val submit: String? = null,
    ) : FlowStep {
        override val key: String get() = "phone_code"
    }

    /**
     * Reads the text of [selector] (the `value` of a form field when it has no text) and stores it in [into]. With
     * [regex], the first capture group of its first match is stored (the whole match when it has no group).
     */
    data class Read(
        val selector: String,
        val into: ValueTarget,
        val regex: String? = null,
    ) : FlowStep {
        override val key: String get() = "read"
    }

    /** Publishes [value] as `{shared.<key>}` for every tester of the run. */
    data class SetShared(
        val sharedKey: String,
        val value: String,
    ) : FlowStep {
        override val key: String get() = "set_shared"
    }

    /** Runs [then] when [selector] is visible, waiting for it up to [timeout] (null: checks once, without waiting). */
    data class IfVisible(
        val selector: String,
        val then: List<FlowStep>,
        val timeout: Duration? = null,
    ) : FlowStep {
        override val key: String get() = "if_visible"
    }

    /** Saves the browser's cookies and storage for later restores (after a sign-in). */
    data object SaveSession : FlowStep {
        override val key: String get() = "save_session"
    }

    /**
     * Marks the point from which the tester's account exists on the site (the registration form was accepted): when
     * `register_and_login` fails later on and tries again, it signs in with the `login` flow instead of registering a
     * second time. A join flow without it counts as having created the account only when it finished.
     */
    data object AccountCreated : FlowStep {
        override val key: String get() = "account_created"
    }

    /**
     * Proves whose session the browser holds: waits for [selector] (the signed-in user's name) and compares its text
     * with the tester's display name (trimmed, whitespace collapsed, Unicode NFC). Another name is `identity_mismatch`.
     */
    data class AssertIdentity(
        val selector: String,
    ) : FlowStep {
        override val key: String get() = "assert_identity"
    }

    /**
     * A journey whose pages depend on the site's state, e.g. signing in: the site may ask for an e-mail code, a phone
     * code or the login form, in any order and more than once. Repeatedly: wait until [until] or one of [pages] is
     * shown, then run that page's steps, until [until] is visible. Pages are recognised in list order, after [until].
     *
     * - [start] names the page to act on first without looking (a retry that goes straight to the login form);
     *   otherwise the journey waits for a known page first.
     * - A page asked for more than [maxVisits] times fails with its [JourneyPage.reason], so a site that keeps asking
     *   is reported instead of looping; a page that is still shown after its steps fails with [JourneyPage.stuck] when
     *   given (and is otherwise simply visited again).
     * - A page that is none of them is `registration_failed`: "Unexpected page while <label>".
     */
    data class Journey(
        /** What the journey does, as it reads in messages: `signing in`. */
        val label: String,
        val until: String,
        val pages: List<JourneyPage>,
        val start: String? = null,
        val maxVisits: Int = DEFAULT_MAX_VISITS,
    ) : FlowStep {
        override val key: String get() = "journey"

        companion object {
            /** A site may legitimately show the login page twice (see docs/PLAN.md), but not a third time. */
            const val DEFAULT_MAX_VISITS = 2
        }
    }
}

/** One page of a [FlowStep.Journey]: shown when [selector] is visible; handled by [steps]. */
data class JourneyPage(
    /** How the page reads in messages: `the e-mail code step`. */
    val label: String,
    val selector: String,
    val steps: List<FlowStep>,
    /** Reason when the site asks for this page more often than the journey allows. */
    val reason: FlowFailureReason = FlowFailureReason.REGISTRATION_FAILED,
    /** Failure when the page is still shown after its steps (a rejected login); null: visit it again. */
    val stuck: FlowFailure? = null,
)

/** Which kind of e-mail link an [FlowStep.EmailLink] waits for. */
enum class LinkPurpose(
    val key: String,
    /** Where the link is stored when the step does not say. */
    val defaultTarget: ValueTarget,
) {
    VERIFY("verify", ValueTarget.vars("verify_link")),
    INVITE("invite", ValueTarget.vars("invite_link")),
    ANY("any", ValueTarget.vars("email_link")),
    ;

    companion object {
        fun fromKey(key: String): LinkPurpose? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/**
 * How a failing flow step is reported instead of its default: [reason] as the report files it, and [message], a
 * template that may also use `{url}` (the page the tester is on). With [error] (a selector reference, e.g. a form's error
 * banner) the message reads `<message>: <that element's text>`, or `<message>: <what went wrong>` when it shows none.
 */
data class FlowFailure(
    val reason: FlowFailureReason? = null,
    val message: String? = null,
    val error: String? = null,
)

/** Failure reasons a flow may report; the keys are those of the agent's failure reasons and the report. */
enum class FlowFailureReason(
    val key: String,
) {
    REGISTRATION_FAILED("registration_failed"),
    LOGIN_FAILED("login_failed"),
    OTP_REJECTED("otp_rejected"),
    IDENTITY_MISMATCH("identity_mismatch"),
    MISSING_PREREQUISITE("missing_prerequisite"),
    ;

    companion object {
        fun fromKey(key: String): FlowFailureReason? = entries.firstOrNull { it.key == key.trim().lowercase() }

        val KEYS: String get() = entries.joinToString(", ") { it.key }
    }
}

/** Where a flow step stores a value: this tester's `{vars.<key>}` or the run's `{shared.<key>}`. */
data class ValueTarget(
    val scope: Scope,
    val key: String,
) {
    enum class Scope(
        val prefix: String,
    ) {
        VARS("vars"),
        SHARED("shared"),
    }

    override fun toString(): String = "${scope.prefix}.$key"

    companion object {
        /** Keys of stored values: lower-case letters, digits and `_`. */
        val KEY_PATTERN: Regex = Regex("[a-z_][a-z0-9_]*")

        fun vars(key: String) = ValueTarget(Scope.VARS, key)

        fun shared(key: String) = ValueTarget(Scope.SHARED, key)

        /** `vars.x`, `shared.x` or a bare `x` (= `vars.x`); null when it is none of these. */
        fun parse(text: String): ValueTarget? {
            val trimmed = text.trim()
            val scope = Scope.entries.firstOrNull { trimmed.startsWith(it.prefix + ".") }
            val key = if (scope == null) trimmed else trimmed.removePrefix(scope.prefix + ".")
            return if (KEY_PATTERN.matches(key)) ValueTarget(scope ?: Scope.VARS, key) else null
        }
    }
}

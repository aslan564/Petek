package az.petek.campaign.domain

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.time.Duration

/**
 * Checks the parts of a [TargetProfile] that describe the site's flows (see [Flow]), and the campaign's pacing:
 *
 * - `api_prefix` is empty or `/segment[/segment…]` without a trailing slash, so `{api}/x` stays one path;
 * - `local_storage` keys and `dismiss` selectors are not blank;
 * - flows have a known name ([FlowNames]) and steps; `verify_identity` contains an `assert_identity`;
 * - every selector reference is not blank, and one shaped like a key of a known group (`login.emial`) must be a key;
 * - `goto` is a path key, a `/path` on the target or a template; regular expressions compile; timeouts are positive
 *   and finite; journeys have pages with unique labels, a `start` among them and `max_visits` of at least 1;
 * - templates use only the flow placeholders ([Placeholder.FLOW_SELF_FIELDS], `{shared.<key>}`, `{vars.<key>}`,
 *   `{campaign.company}`, `{url}` in failure messages); `{self.password}` only in `fill` values, where the harness
 *   types it, never in anything that is shown, published or sent as a URL;
 * - `shared.invite_link` is read-only: it always means this tester's own invitation.
 *
 * Issues are reported through [report] with the YAML path they belong to (see [SourceLines]).
 */
internal class TargetProfileRules(
    private val target: TargetProfile,
    private val pacing: Pacing,
    private val report: (path: String, message: String) -> Unit,
) {
    private val selectorKeys: Set<String> = target.selectors.keys + TargetProfile.DEFAULT_SELECTORS.keys
    private val keyGroups: Set<String> = selectorKeys.mapNotNullTo(HashSet()) { it.substringBefore('.', "").ifEmpty { null } }

    fun check() {
        checkPacing()
        checkApiPrefix()
        target.localStorage.keys.filter { it.isBlank() }.forEach {
            report("$PROFILE.local_storage", "$PROFILE.local_storage has a blank key")
        }
        target.dismiss.forEachIndexed { index, ref -> checkSelector(ref, "$PROFILE.dismiss[$index]") }
        target.flows.forEach { (name, flow) -> checkFlow(name, flow) }
    }

    private fun checkPacing() {
        val stagger = pacing.startStagger
        if (stagger.isNegative() || !stagger.isFinite()) {
            report("campaign.pacing.start_stagger_ms", "campaign.pacing.start_stagger_ms must be zero or positive and finite, was $stagger")
        }
        pacing.maxParallelActors?.takeIf { it < 1 }?.let {
            report("campaign.pacing.max_parallel_actors", "campaign.pacing.max_parallel_actors must be at least 1, was $it")
        }
    }

    private fun checkApiPrefix() {
        if (!API_PREFIX.matches(target.apiPrefix)) {
            report(
                "$PROFILE.api_prefix",
                "$PROFILE.api_prefix must be empty or a path like /api/v1 (no trailing '/', no query), was '${target.apiPrefix}'",
            )
        }
    }

    private fun checkFlow(
        name: String,
        flow: Flow,
    ) {
        val path = "$PROFILE.flows.$name"
        if (name !in FlowNames.ALL) {
            report(path, "unknown flow '$name'; the run functions execute these flows: ${FlowNames.ALL.joinToString(", ")}")
        }
        if (flow.steps.isEmpty()) report(path, "flow '$name' has no steps")
        if (name == FlowNames.VERIFY_IDENTITY && flow.steps.none(::assertsIdentity)) {
            report(path, "flow '$name' must contain an assert_identity step: it is what proves whose session the browser holds")
        }
        checkSteps(flow.steps, path)
    }

    private fun assertsIdentity(step: FlowStep): Boolean =
        when (step) {
            is FlowStep.AssertIdentity -> true
            is FlowStep.IfVisible -> step.then.any(::assertsIdentity)
            is FlowStep.Journey -> step.pages.any { page -> page.steps.any(::assertsIdentity) }
            else -> false
        }

    private fun checkSteps(
        steps: List<FlowStep>,
        path: String,
    ) {
        steps.forEachIndexed { index, step -> checkStep(step, "$path[$index]") }
    }

    private fun checkStep(
        step: FlowStep,
        path: String,
    ) {
        val at = "$path.${step.key}"
        when (step) {
            is FlowStep.Goto -> checkGoto(step.path, at)
            is FlowStep.Fill -> checkSelectorAndText(step.selector, step.value, at, TextKind.TYPED)
            is FlowStep.Select -> checkSelectorAndText(step.selector, step.option, at, TextKind.PLAIN)
            is FlowStep.Check -> checkSelector(step.selector, at)
            is FlowStep.Click -> checkSelector(step.selector, at)
            is FlowStep.ClickIfVisible -> checkSelector(step.selector, at)
            is FlowStep.WaitFor -> checkWaitFor(step, at)
            is FlowStep.ExpectUrl -> checkExpectUrl(step, at)
            is FlowStep.EmailLink -> checkEmailLink(step, at)
            is FlowStep.EmailCode -> checkCodeStep(step.selector, step.submit, at)
            is FlowStep.PhoneCode -> checkCodeStep(step.selector, step.submit, at)
            is FlowStep.Read -> checkRead(step, at)
            is FlowStep.SetShared -> checkSetShared(step, at)
            is FlowStep.IfVisible -> checkIfVisible(step, at)
            is FlowStep.AssertIdentity -> checkSelector(step.selector, at)
            is FlowStep.Journey -> checkJourney(step, at)
            FlowStep.SaveSession, FlowStep.AccountCreated -> Unit
        }
    }

    private fun checkGoto(
        path: String,
        at: String,
    ) {
        checkTemplate(path, at, TextKind.PLAIN)
        val trimmed = path.trim()
        val literal = !target.isPathKey(trimmed) && !trimmed.startsWith("{")
        if (trimmed.isEmpty()) {
            report(at, "$at must not be blank")
        } else if (literal && (!trimmed.startsWith("/") || trimmed.startsWith("//") || trimmed.startsWith("/\\"))) {
            report(at, "$at must be a path key, a path on the target starting with a single '/' or a template, was '$path'")
        }
    }

    private fun checkSelectorAndText(
        selector: String,
        text: String,
        at: String,
        kind: TextKind,
    ) {
        checkSelector(selector, "$at.selector")
        checkTemplate(text, at, kind)
    }

    private fun checkWaitFor(
        step: FlowStep.WaitFor,
        at: String,
    ) {
        val given = listOf(step.selectors.isNotEmpty(), step.text != null).count { it }
        if (given != 1) report(at, "$at needs exactly one of selector, any or text")
        step.selectors.forEach { checkSelector(it, at) }
        step.text?.let {
            if (it.isBlank()) report(at, "$at text must not be blank")
            checkTemplate(it, at, TextKind.PLAIN)
        }
        checkTimeout(step.timeout, at)
        step.failure?.let { checkFailure(it, "$at.fail") }
    }

    private fun checkExpectUrl(
        step: FlowStep.ExpectUrl,
        at: String,
    ) {
        checkRegex(step.regex, at)
        checkTimeout(step.timeout, at)
        step.failure?.let { checkFailure(it, "$at.fail") }
    }

    private fun checkEmailLink(
        step: FlowStep.EmailLink,
        at: String,
    ) {
        step.pattern?.let { checkRegex(it, at) }
        checkStoreTarget(step.target, at)
    }

    private fun checkCodeStep(
        selector: String,
        submit: String?,
        at: String,
    ) {
        checkSelector(selector, at)
        submit?.let { checkSelector(it, "$at.submit") }
    }

    private fun checkRead(
        step: FlowStep.Read,
        at: String,
    ) {
        checkSelector(step.selector, at)
        step.regex?.let { checkRegex(it, at) }
        checkStoreTarget(step.into, at)
    }

    private fun checkSetShared(
        step: FlowStep.SetShared,
        at: String,
    ) {
        checkStoreTarget(ValueTarget.shared(step.sharedKey), at)
        if (!ValueTarget.KEY_PATTERN.matches(step.sharedKey)) {
            report(at, "$at key '${step.sharedKey}' must use only lower-case letters, digits and '_'")
        }
        checkTemplate(step.value, at, TextKind.PLAIN)
    }

    private fun checkIfVisible(
        step: FlowStep.IfVisible,
        at: String,
    ) {
        checkSelector(step.selector, at)
        checkTimeout(step.timeout, at)
        if (step.then.isEmpty()) report(at, "$at needs at least one step under then")
        checkSteps(step.then, "$at.then")
    }

    private fun checkJourney(
        step: FlowStep.Journey,
        at: String,
    ) {
        if (step.label.isBlank()) report(at, "$at label must not be blank")
        checkSelector(step.until, "$at.until")
        if (step.maxVisits < 1) report(at, "$at max_visits must be at least 1, was ${step.maxVisits}")
        if (step.pages.isEmpty()) report(at, "$at needs at least one page")
        val labels = mutableSetOf<String>()
        step.pages.forEachIndexed { index, page ->
            val pageAt = "$at.pages[$index]"
            if (page.label.isBlank()) report(pageAt, "$pageAt label must not be blank")
            if (!labels.add(page.label.trim())) report(pageAt, "$pageAt label '${page.label}' is used twice in this journey")
            checkSelector(page.selector, "$pageAt.when")
            if (page.steps.isEmpty()) report(pageAt, "$pageAt needs at least one step")
            checkSteps(page.steps, "$pageAt.steps")
            page.stuck?.let { checkFailure(it, "$pageAt.stuck") }
        }
        step.start?.let { start ->
            if (start.trim() !in
                labels
            ) {
                report(at, "$at start '$start' is not the label of one of its pages (${labels.joinToString(", ")})")
            }
        }
    }

    private fun checkFailure(
        failure: FlowFailure,
        at: String,
    ) {
        failure.message?.let {
            if (it.isBlank()) report(at, "$at message must not be blank")
            checkTemplate(it, at, TextKind.MESSAGE)
        }
        failure.error?.let { checkSelector(it, "$at.error") }
    }

    private fun checkStoreTarget(
        target: ValueTarget,
        at: String,
    ) {
        if (target == INVITE_LINK) {
            report(at, "$at cannot store into shared.invite_link: that placeholder always means this tester's own invitation")
        }
    }

    private fun checkSelector(
        ref: String,
        at: String,
    ) {
        if (ref.isBlank()) {
            report(at, "$at: selector must not be blank")
            return
        }
        checkTemplate(ref, at, TextKind.PLAIN)
        val keyShape = KEY_SHAPE.matchEntire(ref.trim()) ?: return
        if (keyShape.groupValues[1] in keyGroups && ref.trim() !in selectorKeys) {
            val group = keyShape.groupValues[1]
            val known = selectorKeys.filter { it.startsWith("$group.") }.sorted().joinToString(", ")
            report(at, "$at: '$ref' looks like a selector key, but there is no such key (keys of '$group': $known)")
        }
    }

    private fun checkRegex(
        regex: String,
        at: String,
    ) {
        try {
            Pattern.compile(regex)
        } catch (e: PatternSyntaxException) {
            report(at, "$at: '$regex' is not a valid regular expression (${e.description})")
        }
    }

    private fun checkTimeout(
        timeout: Duration?,
        at: String,
    ) {
        timeout ?: return
        if (!timeout.isPositive() || !timeout.isFinite()) report(at, "$at timeout_s must be positive and finite, was $timeout")
    }

    private fun checkTemplate(
        text: String,
        at: String,
        kind: TextKind,
    ) {
        PLACEHOLDER.findAll(text).map { it.groupValues[1] }.distinct().forEach { name ->
            placeholderProblem(name, kind)?.let { report(at, "$at: $it") }
        }
        LOOKALIKE
            .findAll(text)
            .map { it.value }
            .filterNot { Placeholder.NAME_PATTERN.matches(it.drop(1).dropLast(1)) }
            .forEach {
                report(
                    at,
                    "$at: '$it' looks like a placeholder but would stay literal; placeholder names use only lower-case " +
                        "letters, digits, '_' and '.', without spaces",
                )
            }
    }

    private fun placeholderProblem(
        name: String,
        kind: TextKind,
    ): String? {
        val namespace = name.substringBefore('.', "")
        val rest = name.substringAfter('.', "")
        return when {
            name == PASSWORD && kind != TextKind.TYPED -> {
                "{$name} may only be typed into a field (fill value): anywhere else it could be shown, published or sent"
            }

            namespace == "self" -> {
                "unknown placeholder {$name}; self fields: ${Placeholder.FLOW_SELF_FIELDS.joinToString(", ")}"
                    .takeIf { rest !in Placeholder.FLOW_SELF_FIELDS }
            }

            namespace == "shared" || namespace == "vars" -> {
                "placeholder {$name} needs a key of lower-case letters, digits and '_'".takeUnless { ValueTarget.KEY_PATTERN.matches(rest) }
            }

            name == CAMPAIGN_COMPANY -> {
                null
            }

            name == URL -> {
                "{url} is only available in failure messages".takeIf { kind != TextKind.MESSAGE }
            }

            else -> {
                "unknown placeholder {$name}; flows may use $FLOW_FORMS"
            }
        }
    }

    /** Where a template is used, which decides the placeholders it may contain. */
    private enum class TextKind {
        /** Typed into a field by the harness: the only place for `{self.password}`. */
        TYPED,

        /** Anything else the flow sends or shows: selectors, paths, options, shared values. */
        PLAIN,

        /** A failure message, which may also name the current page as `{url}`. */
        MESSAGE,
    }

    private companion object {
        const val PROFILE = "target_profile"
        const val PASSWORD = "self.password"
        const val CAMPAIGN_COMPANY = "campaign.company"
        const val URL = "url"
        const val FLOW_FORMS = "{self.<field>}, {shared.<key>}, {vars.<key>}, {campaign.company} (and {url} in failure messages)"
        val INVITE_LINK = ValueTarget.shared("invite_link")
        val API_PREFIX = Regex("""(/[A-Za-z0-9._~\-]+)*""")
        val PLACEHOLDER = Regex("\\{(" + Placeholder.NAME_PATTERN.pattern + ")}")
        val LOOKALIKE = Regex("""\{\s*[A-Za-z_][A-Za-z0-9_.\-]*\s*}""")

        /** `group.name`: the shape of a selector key such as `login.email` (a CSS `tag.class` rarely uses a key group). */
        val KEY_SHAPE = Regex("""([a-z_]+)\.([a-z0-9_]+)""")
    }
}

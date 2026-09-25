/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.campaign.domain

import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import java.net.URI
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.time.Duration

/**
 * Checks every rule listed on [CampaignValidator] and the following, reporting all issues at once (never just the first):
 *
 * - settings: `testers > 0`, role and registration quotas are non-negative and add up, `registration.invite` covers
 *   every manager (managers always join by invitation: a company-code sign-up makes an employee), departments are
 *   non-empty, unique and addressable by the actor grammar, names are unique, the budget is positive, the target is
 *   an absolute http(s) URL without credentials (messages mask them);
 * - actors: named departments exist and every expression can match at least one tester under the quotas (a manager
 *   is never a company-code joiner, and employees get the invitations left after the managers);
 * - steps: ids are unique, `do` is not blank, a step without `do`/`run` waits or asserts, `wait_for` names an event
 *   emitted by an earlier step, timeouts are positive and finite, `latency_max` follows a `visible_text` of the same
 *   step that waits for an event (t0), `only_one_succeeds` (once per step) needs a `do`/`run`, `parallel: true` and
 *   actors that can match two or more testers; its `request` names a mutating method (or `*`) and a regex that
 *   compiles, and its `oracle` (checked once for the group) uses no `{self.*}` placeholder;
 * - paths: oracle (also the `only_one_succeeds` oracle), `http_status` and `target_profile.paths` values are `/...`
 *   paths on the target, never other hosts;
 * - id sources: every `target_profile.id_sources` event is emitted by some step, `url_regex` compiles and has a group;
 * - templates use only [Placeholder.SUPPORTED_FORMS]. In `do`/`run` text and a step's own id source, `{last_id}` and
 *   `{event.<e>.id}` need an event emitted by an earlier step; assertions run after the step, so its own `emits` counts;
 * - flows, `local_storage`, `dismiss`, `api_prefix` and `campaign.pacing` follow [TargetProfileRules].
 *
 * Issue lines come from [Campaign.sourceLines], falling back to [ScenarioStep.line].
 */
class DefaultCampaignValidator(
    private val templates: TemplateRenderer = DefaultTemplateRenderer(),
) : CampaignValidator {
    override fun validate(
        campaign: Campaign,
        knownRunFunctions: Set<String>,
    ): List<ValidationIssue> = Rules(campaign, knownRunFunctions, templates).check()

    /** One validation pass; holds the issues found so far. */
    private class Rules(
        private val campaign: Campaign,
        private val knownRunFunctions: Set<String>,
        private val templates: TemplateRenderer,
    ) {
        private val settings = campaign.settings
        private val issues = mutableListOf<ValidationIssue>()
        private val emittedAnywhere: Set<String> = campaign.allSteps.mapNotNullTo(LinkedHashSet()) { it.emits?.event }

        /** How many steps emit each event; `wait_for` and `{last_id}` are unambiguous only when it is one. */
        private val emittingSteps: Map<String, Int> =
            campaign.allSteps
                .mapNotNull { it.emits?.event }
                .groupingBy { it }
                .eachCount()

        fun check(): List<ValidationIssue> {
            checkSettings()
            checkTargetProfile()
            checkSteps()
            return issues.toList()
        }

        private fun report(
            path: String,
            message: String,
            fallbackLine: Int? = null,
        ) {
            issues += ValidationIssue(campaign.sourceLines.lineOf(path) ?: fallbackLine, message)
        }

        // ---- settings ----

        private fun checkSettings() {
            if (settings.name.isBlank()) report("campaign.name", "campaign.name must not be blank")
            checkTarget(settings.target)
            if (settings.testers <= 0) report("campaign.testers", "campaign.testers must be positive, was ${settings.testers}")
            checkRoles()
            checkRegistration()
            checkDepartments()
            checkNames()
            if (settings.budget.maxStepsPerAgent <= 0) {
                report(
                    "campaign.budget.max_steps_per_agent",
                    "campaign.budget.max_steps_per_agent must be positive, was ${settings.budget.maxStepsPerAgent}",
                )
            }
            if (settings.budget.maxMinutes <= 0) {
                report("campaign.budget.max_minutes", "campaign.budget.max_minutes must be positive, was ${settings.budget.maxMinutes}")
            }
        }

        private fun checkTarget(target: URI) {
            val scheme = target.scheme?.lowercase()
            if (!target.isAbsolute || scheme !in WEB_SCHEMES || target.host.isNullOrBlank()) {
                report("campaign.target", "campaign.target must be an absolute http(s) URL with a host, was '${redacted(target)}'")
            }
            if (target.rawAuthority?.contains('@') == true) {
                report(
                    "campaign.target",
                    "campaign.target must not contain credentials ('user:password@'); the target is copied into run records " +
                        "and reports, was '${redacted(target)}'",
                )
            }
        }

        private fun checkRoles() {
            val roles = settings.roles
            Role.entries.filter { roles.count(it) < 0 }.forEach {
                report("campaign.roles.${it.key}", "campaign.roles.${it.key} must not be negative, was ${roles.count(it)}")
            }
            if (settings.testers > 0 && roles.total != settings.testers) {
                report(
                    "campaign.roles",
                    "roles add up to ${roles.total} (admin ${roles.admin} + manager ${roles.manager} + " +
                        "employee ${roles.employee}) but campaign.testers is ${settings.testers}",
                )
            }
        }

        private fun checkRegistration() {
            val registration = settings.registration
            if (registration.invite < 0) {
                report("campaign.registration.invite", "campaign.registration.invite must not be negative, was ${registration.invite}")
            }
            if (registration.companyCode < 0) {
                report(
                    "campaign.registration.company_code",
                    "campaign.registration.company_code must not be negative, was ${registration.companyCode}",
                )
            }
            val nonAdmins = settings.roles.manager + settings.roles.employee
            val joining = registration.invite + registration.companyCode
            if (joining != nonAdmins) {
                report(
                    "campaign.registration",
                    "registration adds up to $joining (invite ${registration.invite} + company_code ${registration.companyCode}) " +
                        "but there are $nonAdmins non-admin testers (manager ${settings.roles.manager} + " +
                        "employee ${settings.roles.employee})",
                )
            }
            val managers = settings.roles.manager
            if (managers >= 0 && registration.invite in 0 until managers) {
                report(
                    "campaign.registration.invite",
                    "campaign.registration.invite is ${registration.invite} but must be at least roles.manager ($managers): " +
                        "managers always join by invitation, because a company-code sign-up becomes an employee on the target",
                )
            }
        }

        private fun checkDepartments() {
            if (settings.departments.isEmpty()) {
                report("campaign.departments", "campaign.departments must list at least one department")
            }
            val seen = mutableMapOf<String, String>()
            settings.departments.forEachIndexed { index, department ->
                val path = "campaign.departments[$index]"
                when {
                    department.isBlank() -> {
                        report(path, "campaign.departments[$index] is blank")
                    }

                    department.any { it in DefaultActorExpressionParser.RESERVED_CHARS } -> {
                        report(
                            path,
                            "department '$department' contains one of ${DefaultActorExpressionParser.RESERVED_CHARS}, " +
                                "so actor expressions cannot name it",
                        )
                    }

                    else -> {
                        seen.put(department.trim().lowercase(), department)?.let { first ->
                            report(path, "department '$department' is listed twice (as '$first' before)")
                        }
                    }
                }
            }
        }

        private fun checkNames() {
            val seen = mutableSetOf<String>()
            settings.names.forEachIndexed { index, name ->
                val path = "campaign.names[$index]"
                when {
                    name.isBlank() -> report(path, "campaign.names[$index] is blank")
                    !seen.add(name.trim().lowercase()) -> report(path, "name '$name' is listed twice in campaign.names")
                }
            }
        }

        // ---- target profile ----

        private fun checkTargetProfile() {
            val anyStep = EventScope(emittedAnywhere, "any step")
            campaign.target.paths.forEach { (key, value) ->
                val path = "target_profile.paths.$key"
                relativePathProblem(value)?.let { report(path, "$path $it") }
                checkTemplate(value, path, path, anyStep, fallbackLine = null)
            }
            campaign.target.selectors.forEach { (key, value) ->
                val path = "target_profile.selectors.$key"
                if (value.isBlank()) report(path, "$path must not be blank")
                checkTemplate(value, path, path, anyStep, fallbackLine = null)
            }
            campaign.target.idSources.forEach { (event, source) ->
                val path = "target_profile.id_sources.$event"
                if (event !in emittedAnywhere) report(path, "$path: no step emits '$event'")
                checkIdSource(source, path, context = path, anyStep, fallbackLine = null)
            }
            TargetProfileRules(campaign.target, settings.pacing) { path, message -> report(path, message) }.check()
        }

        private fun checkIdSource(
            source: IdSource,
            path: String,
            context: String,
            scope: EventScope,
            fallbackLine: Int?,
        ) {
            when (source) {
                is IdSource.UrlRegex -> {
                    checkUrlRegex(source.regex, path, context, fallbackLine)
                }

                is IdSource.OracleField -> {
                    relativePathProblem(source.path)?.let { report(path, "$context: oracle path $it", fallbackLine) }
                    if (source.field.isBlank()) report(path, "$context: oracle field must not be blank", fallbackLine)
                    checkTemplate(source.path, path, context, scope, fallbackLine)
                }

                is IdSource.DomAttribute -> {
                    if (source.selector.isBlank()) report(path, "$context: dom selector must not be blank", fallbackLine)
                    if (source.attribute.isBlank()) report(path, "$context: dom attribute must not be blank", fallbackLine)
                    checkTemplate(source.selector, path, context, scope, fallbackLine)
                }

                IdSource.AgentReport -> {
                    return
                }
            }
        }

        private fun checkUrlRegex(
            regex: String,
            path: String,
            context: String,
            fallbackLine: Int?,
        ) {
            val groups =
                try {
                    Pattern.compile(regex).matcher("").groupCount()
                } catch (e: PatternSyntaxException) {
                    report(path, "$context: url_regex '$regex' is not a valid regular expression (${e.description})", fallbackLine)
                    return
                }
            if (groups < 1) report(path, "$context: url_regex '$regex' needs a capture group for the id", fallbackLine)
        }

        // ---- steps ----

        private fun checkSteps() {
            if (campaign.allSteps.isEmpty()) report("steps", "the campaign has no setup or steps")
            val emittedBefore = LinkedHashSet<String>()
            val firstLineOfId = mutableMapOf<String, Int?>()
            val located =
                campaign.setup.mapIndexed { i, step -> "setup[$i]" to step } +
                    campaign.steps.mapIndexed { i, step -> "steps[$i]" to step }
            located.forEach { (path, step) ->
                StepRules(path, step, emittedBefore.toSet()).check(firstLineOfId)
                step.emits?.let { emittedBefore += it.event }
            }
        }

        private inner class StepRules(
            private val path: String,
            private val step: ScenarioStep,
            emittedBefore: Set<String>,
        ) {
            private val name = "step '${step.id}'"
            private val beforeScope = EventScope(emittedBefore, "an earlier step")
            private val throughScope =
                EventScope(emittedBefore + listOfNotNull(step.emits?.event), "this or an earlier step")

            fun check(firstLineOfId: MutableMap<String, Int?>) {
                checkId(firstLineOfId)
                checkActors()
                checkAction()
                checkEmits()
                checkWaitFor()
                step.assertions.forEachIndexed { index, assertion -> checkAssertion(index, assertion) }
            }

            private fun report(
                subPath: String,
                message: String,
            ) = this@Rules.report(if (subPath.isEmpty()) path else "$path.$subPath", message, step.line)

            private fun checkId(firstLineOfId: MutableMap<String, Int?>) {
                val line = campaign.sourceLines.lineOf("$path.id") ?: step.line
                when {
                    step.id.isBlank() -> {
                        report("id", "step id must not be blank")
                    }

                    step.id in firstLineOfId -> {
                        val first = firstLineOfId[step.id]?.let { " (first used at line $it)" } ?: ""
                        report("id", "step id '${step.id}' is used twice$first")
                    }

                    else -> {
                        firstLineOfId[step.id] = line
                    }
                }
            }

            private fun checkActors() {
                val expression = step.actors
                if (expression.selectors.isEmpty()) {
                    report("actor", "$name: the actor expression is empty")
                    return
                }
                val unknown =
                    expression.selectors
                        .mapNotNull { it.department }
                        .filter { it !in settings.departments }
                        .distinct()
                unknown.forEach { department ->
                    report(
                        "actor",
                        "$name: actor '${expression.raw}' names department '$department', which is not in campaign.departments " +
                            "(${settings.departments.joinToString(", ")})${spellingHint(department)}",
                    )
                }
                if (unknown.isEmpty() && maxMatches(expression) == 0) {
                    report("actor", "$name: actor '${expression.raw}' can never match a tester with the campaign's role quotas")
                }
            }

            /** Departments match exactly; a case-only difference is almost always a typo worth pointing out. */
            private fun spellingHint(department: String): String =
                settings.departments
                    .firstOrNull { it.equals(department, ignoreCase = true) }
                    ?.let { " (did you mean '$it'?)" }
                    .orEmpty()

            private fun checkAction() {
                when (val action = step.action) {
                    is StepAction.Do -> {
                        if (action.instruction.isBlank()) report("do", "$name: do must not be blank")
                        checkTemplate(action.instruction, "$path.do", name, beforeScope, step.line)
                    }

                    is StepAction.Run -> {
                        checkRun(action)
                    }

                    StepAction.None -> {
                        if (step.waitFor == null && step.assertions.isEmpty()) {
                            report("", "$name has neither do nor run, so it must wait_for an event or assert something")
                        }
                    }
                }
            }

            private fun checkRun(action: StepAction.Run) {
                when {
                    action.function.isBlank() -> {
                        report("run", "$name: run needs a function name")
                    }

                    action.function !in knownRunFunctions -> {
                        report(
                            "run",
                            "$name: unknown run function '${action.function}' " +
                                "(known: ${knownRunFunctions.sorted().joinToString(", ").ifEmpty { "none" }})",
                        )
                    }
                }
                action.args.forEach { (key, value) ->
                    checkTemplate(value, "$path.run.args.$key", "$name, run argument '$key'", beforeScope, step.line)
                }
                if (action.function in ADMIN_ONLY_RUN_FUNCTIONS && step.actors.selectors.any { it.role != Role.ADMIN }) {
                    report(
                        "actor",
                        "$name: run ${action.function} may only be performed by the admin (the company owner), " +
                            "but actor '${step.actors.raw}' includes other roles",
                    )
                }
            }

            private fun checkEmits() {
                val emits = step.emits ?: return
                if (emits.event.isBlank()) report("emits", "$name: emits needs an event name")
                if ((emittingSteps[emits.event] ?: 0) > 1) {
                    report(
                        "emits",
                        "$name: event '${emits.event}' is emitted by more than one step; give every event exactly one emitting " +
                            "step so wait_for and {last_id} cannot pick up an older step's object",
                    )
                }
                emits.idSource?.let {
                    checkIdSource(it, "$path.emits.id_from", "$name, emits.id_from", beforeScope, step.line)
                }
            }

            private fun checkWaitFor() {
                val waitFor = step.waitFor ?: return
                if (waitFor.event !in beforeScope.events) {
                    val reason = if (waitFor.event in emittedAnywhere) "is emitted only by this or a later step" else "no step emits"
                    report("wait_for", "$name waits for '${waitFor.event}', which $reason")
                }
                durationProblem(waitFor.timeout)?.let { report("wait_for", "$name: wait_for timeout $it") }
            }

            private fun checkAssertion(
                index: Int,
                assertion: AssertionSpec,
            ) {
                val sub = "assert[$index]"
                val context = "$name, ${assertion.type}"
                val problems = assertionProblems(index, assertion)
                problems.forEach { report(sub, "$context: $it") }
                assertionTemplates(assertion).forEach { checkTemplate(it, "$path.$sub", context, throughScope, step.line) }
            }

            private fun assertionProblems(
                index: Int,
                assertion: AssertionSpec,
            ): List<String> =
                when (assertion) {
                    is AssertionSpec.VisibleText -> {
                        listOfNotNull(
                            "text must not be blank".takeIf { assertion.text.isBlank() },
                            durationProblem(assertion.within)?.let { "within_s $it" },
                        )
                    }

                    is AssertionSpec.NotVisible -> {
                        val given = listOfNotNull(assertion.text, assertion.selector).count { it.isNotBlank() }
                        listOfNotNull("needs exactly one of text or selector".takeIf { given != 1 })
                    }

                    is AssertionSpec.Oracle -> {
                        listOfNotNull(relativePathProblem(assertion.path)?.let { "path $it" })
                    }

                    is AssertionSpec.HttpStatus -> {
                        listOfNotNull(
                            relativePathProblem(assertion.path)?.let { "path $it" },
                            "method '${assertion.method}' is not one of $HTTP_METHODS".takeIf { assertion.method !in HTTP_METHODS },
                            "equals must be an HTTP status (100-599), was ${assertion.equals}".takeIf { assertion.equals !in 100..599 },
                        )
                    }

                    is AssertionSpec.Count -> {
                        listOfNotNull(
                            "selector must not be blank".takeIf { assertion.selector.isBlank() },
                            "equals must not be negative, was ${assertion.equals}".takeIf { assertion.equals < 0 },
                        )
                    }

                    is AssertionSpec.LatencyMax -> {
                        listOfNotNull(
                            durationProblem(assertion.max)?.let { "ms $it" },
                            "must come after a visible_text assertion of the same step, which measures the latency"
                                .takeIf { step.assertions.take(index).none { it is AssertionSpec.VisibleText } },
                            "needs the step to wait_for an event, because latency is measured from the time that event was emitted"
                                .takeIf { step.waitFor == null },
                        )
                    }

                    is AssertionSpec.OnlyOneSucceeds -> {
                        listOfNotNull(
                            "needs parallel: true so the actors start at the same instant".takeUnless { step.parallel },
                            "needs a do or run whose outcomes are compared".takeIf { step.action == StepAction.None },
                            maxMatches(step.actors).takeIf { it < 2 }?.let {
                                "needs actors that can match at least 2 testers, but '${step.actors.raw}' matches at most $it"
                            },
                            "may appear only once per step".takeIf {
                                step.assertions.take(index).any { it is AssertionSpec.OnlyOneSucceeds }
                            },
                        ) + requestProblems(assertion.request) + raceOracleProblems(assertion.oracle)
                    }
                }

            private fun requestProblems(request: RequestPattern?): List<String> {
                request ?: return emptyList()
                val method =
                    request.method?.takeIf { it !in RequestPattern.MUTATING_METHODS }?.let {
                        "request method '$it' is not one of ${RequestPattern.MUTATING_METHODS.joinToString(", ")} " +
                            "(or ${RequestPattern.ANY_METHOD} for any of them): only requests that change something decide a race"
                    }
                val regex =
                    try {
                        Pattern.compile(request.pathRegex)
                        null
                    } catch (e: PatternSyntaxException) {
                        "request path '${request.pathRegex}' is not a valid regular expression (${e.description})"
                    }
                return listOfNotNull(method, regex)
            }

            private fun raceOracleProblems(oracle: OracleCondition?): List<String> {
                oracle ?: return emptyList()
                // Checked once for the whole group, after every racer: there is no actor for {self.*} to refer to.
                val actorBound =
                    listOfNotNull(oracle.path, oracle.equals)
                        .flatMap { templates.placeholders(it) }
                        .distinct()
                        // Other self fields are refused for every template already.
                        .filter { (Placeholder.parse(it) as? Placeholder.Self)?.field in Placeholder.CAMPAIGN_SELF_FIELDS }
                        .map { "oracle is checked once for all actors, so {$it} has no actor to refer to" }
                return listOfNotNull(
                    relativePathProblem(oracle.path)?.let { "oracle path $it" },
                    "oracle field must not be blank".takeIf { oracle.field?.isBlank() == true },
                ) + actorBound
            }

            private fun assertionTemplates(assertion: AssertionSpec): List<String> =
                when (assertion) {
                    is AssertionSpec.VisibleText -> listOf(assertion.text)
                    is AssertionSpec.NotVisible -> listOfNotNull(assertion.text, assertion.selector)
                    is AssertionSpec.Oracle -> listOfNotNull(assertion.path, assertion.equals, assertion.contains)
                    is AssertionSpec.HttpStatus -> listOf(assertion.path)
                    is AssertionSpec.Count -> listOf(assertion.selector)
                    is AssertionSpec.LatencyMax -> emptyList()
                    is AssertionSpec.OnlyOneSucceeds -> listOfNotNull(assertion.oracle?.path, assertion.oracle?.equals)
                }
        }

        // ---- templates ----

        private fun checkTemplate(
            text: String,
            path: String,
            context: String,
            scope: EventScope,
            fallbackLine: Int?,
        ) {
            templates.placeholders(text).forEach { name ->
                placeholderProblem(name, scope)?.let { report(path, "$context: $it", fallbackLine) }
            }
            LOOKALIKE.findAll(text).map { it.value }.filterNot { Placeholder.NAME_PATTERN.matches(it.drop(1).dropLast(1)) }.forEach {
                report(
                    path,
                    "$context: '$it' looks like a placeholder but would stay literal; placeholder names use only " +
                        "lower-case letters, digits, '_' and '.', without spaces",
                    fallbackLine,
                )
            }
        }

        private fun placeholderProblem(
            name: String,
            scope: EventScope,
        ): String? =
            when (val placeholder = Placeholder.parse(name)) {
                null -> {
                    if ("{$name}" == Placeholder.API_PREFIX) {
                        "{$name} stands for target_profile.api_prefix and is replaced when the campaign file is loaded; " +
                            "a campaign built in code writes the prefix itself"
                    } else {
                        "unknown placeholder {$name}; allowed: ${Placeholder.SUPPORTED_FORMS}"
                    }
                }

                is Placeholder.Self -> {
                    "placeholder {$name} is not available to campaign files; allowed: ${Placeholder.SUPPORTED_FORMS}"
                        .takeIf { placeholder.field !in Placeholder.CAMPAIGN_SELF_FIELDS }
                }

                Placeholder.LastId -> {
                    "{$name} needs an event emitted by ${scope.description}".takeIf { scope.events.isEmpty() }
                }

                is Placeholder.EventId -> {
                    when (placeholder.event) {
                        in scope.events -> null
                        in emittedAnywhere -> "{$name} refers to '${placeholder.event}', which is not emitted by ${scope.description}"
                        else -> "{$name} refers to '${placeholder.event}', which no step emits"
                    }
                }
            }

        // ---- actor bounds ----

        /** Upper bound of distinct testers [expression] can match under the quotas (identities are not known yet). */
        private fun maxMatches(expression: ActorExpression): Int =
            expression.selectors
                .distinct()
                .groupBy { it.role }
                .entries
                .sumOf { (role, selectors) -> minOf(settings.roles.count(role).coerceAtLeast(0), selectors.sumOf(::maxSelectorMatches)) }

        private fun maxSelectorMatches(selector: ActorSelector): Int {
            val role = selector.role
            if (selector.department != null && (role == Role.ADMIN || selector.department !in settings.departments)) return 0
            var bound = settings.roles.count(role).coerceAtLeast(0)
            selector.registration?.let { mode -> bound = minOf(bound, joinersOf(role, mode)) }
            return selector.nth?.let { if (it <= bound) 1 else 0 } ?: bound
        }

        /**
         * Upper bound of testers of [role] joining by [mode]: the admin owns the company, managers are always invited
         * and employees get the invitations left after the managers plus every company code.
         */
        private fun joinersOf(
            role: Role,
            mode: RegistrationMode,
        ): Int {
            val managers = settings.roles.manager.coerceAtLeast(0)
            val registration = settings.registration
            val bound =
                when (role) {
                    Role.ADMIN -> {
                        0
                    }

                    Role.MANAGER -> {
                        if (mode == RegistrationMode.INVITE) managers else 0
                    }

                    Role.EMPLOYEE -> {
                        when (mode) {
                            RegistrationMode.INVITE -> registration.invite - managers
                            RegistrationMode.COMPANY_CODE -> registration.companyCode
                            RegistrationMode.OWNER -> 0
                        }
                    }
                }
            return bound.coerceAtLeast(0)
        }
    }

    /** Events a template may refer to at its position, and how to describe that position in messages. */
    private data class EventScope(
        val events: Set<String>,
        val description: String,
    )

    private companion object {
        val WEB_SCHEMES = setOf("http", "https")

        /** Run functions that create or seed the company: only the owner may perform them (isolation, rule 7). */
        val ADMIN_ONLY_RUN_FUNCTIONS = setOf("register_owner", "seed_company")
        val HTTP_METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

        /**
         * Brace text shaped like a placeholder that the renderer would leave literal: `{Self.Email}`,
         * `{event.ticket-created.id}`, `{ last_id }`. Such a typo would otherwise reach a URL or prompt unreplaced.
         */
        val LOOKALIKE = Regex("""\{\s*[A-Za-z_][A-Za-z0-9_.\-]*\s*}""")

        /** Timeouts must end: an infinite one (`timeout_s: 1e300` overflows to it) would hang the run instead of failing the step. */
        fun durationProblem(duration: Duration): String? =
            when {
                !duration.isPositive() -> "must be positive, was $duration"
                !duration.isFinite() -> "must be finite, was $duration"
                else -> null
            }

        /**
         * Oracle, HTTP and page paths are resolved against the target. An absolute or scheme-relative URL would send
         * the test token, the agent's cookies or typed passwords to another host, so only `/...` paths are accepted.
         */
        fun relativePathProblem(path: String): String? =
            when {
                path.isBlank() -> {
                    "must not be blank"
                }

                !path.startsWith("/") || path.startsWith("//") || path.startsWith("/\\") -> {
                    "must be a path on the target starting with a single '/', was '$path'"
                }

                else -> {
                    null
                }
            }

        /** The target as shown in messages: credentials in the authority are masked. */
        fun redacted(target: URI): String {
            val authority = target.rawAuthority ?: return target.toString()
            val at = authority.lastIndexOf('@')
            return if (at < 0) target.toString() else target.toString().replaceFirst(authority, "***@" + authority.substring(at + 1))
        }
    }
}

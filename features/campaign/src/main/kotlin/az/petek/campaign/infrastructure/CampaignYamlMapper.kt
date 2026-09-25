/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.campaign.infrastructure

import az.petek.campaign.domain.ActorExpression
import az.petek.campaign.domain.ActorExpressionParser
import az.petek.campaign.domain.AssertionSpec
import az.petek.campaign.domain.Budget
import az.petek.campaign.domain.Campaign
import az.petek.campaign.domain.CampaignSettings
import az.petek.campaign.domain.CampaignValidationException
import az.petek.campaign.domain.EmitSpec
import az.petek.campaign.domain.IdSource
import az.petek.campaign.domain.OnFail
import az.petek.campaign.domain.OracleCondition
import az.petek.campaign.domain.Pacing
import az.petek.campaign.domain.RegistrationQuota
import az.petek.campaign.domain.RequestPattern
import az.petek.campaign.domain.RoleQuota
import az.petek.campaign.domain.ScenarioStep
import az.petek.campaign.domain.SourceLines
import az.petek.campaign.domain.StepAction
import az.petek.campaign.domain.StepPhase
import az.petek.campaign.domain.TargetProfile
import az.petek.campaign.domain.WaitForSpec
import az.petek.campaign.domain.expandApiPrefix
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Maps a parsed campaign YAML tree onto the domain model, following the schema of `scenarios/kadrohr.yaml` and
 * docs/PLAN.md "Ssenari formatı". Structural problems (unknown keys, wrong types, missing keys) are collected with
 * lines and thrown together as one [CampaignValidationException]; cross-field rules are left to the validator.
 * `{api}` is replaced by `target_profile.api_prefix` once the whole file is mapped (see [expandApiPrefix]).
 */
internal class CampaignYamlMapper(
    private val defaultName: String,
    private val targetOverride: URI?,
    private val actorParser: ActorExpressionParser,
) {
    fun map(
        root: YamlNode,
        sourceHash: String,
    ): Campaign {
        val lines = collectSourceLines(root)
        val reader = YamlReader(lines)
        val campaign = Reading(reader).campaign(root, sourceHash)
        val issues = reader.issues
        if (issues.isNotEmpty()) throw CampaignValidationException(issues)
        // Every path that yields no campaign records a problem first; reaching this without one is a mapper bug.
        return checkNotNull(campaign) { "campaign mapping produced neither a campaign nor an issue" }
            .copy(sourceLines = lines)
            .expandApiPrefix()
    }

    /** One mapping pass over one file. */
    private inner class Reading(
        private val reader: YamlReader,
    ) {
        fun campaign(
            root: YamlNode,
            sourceHash: String,
        ): Campaign? {
            if (root.plain() == null) return reader.problem(SourceLines.ROOT, "the campaign file is empty")
            val top = reader.map(root, SourceLines.ROOT, ROOT_KEYS) ?: return null
            val settings = settings(top)
            val target = top.map("target_profile", TARGET_PROFILE_KEYS)?.let(::targetProfile) ?: TargetProfile.DEFAULT
            val setup = steps(top, "setup", StepPhase.SETUP)
            val steps = steps(top, "steps", StepPhase.MAIN)
            return Campaign(settings ?: return null, target, setup, steps, sourceHash)
        }

        // ---- campaign ----

        private fun settings(top: YamlFields): CampaignSettings? {
            val fields = top.map("campaign", SETTINGS_KEYS, required = true) ?: return null
            val target = target(fields)
            val testers = fields.int("testers", required = true)
            val seed = fields.long("seed", required = true)
            val names = fields.textList("names") ?: emptyList()
            val roles = roles(fields)
            val departments = fields.textList("departments", required = true)
            val registration = registration(fields, roles)
            val budget = budget(fields)
            val onFail = onFail(fields) ?: OnFail.CONTINUE
            val name = fields.text("name") ?: defaultName
            val pacing = fields.map("pacing", PACING_KEYS)?.let(::pacing) ?: Pacing.NONE
            return CampaignSettings(
                target = target ?: return null,
                testers = testers ?: return null,
                seed = seed ?: return null,
                names = names,
                roles = roles ?: return null,
                departments = departments ?: return null,
                registration = registration ?: return null,
                budget = budget ?: return null,
                onFail = onFail,
                name = name,
                pacing = pacing,
            )
        }

        private fun pacing(fields: YamlFields): Pacing =
            Pacing(
                startStagger = fields.long("start_stagger_ms", required = false)?.milliseconds ?: Pacing.NONE.startStagger,
                maxParallelActors = fields.int("max_parallel_actors", required = false),
            )

        private fun target(fields: YamlFields): URI? {
            val path = fields.pathOf("target")
            val fromFile =
                fields.text("target")?.let { raw ->
                    try {
                        URI(raw.trim())
                    } catch (e: URISyntaxException) {
                        reader.problem(path, "'$path' is not a valid URL: ${e.reason}")
                    }
                }
            if (targetOverride == null && fromFile == null && !fields.has("target")) {
                reader.problem(fields.path, "missing required key 'target' in 'campaign' (or pass a target override)")
            }
            return targetOverride ?: fromFile
        }

        private fun roles(fields: YamlFields): RoleQuota? {
            val roles = fields.map("roles", ROLE_KEYS, required = true) ?: return null
            val admin = roles.int("admin", required = true)
            val manager = roles.int("manager", required = true)
            val employee = roles.int("employee", required = true)
            return RoleQuota(admin ?: return null, manager ?: return null, employee ?: return null)
        }

        /**
         * Omitted: non-admins split evenly, the invitation side taking the odd one, but every manager is invited
         * (managers always join by invitation, see [RegistrationQuota]).
         */
        private fun registration(
            fields: YamlFields,
            roles: RoleQuota?,
        ): RegistrationQuota? {
            if (!fields.has("registration")) {
                roles ?: return null
                val nonAdmins = roles.manager + roles.employee
                val invite = maxOf(roles.manager, (nonAdmins + 1) / 2)
                return RegistrationQuota(invite = invite, companyCode = nonAdmins - invite)
            }
            val registration = fields.map("registration", REGISTRATION_KEYS) ?: return null
            val invite = registration.int("invite", required = true)
            val companyCode = registration.int("company_code", required = true)
            return RegistrationQuota(invite ?: return null, companyCode ?: return null)
        }

        private fun budget(fields: YamlFields): Budget? {
            val budget = fields.map("budget", BUDGET_KEYS, required = true) ?: return null
            val maxSteps = budget.int("max_steps_per_agent", required = true)
            val maxMinutes = budget.int("max_minutes", required = true)
            return Budget(maxSteps ?: return null, maxMinutes ?: return null)
        }

        private fun onFail(fields: YamlFields): OnFail? {
            val raw = fields.text("on_fail") ?: return null
            return OnFail.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: reader.problem(fields.pathOf("on_fail"), "'${fields.pathOf("on_fail")}' must be continue or abort, was '$raw'")
        }

        // ---- target profile ----

        private fun targetProfile(fields: YamlFields): TargetProfile =
            TargetProfile(
                paths = textMap(fields, "paths"),
                selectors = textMap(fields, "selectors"),
                idSources = idSources(fields),
                flows = TargetProfile.DEFAULT_FLOWS + FlowYamlReading(reader).flows(fields["flows"], fields.pathOf("flows")),
                localStorage = textMap(fields, "local_storage"),
                dismiss = fields.textList("dismiss").orEmpty(),
                apiPrefix = fields.text("api_prefix")?.trim() ?: TargetProfile.DEFAULT_API_PREFIX,
            )

        private fun textMap(
            fields: YamlFields,
            key: String,
        ): Map<String, String> {
            val node = fields[key] ?: return emptyMap()
            val path = fields.pathOf(key)
            if (node !is YamlMap) return reader.problem(path, "'$path' must be a map of names to values") ?: emptyMap()
            return node.entries.entries
                .mapNotNull { (name, value) ->
                    val valuePath = childPath(path, name.content)
                    val plain = value.plain()
                    val text = if (plain == null) reader.problem(valuePath, "'$valuePath' is empty") else reader.text(plain, valuePath)
                    text?.let { name.content to it }
                }.toMap()
        }

        private fun idSources(fields: YamlFields): Map<String, IdSource> {
            val node = fields["id_sources"] ?: return emptyMap()
            val path = fields.pathOf("id_sources")
            if (node !is YamlMap) return reader.problem(path, "'$path' must be a map of event names to id sources") ?: emptyMap()
            return node.entries.entries
                .mapNotNull { (event, value) ->
                    idSource(value.plain(), childPath(path, event.content))?.let { event.content to it }
                }.toMap()
        }

        private fun idSource(
            node: YamlNode?,
            path: String,
        ): IdSource? {
            if (node == null) return reader.problem(path, "'$path' needs an id source (${ID_SOURCE_KEYS.joinToString(", ")})")
            val fields = reader.map(node, path, ID_SOURCE_KEYS) ?: return null
            val kinds = fields.keys.filter { it in ID_SOURCE_KEYS }
            if (kinds.size != 1) {
                return reader.problem(path, "'$path' needs exactly one of ${ID_SOURCE_KEYS.joinToString(", ")}, found ${kinds.size}")
            }
            return when (val kind = kinds.single()) {
                "url_regex" -> {
                    fields.text(kind, required = true)?.let { IdSource.UrlRegex(it) }
                }

                "oracle" -> {
                    fields.map(kind, ORACLE_ID_KEYS, required = true)?.let { oracle ->
                        val oraclePath = oracle.text("path", required = true)
                        val field = oracle.text("field", required = true)
                        if (oraclePath != null && field != null) IdSource.OracleField(oraclePath, field) else null
                    }
                }

                "dom" -> {
                    fields.map(kind, DOM_ID_KEYS, required = true)?.let { dom ->
                        val selector = dom.text("selector", required = true)
                        val attribute = dom.text("attribute", required = true)
                        if (selector != null && attribute != null) IdSource.DomAttribute(selector, attribute) else null
                    }
                }

                else -> {
                    agentReport(fields, kind)
                }
            }
        }

        /** `agent: true` is the only accepted form; it marks the id as reported by the agent itself. */
        private fun agentReport(
            fields: YamlFields,
            key: String,
        ): IdSource? {
            val path = fields.pathOf(key)
            return when (fields.bool(key)) {
                true -> IdSource.AgentReport
                false -> reader.problem(path, "'$path' can only be true")
                null -> if (fields.has(key)) null else reader.problem(path, "'$path' needs the value true")
            }
        }

        // ---- steps ----

        private fun steps(
            top: YamlFields,
            key: String,
            phase: StepPhase,
        ): List<ScenarioStep> {
            val items = reader.list(top[key], top.pathOf(key)) ?: return emptyList()
            return items.mapIndexedNotNull { index, item ->
                if (item.node == null) {
                    reader.problem(item.path, "'${item.path}' is an empty step")
                } else {
                    step(item, phase, index + 1)
                }
            }
        }

        private fun step(
            item: YamlItem,
            phase: StepPhase,
            ordinal: Int,
        ): ScenarioStep? {
            val fields = reader.map(item.node, item.path, STEP_KEYS) ?: return null
            val id = fields.text("id") ?: "${if (phase == StepPhase.SETUP) "setup" else "step"}-$ordinal"
            val actors = actors(fields)
            val action = action(fields)
            val emits = fields.valued("emits")?.let { emits(it, fields.pathOf("emits")) }
            val waitFor = fields.valued("wait_for")?.let { waitFor(it, fields.pathOf("wait_for")) }
            val parallel = fields.bool("parallel") ?: false
            val onFail = onFail(fields)
            val assertions = assertions(fields)
            return ScenarioStep(
                id = id,
                phase = phase,
                actors = actors ?: return null,
                action = action ?: return null,
                emits = emits,
                waitFor = waitFor,
                parallel = parallel,
                assertions = assertions,
                onFail = onFail,
                line = item.line,
            )
        }

        private fun actors(fields: YamlFields): ActorExpression? {
            val node = fields.required("actor") ?: return null
            val path = fields.pathOf("actor")
            val line = reader.lineOf(path)
            return try {
                when (node) {
                    is YamlScalar -> {
                        actorParser.parse(node.content, line)
                    }

                    is YamlList -> {
                        val items = reader.textList(node, path) ?: return null
                        actorParser.parseList(items, line)
                    }

                    else -> {
                        reader.problem(path, "'$path' must be an actor expression or a list of them")
                    }
                }
            } catch (e: CampaignValidationException) {
                reader.addAll(e.issues)
                null
            }
        }

        private fun action(fields: YamlFields): StepAction? =
            when {
                fields.declares("do") && fields.declares("run") -> {
                    reader.problem(fields.path, "'${fields.path}' has both do and run; use one of them")
                }

                fields.declares("do") -> {
                    fields.valued("do")?.let { reader.text(it, fields.pathOf("do")) }?.let { StepAction.Do(it) }
                }

                fields.declares("run") -> {
                    fields.valued("run")?.let { run(it, fields.pathOf("run")) }
                }

                else -> {
                    StepAction.None
                }
            }

        private fun run(
            node: YamlNode,
            path: String,
        ): StepAction.Run? {
            if (node is YamlScalar) return StepAction.Run(node.content)
            val fields = reader.map(node, path, RUN_KEYS) ?: return null
            val function = fields.text("function", required = true)
            val args = fields["args"]?.let { runArgs(it, fields.pathOf("args")) } ?: emptyMap()
            return function?.let { StepAction.Run(it, args) }
        }

        private fun runArgs(
            node: YamlNode,
            path: String,
        ): Map<String, String>? {
            if (node !is YamlMap) return reader.problem(path, "'$path' must be a map of argument names to values")
            return node.entries.entries
                .mapNotNull { (name, value) ->
                    val argPath = childPath(path, name.content)
                    val plain = value.plain()
                    val text = if (plain == null) reader.problem(argPath, "'$argPath' has no value") else reader.text(plain, argPath)
                    text?.let { name.content to it }
                }.toMap()
        }

        private fun emits(
            node: YamlNode,
            path: String,
        ): EmitSpec? {
            if (node is YamlScalar) return EmitSpec(node.content, null)
            val fields = reader.map(node, path, EMITS_KEYS) ?: return null
            val event = fields.text("event", required = true)
            val idSource = fields["id_from"]?.let { idSource(it, fields.pathOf("id_from")) }
            if (fields.has("id_from") && idSource == null) return null
            return event?.let { EmitSpec(it, idSource) }
        }

        private fun waitFor(
            node: YamlNode,
            path: String,
        ): WaitForSpec? {
            if (node is YamlScalar) return WaitForSpec(node.content, DEFAULT_WAIT_TIMEOUT)
            val fields = reader.map(node, path, WAIT_FOR_KEYS) ?: return null
            val event = fields.text("event", required = true)
            val timeout = fields.seconds("timeout_s") ?: DEFAULT_WAIT_TIMEOUT
            return event?.let { WaitForSpec(it, timeout) }
        }

        // ---- assertions ----

        private fun assertions(fields: YamlFields): List<AssertionSpec> {
            val items = reader.list(fields.valued("assert"), fields.pathOf("assert")) ?: return emptyList()
            return items.mapNotNull(::assertion)
        }

        private fun assertion(item: YamlItem): AssertionSpec? {
            val node = item.node
            if (node !is YamlMap || node.entries.size != 1) {
                return reader.problem(
                    item.path,
                    "'${item.path}' must be a single assertion such as '- visible_text: {text: ...}' " +
                        "(one of ${ASSERTION_TYPES.joinToString(", ")})",
                )
            }
            val (key, value) = node.entries.entries.single()
            val type = key.content
            val path = childPath(item.path, type)
            val body = value.plain()
            return when (type) {
                "visible_text" -> visibleText(body, path)
                "not_visible" -> notVisible(body, path)
                "oracle" -> oracle(body, path)
                "http_status" -> httpStatus(body, path)
                "count" -> count(body, path)
                "latency_max" -> latencyMax(body, path)
                "only_one_succeeds" -> onlyOneSucceeds(body, path)
                else -> reader.problem(path, "unknown assertion '$type' (known: ${ASSERTION_TYPES.joinToString(", ")})")
            }
        }

        private fun body(
            node: YamlNode?,
            path: String,
            allowed: Set<String>,
        ): YamlFields? =
            if (node == null) {
                reader.problem(path, "'$path' needs its parameters (${allowed.joinToString(", ")})")
            } else {
                reader.map(node, path, allowed)
            }

        private fun visibleText(
            node: YamlNode?,
            path: String,
        ): AssertionSpec? {
            val fields = body(node, path, VISIBLE_TEXT_KEYS) ?: return null
            val text = fields.text("text", required = true)
            val within = fields.seconds("within_s") ?: DEFAULT_VISIBLE_WITHIN
            return text?.let { AssertionSpec.VisibleText(it, within) }
        }

        private fun notVisible(
            node: YamlNode?,
            path: String,
        ): AssertionSpec? {
            val fields = body(node, path, NOT_VISIBLE_KEYS) ?: return null
            val text = fields.text("text")
            val selector = fields.text("selector")
            if (fields.has("text") == fields.has("selector")) {
                return reader.problem(path, "'$path' needs exactly one of text or selector")
            }
            return if (text != null || selector != null) AssertionSpec.NotVisible(text, selector) else null
        }

        private fun oracle(
            node: YamlNode?,
            path: String,
        ): AssertionSpec? {
            val fields = body(node, path, ORACLE_KEYS) ?: return null
            val oraclePath = fields.text("path", required = true)
            val field = fields.text("field")
            val equals = fields.text("equals")
            val contains = fields.text("contains")
            return oraclePath?.let { AssertionSpec.Oracle(it, field, equals, contains) }
        }

        private fun httpStatus(
            node: YamlNode?,
            path: String,
        ): AssertionSpec? {
            val fields = body(node, path, HTTP_STATUS_KEYS) ?: return null
            val requestPath = fields.text("path", required = true)
            val method = fields.text("method")?.trim()?.uppercase() ?: "GET"
            val equals = fields.int("equals", required = true)
            return if (requestPath != null && equals != null) AssertionSpec.HttpStatus(requestPath, method, equals) else null
        }

        private fun count(
            node: YamlNode?,
            path: String,
        ): AssertionSpec? {
            val fields = body(node, path, COUNT_KEYS) ?: return null
            val selector = fields.text("selector", required = true)
            val equals = fields.int("equals", required = true)
            return if (selector != null && equals != null) AssertionSpec.Count(selector, equals) else null
        }

        private fun latencyMax(
            node: YamlNode?,
            path: String,
        ): AssertionSpec? {
            val fields = body(node, path, LATENCY_KEYS) ?: return null
            return fields.int("ms", required = true)?.let { AssertionSpec.LatencyMax(it.milliseconds) }
        }

        /** `true`, or the map `{request: "<METHOD> <path regex>", oracle: {path, field, equals}}` (both optional). */
        private fun onlyOneSucceeds(
            node: YamlNode?,
            path: String,
        ): AssertionSpec? {
            if (node is YamlMap) return raceSpec(node, path)
            return when (reader.bool(node, path)) {
                true -> AssertionSpec.OnlyOneSucceeds()
                false -> reader.problem(path, "'$path' can only be true or a map with $RACE_FORM")
                null -> if (node == null) reader.problem(path, "'$path' needs the value true or a map with $RACE_FORM") else null
            }
        }

        private fun raceSpec(
            node: YamlMap,
            path: String,
        ): AssertionSpec? {
            val fields = reader.map(node, path, ONLY_ONE_SUCCEEDS_KEYS) ?: return null
            val requestPath = fields.pathOf("request")
            val request =
                fields.valued("request")?.let { reader.text(it, requestPath) }?.let { raw ->
                    RequestPattern.parse(raw)
                        ?: reader.problem(
                            requestPath,
                            "'$requestPath' must be \"<METHOD> <path regex>\", e.g. \"POST .+/approve\", was '$raw'",
                        )
                }
            val oracle =
                fields.valued("oracle")?.let { reader.map(it, fields.pathOf("oracle"), RACE_ORACLE_KEYS) }?.let { oracle ->
                    oracle.text("path", required = true)?.let { OracleCondition(it, oracle.text("field"), oracle.text("equals")) }
                }
            if ((fields.declares("request") && request == null) || (fields.declares("oracle") && oracle == null)) return null
            return AssertionSpec.OnlyOneSucceeds(request, oracle)
        }
    }

    private companion object {
        val DEFAULT_WAIT_TIMEOUT = 30.seconds
        val DEFAULT_VISIBLE_WITHIN = 5.seconds

        val ROOT_KEYS = linkedSetOf("campaign", "target_profile", "setup", "steps")
        val SETTINGS_KEYS =
            linkedSetOf(
                "name",
                "target",
                "testers",
                "seed",
                "names",
                "roles",
                "departments",
                "registration",
                "budget",
                "on_fail",
                "pacing",
            )
        val ROLE_KEYS = linkedSetOf("admin", "manager", "employee")
        val REGISTRATION_KEYS = linkedSetOf("invite", "company_code")
        val BUDGET_KEYS = linkedSetOf("max_steps_per_agent", "max_minutes")
        val PACING_KEYS = linkedSetOf("start_stagger_ms", "max_parallel_actors")
        val TARGET_PROFILE_KEYS = linkedSetOf("paths", "selectors", "id_sources", "flows", "local_storage", "dismiss", "api_prefix")
        val ID_SOURCE_KEYS = linkedSetOf("url_regex", "oracle", "dom", "agent")
        val ORACLE_ID_KEYS = linkedSetOf("path", "field")
        val DOM_ID_KEYS = linkedSetOf("selector", "attribute")
        val STEP_KEYS = linkedSetOf("id", "actor", "do", "run", "emits", "wait_for", "parallel", "on_fail", "assert")
        val RUN_KEYS = linkedSetOf("function", "args")
        val EMITS_KEYS = linkedSetOf("event", "id_from")
        val WAIT_FOR_KEYS = linkedSetOf("event", "timeout_s")
        val ASSERTION_TYPES =
            listOf("visible_text", "not_visible", "oracle", "http_status", "count", "latency_max", "only_one_succeeds")
        val VISIBLE_TEXT_KEYS = linkedSetOf("text", "within_s")
        val NOT_VISIBLE_KEYS = linkedSetOf("text", "selector")
        val ORACLE_KEYS = linkedSetOf("path", "field", "equals", "contains")
        val HTTP_STATUS_KEYS = linkedSetOf("path", "method", "equals")
        val COUNT_KEYS = linkedSetOf("selector", "equals")
        val LATENCY_KEYS = linkedSetOf("ms")
        val ONLY_ONE_SUCCEEDS_KEYS = linkedSetOf("request", "oracle")
        val RACE_ORACLE_KEYS = linkedSetOf("path", "field", "equals")
        const val RACE_FORM = "request (\"<METHOD> <path regex>\") and/or oracle ({path, field, equals})"
    }
}

/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.infrastructure.mcp

import az.petek.core.ids.ArtifactId
import az.petek.core.ids.RunId
import az.petek.dashboard.domain.ExplorationStatus
import az.petek.dashboard.domain.FieldProblem
import az.petek.dashboard.domain.FindingView
import az.petek.dashboard.domain.PanelBackend
import az.petek.dashboard.domain.PanelBudget
import az.petek.dashboard.domain.PanelException
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.PanelNotFoundException
import az.petek.dashboard.domain.PanelRequestException
import az.petek.dashboard.domain.RegistrationSplit
import az.petek.dashboard.domain.RoleSplit
import az.petek.dashboard.domain.RunRequest
import az.petek.dashboard.domain.TeardownView
import az.petek.dashboard.infrastructure.PanelJson
import az.petek.evidence.domain.RunResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * The tools the MCP server offers (R10): the panel's use cases, one tool each, with the same JSON the panel's own API
 * uses ([PanelJson]) as the text content of every result. Tools that change something ([Tool.writes]) are refused
 * unless [McpSettings.allowWrites]; the target policy of the backend applies as everywhere else. A failed tool is an
 * MCP result with `isError` and the panel's Azerbaijani message, never a protocol error, so the host AI can read it.
 */
internal class McpTools(
    private val backend: PanelBackend,
    private val settings: McpSettings,
) {
    private class Tool(
        val name: String,
        val description: String,
        val writes: Boolean = false,
        val schema: SchemaBuilder.() -> Unit = {},
        val run: suspend (JsonObject) -> JsonElement,
    )

    private val tools: List<Tool> = listOf(listTargets(), capacity()) + explorer() + scenarios() + runs()

    private val byName = tools.associateBy { it.name }

    /** The `tools/list` entries: name, description and JSON Schema of the arguments. */
    fun descriptors(): List<JsonElement> =
        tools.map { tool ->
            buildJsonObject {
                put("name", tool.name)
                val writes = if (tool.writes) " Changes state: needs a session that allows writes." else ""
                put("description", tool.description + writes)
                putJsonObject("inputSchema") {
                    put("type", "object")
                    val required = mutableListOf<String>()
                    putJsonObject("properties") { SchemaBuilder(this, required).apply(tool.schema) }
                    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
                }
            }
        }

    /** `tools/call`: the tool's JSON as text content, or an `isError` result with the failure's message. */
    suspend fun call(params: JsonObject): JsonElement {
        val name = params.stringArgument("name")!!
        val tool = byName[name] ?: throw JsonRpcException(JsonRpcError.INVALID_PARAMS, "unknown tool $name")
        val arguments = params.objectArgument("arguments") ?: JsonObject(emptyMap())
        return try {
            if (tool.writes) requireWrites(name)
            content(tool.run(arguments), isError = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: JsonRpcException) {
            throw e
        } catch (e: PanelRequestException) {
            content(error(e.message.orEmpty(), e.problems), isError = true)
        } catch (e: PanelException) {
            content(error(e.message.orEmpty()), isError = true)
        } catch (e: Exception) {
            logger.error(e) { "MCP tool $name failed" }
            content(error("Gözlənilməz xəta baş verdi; ətraflı məlumat loqdadır."), isError = true)
        }
    }

    // --- the tools ------------------------------------------------------------------------------------------------

    private fun listTargets() =
        Tool(
            name = "list_targets",
            description =
                "The site under test this Pətək instance is configured for, whether writes are allowed here, and where " +
                    "evidence is stored.",
        ) {
            buildJsonObject {
                put("target", settings.target)
                putJsonArray("profiles") {
                    settings.profiles.forEach { (name, url) ->
                        addJsonObject {
                            put("name", name)
                            put("url", url)
                        }
                    }
                }
                put("allowWrites", settings.allowWrites)
                put("evidenceDir", settings.evidenceDir.toAbsolutePath().toString())
            }
        }

    private fun capacity() =
        Tool(
            name = "get_capacity",
            description = "How many tester agents this machine can run at once (advice, not a limit) for a wanted count.",
            schema = { integer("testers", "wanted tester count (default 30)") },
        ) { args -> PanelJson.capacity(backend.capacity(args.intArgument("testers") ?: DEFAULT_CAPACITY_TESTERS)) }

    private fun explorer(): List<Tool> =
        listOf(
            Tool(
                name = "explore_site",
                description =
                    "Start the explorer on the site: it walks the pages as a visitor, learns pages, forms and actions, " +
                        "records findings, lists what it could not decide (unknowns) and proposes test ideas and a " +
                        "scenario draft. Runs in the background; poll get_exploration, or set wait=true to return when it " +
                        "ends. allowWrites lets it submit each create form once and needs a session that allows writes.",
                schema = {
                    string("target", "site URL (default: the configured target)")
                    string("instructions", "what to test, what matters, what to avoid (free text)")
                    integer("testers", "how many tester agents the drafted campaign plans for (default 6)")
                    strings("departments", "department names for the drafted company (default IT, HR)")
                    integer("maxPages", "page budget (default 10)")
                    integer("maxMinutes", "time budget in minutes (default 5)")
                    boolean("allowWrites", "let the explorer submit each create form once (test targets only)")
                    boolean("wait", "return only when the exploration has ended")
                },
            ) { args ->
                val writes = args.booleanArgument("allowWrites")
                if (writes) requireWrites("explore_site with allowWrites")
                val instructions = instructions(args, writes)
                val started = backend.startExploration(instructions)
                if (!args.booleanArgument("wait")) return@Tool PanelJson.exploration(started)
                val budget = instructions.budget.maxMinutes.minutes + WAIT_GRACE
                val ended =
                    withTimeoutOrNull(budget) {
                        backend.explorationUpdates.first { it.status != ExplorationStatus.RUNNING }
                    }
                PanelJson.exploration(ended ?: backend.exploration())
            },
            Tool(
                name = "get_exploration",
                description =
                    "The current (or last) exploration: status, pages visited, site model, findings, unknowns, ideas, " +
                        "draft YAML.",
            ) { PanelJson.exploration(backend.exploration()) },
            Tool(
                name = "cancel_exploration",
                description = "Stop the running exploration, keeping what it learned.",
                writes = true,
            ) { buildJsonObject { put("cancelled", backend.cancelExploration()) } },
            Tool(
                name = "list_unknowns",
                description =
                    "The questions the explorer could not answer from the site, with the answers given so far. Ask the " +
                        "owner, then answer_unknown.",
            ) {
                val view = backend.exploration() ?: throw PanelNotFoundException("Hələ kəşfiyyat olmayıb.")
                JsonArray(
                    view.unknowns.map {
                        buildJsonObject {
                            put("id", it.id)
                            put("question", it.question)
                            put("context", it.context)
                            put("answer", it.answer)
                        }
                    },
                )
            },
            Tool(
                name = "answer_unknown",
                description = "Answer one of the explorer's questions with what the owner said; the answer joins the instructions.",
                schema = {
                    string("unknownId", "id from list_unknowns", required = true)
                    string("answer", "the owner's answer (1–2000 characters)", required = true)
                },
            ) { args ->
                PanelJson.exploration(backend.answerUnknown(args.stringArgument("unknownId")!!, args.stringArgument("answer")!!))
            },
            Tool(
                name = "compare_explorations",
                description =
                    "What changed on the site since the previous exploration of the same target; null when there is " +
                        "nothing to compare.",
            ) { PanelJson.modelDiff(backend.compareWithPrevious()) },
        )

    private fun scenarios(): List<Tool> =
        listOf(
            Tool(
                name = "generate_scenario",
                description =
                    "Turn the current exploration's draft into a new DRAFT scenario version in the catalog (the owner " +
                        "approves it).",
            ) { PanelJson.scenario(backend.generateScenario()) },
            Tool(
                name = "list_scenarios",
                description =
                    "Every scenario version in the catalog: name, version, status (DRAFT, APPROVED, FROZEN, SUPERSEDED), " +
                        "source.",
            ) { PanelJson.scenarioVersions(backend.scenarios()) },
            Tool(
                name = "get_scenario",
                description = "A scenario version with its campaign YAML.",
                schema = { string("id", "scenario version id", required = true) },
            ) { args ->
                val scenario = backend.scenario(args.stringArgument("id")!!)
                PanelJson.scenario(scenario ?: throw PanelNotFoundException("Ssenari tapılmadı."))
            },
            Tool(
                name = "diff_scenarios",
                description = "The unified diff between two scenario versions.",
                schema = {
                    string("fromId", "older version id", required = true)
                    string("toId", "newer version id", required = true)
                },
            ) { args -> PanelJson.diff(backend.diff(args.stringArgument("fromId")!!, args.stringArgument("toId")!!)) },
            Tool(
                name = "get_run_plan",
                description = "The plan a run of the scenario would follow: steps, actors, agents, emits and waits, assertions.",
                schema = { string("scenarioId", "scenario version id", required = true) },
            ) { args ->
                val plan = backend.runPlan(args.stringArgument("scenarioId")!!)
                PanelJson.plan(plan ?: throw PanelNotFoundException("Bu ssenarinin planı yoxdur."))
            },
            Tool(
                name = "approve_scenario",
                description = "DRAFT → APPROVED (supersedes the name's previous approved version). Only after the owner agreed.",
                writes = true,
                schema = { string("id", "scenario version id", required = true) },
            ) { args -> PanelJson.scenarioVersion(backend.approve(args.stringArgument("id")!!)) },
            Tool(
                name = "freeze_scenario",
                description = "APPROVED → FROZEN; a frozen version never changes again.",
                writes = true,
                schema = { string("id", "scenario version id", required = true) },
            ) { args -> PanelJson.scenarioVersion(backend.freeze(args.stringArgument("id")!!)) },
        )

    private fun runs(): List<Tool> =
        listOf(
            Tool(
                name = "run_campaign",
                description =
                    "Run an approved scenario with its tester agents against the configured target (one run at a time). " +
                        "Runs in the background; poll get_run_status, or set wait=true to return the run's summary when it ends.",
                writes = true,
                schema = {
                    string("scenarioId", "an APPROVED or FROZEN scenario version id", required = true)
                    integer("testers", "override the scenario's tester count")
                    boolean("wait", "return only when the run has ended")
                },
            ) { args ->
                val request = RunRequest(scenarioId = args.stringArgument("scenarioId"), testers = args.intArgument("testers"))
                val started = backend.startRun(request)
                if (!args.booleanArgument("wait")) return@Tool PanelJson.runStarted(started)
                waitForRun(started.runId)
            },
            Tool(
                name = "cancel_run",
                description = "Cancel the running run; its teardown and report still happen.",
                writes = true,
            ) { buildJsonObject { put("cancelled", backend.cancelRun()) } },
            Tool(
                name = "list_runs",
                description = "Past and current runs, newest first: result, counts, findings, tokens, report availability.",
            ) { PanelJson.runs(backend.runs()) },
            Tool(
                name = "get_run_status",
                description = "One run's summary and, when written, the absolute path of its report directory.",
                schema = { string("runId", "run id", required = true) },
            ) { args -> runStatus(RunId(args.stringArgument("runId")!!)) },
            Tool(
                name = "get_findings",
                description =
                    "The judged findings of a run: class (BACKEND, DELIVERY_UI, INVESTIGATE, FLAKY, AGENT_FAILURE), step, " +
                        "agent, the three sources A (what the sender did), B (what receivers saw), C (what the target's API " +
                        "says), and the evidence artifact ids for get_evidence. A root-cause investigation starts here.",
                schema = { string("runId", "run id", required = true) },
            ) { args -> findings(backend.findings(RunId(args.stringArgument("runId")!!))) },
            Tool(
                name = "get_evidence",
                description =
                    "An evidence artifact (screenshot, capture) by id: its type and absolute path, for reading with your " +
                        "own file tools.",
                schema = { string("artifactId", "artifact id from a finding, a triage verdict or an exploration page", required = true) },
            ) { args ->
                val id = ArtifactId(args.stringArgument("artifactId")!!)
                val record =
                    backend.explorationArtifact(id) ?: throw PanelNotFoundException("Sübut tapılmadı və ya göstərilməyib.")
                buildJsonObject {
                    put("artifactId", record.artifactId.value)
                    put("runId", record.runId.value)
                    put("stepId", record.stepId.value)
                    put("type", record.type.name)
                    put(
                        "path",
                        settings.evidenceDir
                            .toAbsolutePath()
                            .resolve(record.relativePath)
                            .toString(),
                    )
                    put("sha256", record.sha256)
                    put("sizeBytes", record.sizeBytes)
                }
            },
            Tool(
                name = "get_triage",
                description =
                    "The stored triage of a run (system bug / model gap / scenario bug per surprise); null when not " +
                        "triaged.",
                schema = { string("runId", "run id", required = true) },
            ) { args -> PanelJson.triage(backend.triage(RunId(args.stringArgument("runId")!!))) },
            Tool(
                name = "run_triage",
                description = "Triage a finished run's surprises (one AI question per surprise; may take a while).",
                schema = { string("runId", "run id", required = true) },
            ) { args -> PanelJson.triage(backend.runTriage(RunId(args.stringArgument("runId")!!))) },
            Tool(
                name = "get_stability",
                description = "How the steps of a --repeat group behaved across its runs (flaky steps).",
                schema = { string("repeatGroup", "repeat group id", required = true) },
            ) { args -> PanelJson.stability(backend.stability(args.stringArgument("repeatGroup")!!)) },
            Tool(
                name = "teardown",
                description =
                    "Delete the test data a finished run created on the target (its test companies, through the " +
                        "target's test API).",
                writes = true,
                schema = { string("runId", "run id", required = true) },
            ) { args -> teardown(backend.teardown(RunId(args.stringArgument("runId")!!))) },
        )

    // --- helpers --------------------------------------------------------------------------------------------------

    private fun requireWrites(what: String) {
        if (!settings.allowWrites) {
            throw PanelRequestException(
                listOf(
                    FieldProblem(
                        "allowWrites",
                        "$what changes state, and this MCP session is read-only; start `petek mcp --allow-writes` to permit it.",
                    ),
                ),
            )
        }
    }

    private fun instructions(
        args: JsonObject,
        writes: Boolean,
    ): PanelInstructions {
        val testers = args.intArgument("testers") ?: DEFAULT_TESTERS
        val joining = (testers - 1).coerceAtLeast(0)
        val managers = joining / MANAGER_SHARE
        val employees = joining - managers
        val invite = managers + employees / 2
        return PanelInstructions(
            target = args.stringArgument("target", required = false) ?: settings.target,
            instructions = args.stringArgument("instructions", required = false).orEmpty(),
            testers = testers,
            roles = RoleSplit(admins = 1, managers = managers, employees = employees),
            departments = args.stringsArgument("departments") ?: DEFAULT_DEPARTMENTS,
            registration = RegistrationSplit(invite = invite, companyCode = joining - invite),
            budget =
                PanelBudget(
                    maxMinutes = args.intArgument("maxMinutes") ?: DEFAULT_MINUTES,
                    maxStepsPerAgent = DEFAULT_STEPS,
                    maxPages = args.intArgument("maxPages") ?: DEFAULT_PAGES,
                ),
            allowWrites = writes,
        )
    }

    private suspend fun waitForRun(runId: RunId): JsonElement {
        val ended =
            withTimeoutOrNull(RUN_WAIT) {
                while (backend.runs().firstOrNull { it.runId == runId }?.let { it.result == RunResult.RUNNING } != false) {
                    delay(RUN_POLL)
                }
                true
            } ?: false
        return runStatus(runId, ended)
    }

    private suspend fun runStatus(
        runId: RunId,
        ended: Boolean? = null,
    ): JsonElement {
        val run = backend.runs().firstOrNull { it.runId == runId } ?: throw PanelNotFoundException("Run tapılmadı.")
        val summary = (PanelJson.runs(listOf(run)) as JsonArray).single() as JsonObject
        val report = backend.reportDirectory(runId)?.toAbsolutePath()?.toString()
        return JsonObject(
            summary +
                mapOf(
                    "reportDirectory" to (report?.let(::JsonPrimitive) ?: JsonNull),
                    "waitedToEnd" to (ended?.let(::JsonPrimitive) ?: JsonNull),
                ),
        )
    }

    private fun findings(views: List<FindingView>): JsonElement =
        JsonArray(
            views.map {
                buildJsonObject {
                    put("findingId", it.findingId.value)
                    put("class", it.findingClass.name)
                    put("step", it.scenarioStep)
                    put("agentId", it.agentId?.value)
                    put("a", it.a)
                    put("b", it.b)
                    put("c", it.c)
                    put("note", it.note)
                    put("evidenceTier", it.evidenceTier.name)
                    putJsonArray("artifactIds") { it.artifactIds.forEach { id -> add(id.value) } }
                }
            },
        )

    private fun teardown(view: TeardownView): JsonElement =
        buildJsonObject {
            put("runId", view.runId.value)
            putJsonArray("removed") { view.removed.forEach { add(it) } }
            putJsonArray("failures") { view.failures.forEach { add(it) } }
        }

    private fun error(
        message: String,
        problems: List<FieldProblem> = emptyList(),
    ): JsonElement =
        buildJsonObject {
            put("error", message)
            putJsonArray("problems") {
                problems.forEach { problem ->
                    addJsonObject {
                        put("field", problem.field)
                        put("message", problem.message)
                    }
                }
            }
        }

    private fun content(
        payload: JsonElement,
        isError: Boolean,
    ): JsonElement =
        buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", PanelJson.encode(payload))
                }
            }
            put("structuredContent", payload as? JsonObject ?: buildJsonObject { put("items", payload) })
            put("isError", isError)
        }

    /** JSON Schema properties of a tool's arguments; [required] collects the names marked so. */
    private class SchemaBuilder(
        private val properties: JsonObjectBuilder,
        private val required: MutableList<String>,
    ) {
        fun string(
            name: String,
            description: String,
            required: Boolean = false,
        ) = property(name, "string", description, required)

        fun integer(
            name: String,
            description: String,
        ) = property(name, "integer", description, false)

        fun boolean(
            name: String,
            description: String,
        ) = property(name, "boolean", description, false)

        fun strings(
            name: String,
            description: String,
        ) {
            properties.putJsonObject(name) {
                put("type", "array")
                put("description", description)
                putJsonObject("items") { put("type", "string") }
            }
        }

        private fun property(
            name: String,
            type: String,
            description: String,
            isRequired: Boolean,
        ) {
            properties.putJsonObject(name) {
                put("type", type)
                put("description", description)
            }
            if (isRequired) required += name
        }
    }

    private companion object {
        const val DEFAULT_TESTERS = 6
        const val DEFAULT_CAPACITY_TESTERS = 30
        const val MANAGER_SHARE = 3
        const val DEFAULT_MINUTES = 5
        const val DEFAULT_STEPS = 20
        const val DEFAULT_PAGES = 10
        val DEFAULT_DEPARTMENTS = listOf("IT", "HR")
        val WAIT_GRACE: Duration = 1.minutes
        val RUN_WAIT: Duration = 60.minutes
        val RUN_POLL: Duration = 1.seconds
    }
}

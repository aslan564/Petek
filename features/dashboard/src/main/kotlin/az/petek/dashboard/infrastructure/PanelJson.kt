/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.infrastructure

import az.petek.dashboard.domain.AccountRequest
import az.petek.dashboard.domain.AccountView
import az.petek.dashboard.domain.CapacityView
import az.petek.dashboard.domain.DiffView
import az.petek.dashboard.domain.EventView
import az.petek.dashboard.domain.ExplorationView
import az.petek.dashboard.domain.FieldProblem
import az.petek.dashboard.domain.ManualCodeView
import az.petek.dashboard.domain.OrchestratorSnapshot
import az.petek.dashboard.domain.PanelBudget
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.PanelRequestException
import az.petek.dashboard.domain.PhaseState
import az.petek.dashboard.domain.PlanStepView
import az.petek.dashboard.domain.RegistrationSplit
import az.petek.dashboard.domain.RoleSplit
import az.petek.dashboard.domain.RunPlanView
import az.petek.dashboard.domain.RunRequest
import az.petek.dashboard.domain.RunStartView
import az.petek.dashboard.domain.RunSummaryView
import az.petek.dashboard.domain.ScenarioVersionView
import az.petek.dashboard.domain.ScenarioView
import az.petek.dashboard.domain.SiteModelDiffView
import az.petek.dashboard.domain.StabilityView
import az.petek.dashboard.domain.TriageView
import az.petek.dashboard.domain.VisitedPageView
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * The panel's wire format besides the live board ([DashboardJson]): views out, requests in. Times travel as epoch
 * milliseconds (`...AtMs`), enums by name. Requests are read field by field so a malformed one fails with a
 * [PanelRequestException] that names the field, never with a parser stack trace.
 */
internal object PanelJson {
    private val json = Json { prettyPrint = false }

    fun encode(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)

    fun error(
        message: String,
        problems: List<FieldProblem> = emptyList(),
    ): String =
        encode(
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
            },
        )

    // --- views ----------------------------------------------------------------------------------------------------

    fun capacity(view: CapacityView): JsonElement =
        buildJsonObject {
            put("requested", view.requested)
            put("recommended", view.recommended)
            put("exceeds", view.exceeds)
            put("limitingFactor", view.limitingFactor.name)
            put("availableMemoryMb", view.availableMemoryMb)
            put("totalMemoryMb", view.totalMemoryMb)
            put("cpuCores", view.cpuCores)
            put("measured", view.measured)
            strings("notes", view.notes)
        }

    fun exploration(view: ExplorationView?): JsonElement =
        view?.let {
            buildJsonObject {
                put("id", it.id)
                put("target", it.target)
                put("instructions", it.instructions)
                put("status", it.status.name)
                time("startedAtMs", it.startedAt)
                put("elapsedMs", it.elapsedMs)
                budget(it.budget)
                putJsonArray("phases") {
                    it.phases.forEach { phase ->
                        addJsonObject {
                            put("phase", phase.phase.name)
                            put("state", phase.state.name)
                            put("pagesVisited", phase.pagesVisited)
                            strings("roles", phase.roles)
                        }
                    }
                }
                put("currentPage", it.currentPage?.let(::page) ?: JsonNull)
                putJsonArray("visited") { it.visited.forEach { visited -> add(page(visited)) } }
                siteModel(it)
                putJsonArray("findings") {
                    it.findings.forEach { finding ->
                        addJsonObject {
                            put("kind", finding.kind)
                            put("severity", finding.severity.name)
                            put("pageUrl", finding.pageUrl)
                            put("detail", finding.detail)
                            strings("artifacts", finding.artifactIds.map { id -> id.value })
                        }
                    }
                }
                putJsonArray("unknowns") {
                    it.unknowns.forEach { unknown ->
                        addJsonObject {
                            put("id", unknown.id)
                            put("question", unknown.question)
                            put("context", unknown.context)
                            put("answer", unknown.answer)
                        }
                    }
                }
                putJsonArray("ideas") {
                    it.ideas.forEach { idea ->
                        addJsonObject {
                            put("pattern", idea.pattern)
                            put("action", idea.action)
                            put("rationale", idea.rationale)
                            put("priority", idea.priority)
                        }
                    }
                }
                put("draftYaml", it.draftYaml)
                put("previousModelVersion", it.previousModelVersion)
                putJsonArray("activity") {
                    it.activity.forEach { event ->
                        addJsonObject {
                            time("atMs", event.at)
                            put("kind", event.kind)
                            put("text", event.text)
                        }
                    }
                }
                put("message", it.message)
            }
        } ?: JsonNull

    /** The small part of an exploration the sidebar shows on every screen. */
    fun explorationBrief(view: ExplorationView?): JsonElement =
        view?.let {
            buildJsonObject {
                put("id", it.id)
                put("target", it.target)
                put("status", it.status.name)
                put(
                    "phase",
                    it.phases
                        .firstOrNull { phase -> phase.state == PhaseState.RUNNING }
                        ?.phase
                        ?.name,
                )
                put("pagesVisited", it.phases.sumOf { phase -> phase.pagesVisited })
                put("maxPages", it.budget.maxPages)
                put("elapsedMs", it.elapsedMs)
            }
        } ?: JsonNull

    fun modelDiff(view: SiteModelDiffView?): JsonElement =
        view?.let {
            buildJsonObject {
                put("fromVersion", it.fromVersion)
                put("toVersion", it.toVersion)
                putJsonArray("changes") {
                    it.changes.forEach { change ->
                        addJsonObject {
                            put("kind", change.kind.name)
                            put("subject", change.subject)
                            put("name", change.name)
                            put("detail", change.detail)
                        }
                    }
                }
            }
        } ?: JsonNull

    fun scenarioVersions(views: List<ScenarioVersionView>): JsonElement = JsonArray(views.map(::scenarioVersion))

    fun scenarioVersion(view: ScenarioVersionView): JsonElement =
        buildJsonObject {
            put("id", view.id)
            put("name", view.name)
            put("version", view.version)
            put("status", view.status.name)
            put("source", view.source.name)
            put("parentId", view.parentId)
            put("note", view.note)
            time("createdAtMs", view.createdAt)
            time("approvedAtMs", view.approvedAt)
            time("frozenAtMs", view.frozenAt)
            put("runnable", view.runnable)
        }

    fun scenario(view: ScenarioView?): JsonElement =
        view?.let {
            buildJsonObject {
                put("version", scenarioVersion(it.version))
                put("yaml", it.yaml)
            }
        } ?: JsonNull

    fun diff(view: DiffView): JsonElement =
        buildJsonObject {
            put("from", scenarioVersion(view.from))
            put("to", scenarioVersion(view.to))
            put("added", view.added)
            put("removed", view.removed)
            putJsonArray("lines") {
                view.lines.forEach { line ->
                    addJsonObject {
                        put("kind", line.kind.name)
                        put("text", line.text)
                        put("old", line.oldNumber)
                        put("new", line.newNumber)
                    }
                }
            }
        }

    fun triage(view: TriageView?): JsonElement =
        view?.let {
            buildJsonObject {
                put("runId", it.runId.value)
                put("scenarioId", it.scenarioId)
                putJsonArray("verdicts") {
                    it.verdicts.forEach { verdict ->
                        addJsonObject {
                            put("surpriseId", verdict.surpriseId)
                            put("step", verdict.scenarioStep)
                            put("agentId", verdict.agentId?.value)
                            put("surpriseKind", verdict.surpriseKind)
                            put("surprise", verdict.surprise)
                            put("category", verdict.category.name)
                            put("rationale", verdict.rationale)
                            put("confidence", verdict.confidence)
                            put("proposedChange", verdict.proposedChange)
                            put("proposalScenarioId", verdict.proposalScenarioId)
                            strings("evidence", verdict.evidence.map { id -> id.value })
                        }
                    }
                }
            }
        } ?: JsonNull

    fun plan(view: RunPlanView?): JsonElement =
        view?.let {
            buildJsonObject {
                put("runId", it.runId?.value)
                put("campaignName", it.campaignName)
                putJsonArray("steps") { it.steps.forEach { step -> add(planStep(step)) } }
            }
        } ?: JsonNull

    fun orchestrator(snapshot: OrchestratorSnapshot): String =
        encode(
            buildJsonObject {
                put("version", snapshot.version)
                time("generatedAtMs", snapshot.generatedAt)
                put("run", DashboardJson.runElement(snapshot.run))
                put("plan", plan(snapshot.plan))
                putJsonArray("agents") {
                    snapshot.agents.forEach { agent ->
                        addJsonObject {
                            put("id", agent.agentId.value)
                            put("name", agent.displayName)
                            put("role", agent.role?.key)
                        }
                    }
                }
                putJsonArray("tasks") {
                    snapshot.tasks.forEach { task ->
                        addJsonObject {
                            put("step", task.stepId)
                            put("agentId", task.agentId.value)
                            put("state", task.state.name)
                            put("detail", task.detail)
                            time("atMs", task.updatedAt)
                        }
                    }
                }
                putJsonObject("taskCounts") { snapshot.taskCounts.forEach { (state, count) -> put(state.name, count) } }
                putJsonArray("events") { snapshot.events.forEach { add(event(it)) } }
            },
        )

    fun runs(views: List<RunSummaryView>): JsonElement =
        JsonArray(
            views.map {
                buildJsonObject {
                    put("runId", it.runId.value)
                    put("campaignName", it.campaignName)
                    put("target", it.target)
                    time("startedAtMs", it.startedAt)
                    time("endedAtMs", it.endedAt)
                    put("durationMs", it.durationMs)
                    put("result", it.result.name)
                    put("testers", it.testers)
                    put("stepsPassed", it.stepsPassed)
                    put("stepsFailed", it.stepsFailed)
                    put("assertionsPassed", it.assertionsPassed)
                    put("assertionsFailed", it.assertionsFailed)
                    put("findings", it.findings)
                    put("inputTokens", it.inputTokens)
                    put("outputTokens", it.outputTokens)
                    put("costUsd", it.costUsd)
                    put("repeatGroup", it.repeatGroup)
                    put("repeatIndex", it.repeatIndex)
                    put("reportUrl", if (it.reportAvailable) "/runs/${it.runId.value}/report/" else null)
                    put("triaged", it.triaged)
                    put("scenarioId", it.scenarioId)
                }
            },
        )

    fun stability(view: StabilityView?): JsonElement =
        view?.let {
            buildJsonObject {
                put("repeatGroup", it.repeatGroup)
                strings("runs", it.runs.map { id -> id.value })
                putJsonArray("steps") {
                    it.steps.forEach { step ->
                        addJsonObject {
                            put("step", step.scenarioStep)
                            put("runs", step.runs)
                            put("passed", step.passed)
                            put("flaky", step.flaky)
                        }
                    }
                }
            }
        } ?: JsonNull

    fun runStarted(view: RunStartView): JsonElement =
        buildJsonObject {
            put("runId", view.runId.value)
            put("scenarioId", view.scenarioId)
            put("testers", view.testers)
        }

    // --- requests -------------------------------------------------------------------------------------------------

    fun instructions(body: String): PanelInstructions {
        val root = parse(body)
        val roles = root.obj("roles")
        val registration = root.obj("registration")
        val budget = root.obj("budget")
        return PanelInstructions(
            target = root.string("target"),
            instructions = root.string("instructions", required = false),
            testers = root.int("testers"),
            roles = RoleSplit(roles.int("admins"), roles.int("managers"), roles.int("employees")),
            departments = root.strings("departments"),
            registration = RegistrationSplit(registration.int("invite"), registration.int("companyCode")),
            budget = PanelBudget(budget.int("maxMinutes"), budget.int("maxStepsPerAgent"), budget.int("maxPages")),
            allowWrites = root.boolean("allowWrites"),
        )
    }

    fun runRequest(body: String): RunRequest {
        val root = parse(body)
        return RunRequest(
            scenarioId = root.optionalString("scenarioId"),
            campaignPath = root.optionalString("campaignPath"),
            testers = root["testers"]?.takeUnless { it is JsonNull }?.let { root.int("testers") },
            headful = root.boolean("headful"),
            target = root.optionalString("target"),
        )
    }

    /** The text of an answer to one of the explorer's questions (1 to [MAX_ANSWER_CHARS] characters). */
    fun answer(body: String): String {
        val answer = parse(body).string("answer").trim()
        if (answer.isEmpty() || answer.length > MAX_ANSWER_CHARS) {
            throw PanelRequestException(listOf(FieldProblem("answer", "Cavab 1–$MAX_ANSWER_CHARS simvol olmalıdır.")))
        }
        return answer
    }

    /** `{"target", "role", "email", "password"}` from the "Hesablar" form. */
    fun accountRequest(body: String): AccountRequest {
        val root = parse(body)
        return AccountRequest(root.string("target"), root.string("role"), root.string("email"), root.string("password"))
    }

    /** The owner's accounts per site, never with a password. */
    fun accounts(accounts: List<AccountView>): JsonElement =
        buildJsonObject {
            putJsonArray("accounts") {
                accounts.forEach { account ->
                    addJsonObject {
                        put("site", account.site)
                        put("role", account.role)
                        put("email", account.email)
                        put("passwordVariable", account.passwordVariable)
                    }
                }
            }
        }

    /** `{"code": "123456"}` from the owner's "Kodu daxil et" form. */
    fun manualCode(body: String): String = parse(body).string("code").trim()

    /** The testers waiting for a code the owner types in (`PETEK_MAIL_SOURCE=manual`). */
    fun manualCodes(requests: List<ManualCodeView>): JsonElement =
        buildJsonObject {
            putJsonArray("requests") {
                requests.forEach { request ->
                    addJsonObject {
                        put("id", request.id)
                        put("address", request.address)
                        put("askedAt", request.askedAt.toString())
                    }
                }
            }
        }

    // --- helpers --------------------------------------------------------------------------------------------------

    private const val MAX_ANSWER_CHARS = 2_000

    private fun page(view: VisitedPageView): JsonElement =
        buildJsonObject {
            put("url", view.url)
            put("title", view.title)
            put("visitedAs", view.visitedAs)
            put("httpStatus", view.httpStatus)
            put("loadMs", view.loadMs)
            put("screenshot", view.screenshotArtifactId?.value)
            time("atMs", view.at)
        }

    private fun JsonObjectBuilder.siteModel(view: ExplorationView) {
        putJsonObject("model") {
            put("version", view.model.version)
            putJsonArray("pages") {
                view.model.pages.forEach { page ->
                    addJsonObject {
                        put("id", page.id)
                        put("urlPattern", page.urlPattern)
                        put("title", page.title)
                        put("purpose", page.purpose)
                        strings("reachableBy", page.reachableBy)
                        put("provenance", page.provenance.name)
                        putJsonArray("forms") {
                            page.forms.forEach { form ->
                                addJsonObject {
                                    put("purpose", form.purpose)
                                    put("provenance", form.provenance.name)
                                    putJsonArray("fields") {
                                        form.fields.forEach { field ->
                                            addJsonObject {
                                                put("label", field.label)
                                                put("type", field.type)
                                                put("required", field.required)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        putJsonArray("actions") {
                            page.actions.forEach { action ->
                                addJsonObject {
                                    put("id", action.id)
                                    put("name", action.name)
                                    put("kind", action.kind)
                                    put("provenance", action.provenance.name)
                                    strings("allowedRoles", action.allowedRoles)
                                    strings("forbiddenRoles", action.forbiddenRoles)
                                    put("triggersRealtime", action.triggersRealtime)
                                }
                            }
                        }
                    }
                }
            }
            put("kind", view.model.kind)
            put("kindReason", view.model.kindReason)
            strings("gate", view.model.gate)
            putJsonArray("realtime") {
                view.model.realtime.forEach { realtime ->
                    addJsonObject {
                        put("transport", realtime.transport)
                        put("detail", realtime.detail)
                        strings("pages", realtime.pages)
                    }
                }
            }
        }
    }

    private fun planStep(step: PlanStepView): JsonElement =
        buildJsonObject {
            put("id", step.id)
            put("setup", step.setup)
            put("actors", step.actors)
            strings("agentIds", step.agentIds.map { it.value })
            put("kind", step.kind)
            put("action", step.action)
            put("emits", step.emits)
            put("waitFor", step.waitFor)
            put("parallel", step.parallel)
            strings("assertions", step.assertions)
        }

    private fun event(view: EventView): JsonElement =
        buildJsonObject {
            put("id", view.eventId.value)
            put("name", view.name)
            put("emitter", view.emitter.value)
            put("objectId", view.objectId)
            time("t0Ms", view.t0)
            put("received", view.received)
            put("missing", view.missing)
            put("medianMs", view.medianLatencyMs)
            put("maxMs", view.maxLatencyMs)
            putJsonArray("receipts") {
                view.receipts.forEach { receipt ->
                    addJsonObject {
                        put("agentId", receipt.receiver.value)
                        put("received", receipt.received)
                        put("latencyMs", receipt.latencyMs)
                    }
                }
            }
        }

    private fun JsonObjectBuilder.budget(budget: PanelBudget) {
        putJsonObject("budget") {
            put("maxMinutes", budget.maxMinutes)
            put("maxStepsPerAgent", budget.maxStepsPerAgent)
            put("maxPages", budget.maxPages)
        }
    }

    private fun JsonObjectBuilder.time(
        key: String,
        instant: Instant?,
    ) {
        put(key, instant?.toEpochMilli())
    }

    private fun JsonObjectBuilder.strings(
        key: String,
        values: List<String>,
    ) {
        putJsonArray(key) { values.forEach { add(it) } }
    }

    private fun parse(body: String): JsonObject {
        val element =
            try {
                Json.parseToJsonElement(body)
            } catch (_: SerializationException) {
                null
            }
        return element as? JsonObject ?: throw invalid("body", "Sorğu JSON obyekti olmalıdır.")
    }

    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: throw invalid(key, "\"$key\" obyekti yoxdur.")

    private fun JsonObject.string(
        key: String,
        required: Boolean = true,
    ): String {
        val value = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return value ?: if (required) throw invalid(key, "\"$key\" mətni yoxdur.") else ""
    }

    private fun JsonObject.optionalString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int {
        val primitive = this[key] as? JsonPrimitive
        return primitive?.takeUnless { it.isString }?.intOrNull ?: throw invalid(key, "\"$key\" tam ədəd olmalıdır.")
    }

    private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false

    private fun JsonObject.strings(key: String): List<String> {
        val array = this[key] as? JsonArray ?: throw invalid(key, "\"$key\" siyahı olmalıdır.")
        return array.map {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                ?: throw invalid(key, "\"$key\" mətnlərdən ibarət olmalıdır.")
        }
    }

    private fun invalid(
        field: String,
        message: String,
    ) = PanelRequestException(listOf(FieldProblem(field, message)))
}

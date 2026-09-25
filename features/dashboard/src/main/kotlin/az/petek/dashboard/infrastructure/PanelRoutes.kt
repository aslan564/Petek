/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.infrastructure

import az.petek.core.ids.RunId
import az.petek.dashboard.domain.FieldProblem
import az.petek.dashboard.domain.PanelBackend
import az.petek.dashboard.domain.PanelConflictException
import az.petek.dashboard.domain.PanelException
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.PanelNotFoundException
import az.petek.dashboard.domain.PanelRequestException
import az.petek.dashboard.domain.PanelUnavailableException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = KotlinLogging.logger {}

/**
 * The panel's REST API over [backend]. Reads are GET; everything that starts, stops or changes something is a POST
 * (the server checks its token before routing, see [RequestGuard]). Answers are JSON; failures are
 * `{"error": "...", "problems": [{"field", "message"}]}` with 400 (invalid), 404, 409 (not now) or 503 (not wired).
 */
internal fun Route.panelRoutes(backend: PanelBackend) {
    explorationRoutes(backend)
    scenarioRoutes(backend)
    runRoutes(backend)
}

private fun Route.explorationRoutes(backend: PanelBackend) {
    get("/api/capacity") {
        val testers =
            call.request.queryParameters["testers"]
                ?.toIntOrNull()
                ?.takeIf { it in 1..PanelInstructions.MAX_TESTERS }
        call.answer {
            testers ?: throw invalid("testers", "Tester sayı 1 ilə ${PanelInstructions.MAX_TESTERS} arasında olmalıdır.")
            PanelJson.capacity(backend.capacity(testers))
        }
    }
    get("/api/exploration") { call.answer { PanelJson.exploration(backend.exploration()) } }
    get("/api/exploration/diff") { call.answer { PanelJson.modelDiff(backend.compareWithPrevious()) } }
    post("/api/exploration") {
        call.answer(HttpStatusCode.Accepted) {
            val instructions = PanelJson.instructions(call.jsonBody())
            instructions.problems().takeIf { it.isNotEmpty() }?.let { throw PanelRequestException(it) }
            PanelJson.exploration(backend.startExploration(instructions))
        }
    }
    post("/api/exploration/cancel") { call.answer { cancelled(backend.cancelExploration()) } }
    post("/api/exploration/unknowns/{id}") {
        call.answer {
            val id = call.id("id")
            PanelJson.exploration(backend.answerUnknown(id, PanelJson.answer(call.jsonBody())))
        }
    }
}

private fun Route.scenarioRoutes(backend: PanelBackend) {
    get("/api/scenarios") { call.answer { PanelJson.scenarioVersions(backend.scenarios()) } }
    post("/api/scenarios/generate") { call.answer(HttpStatusCode.Created) { PanelJson.scenario(backend.generateScenario()) } }
    get("/api/scenarios/{id}") {
        call.answer { PanelJson.scenario(backend.scenario(call.id("id")) ?: throw notFound("Ssenari tapılmadı.")) }
    }
    get("/api/scenarios/{id}/plan") {
        call.answer { PanelJson.plan(backend.runPlan(call.id("id")) ?: throw notFound("Bu ssenarinin planı yoxdur.")) }
    }
    get("/api/scenarios/{id}/diff") {
        call.answer {
            val from = call.request.queryParameters["from"]?.takeIf(ID::matches) ?: throw invalid("from", "Müqayisə üçün versiya seçin.")
            PanelJson.diff(backend.diff(from, call.id("id")))
        }
    }
    post("/api/scenarios/{id}/approve") { call.answer { PanelJson.scenarioVersion(backend.approve(call.id("id"))) } }
    post("/api/scenarios/{id}/freeze") { call.answer { PanelJson.scenarioVersion(backend.freeze(call.id("id"))) } }
}

private fun Route.runRoutes(backend: PanelBackend) {
    get("/api/runs") { call.answer { PanelJson.runs(backend.runs()) } }
    post("/api/runs") {
        call.answer(HttpStatusCode.Accepted) {
            val request = PanelJson.runRequest(call.jsonBody())
            request.problems().takeIf { it.isNotEmpty() }?.let { throw PanelRequestException(it) }
            PanelJson.runStarted(backend.startRun(request))
        }
    }
    post("/api/runs/cancel") { call.answer { cancelled(backend.cancelRun()) } }
    get("/api/runs/{runId}/triage") { call.answer { PanelJson.triage(backend.triage(RunId(call.id("runId")))) } }
    post("/api/runs/{runId}/triage") { call.answer { PanelJson.triage(backend.runTriage(RunId(call.id("runId")))) } }
    get("/api/stability") {
        call.answer {
            val group = call.request.queryParameters["group"]?.takeIf(ID::matches) ?: throw invalid("group", "Təkrar qrupu seçin.")
            PanelJson.stability(backend.stability(group))
        }
    }
}

/** Ids travel in paths and queries; anything but plain id characters is refused before it reaches the backend. */
private val ID = Regex("[A-Za-z0-9_.:-]{1,128}")
private const val MAX_BODY_BYTES = 64L * 1024

private fun ApplicationCall.id(name: String): String =
    parameters[name]?.takeIf(ID::matches) ?: throw PanelNotFoundException("Belə bir qeyd yoxdur.")

/** The JSON body of a POST, refused when it is not JSON, has no declared length or is larger than 64 KiB. */
private suspend fun ApplicationCall.jsonBody(): String {
    val length = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (!request.contentType().match(ContentType.Application.Json)) throw invalid("body", "Sorğu JSON formatında olmalıdır.")
    if (length == null || length > MAX_BODY_BYTES) throw invalid("body", "Sorğu çox böyükdür.")
    return receiveText()
}

private fun cancelled(done: Boolean): JsonElement = buildJsonObject { put("cancelled", done) }

private fun invalid(
    field: String,
    message: String,
) = PanelRequestException(listOf(FieldProblem(field, message)))

private fun notFound(message: String) = PanelNotFoundException(message)

/** Runs one panel operation and answers with its JSON, or with the status and message its failure calls for. */
private suspend fun ApplicationCall.answer(
    status: HttpStatusCode = HttpStatusCode.OK,
    block: suspend () -> JsonElement,
) {
    response.header(HttpHeaders.CacheControl, "no-store")
    val (code, body) =
        try {
            status to PanelJson.encode(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: PanelRequestException) {
            HttpStatusCode.BadRequest to PanelJson.error(e.message.orEmpty(), e.problems)
        } catch (e: PanelConflictException) {
            HttpStatusCode.Conflict to PanelJson.error(e.message.orEmpty())
        } catch (e: PanelNotFoundException) {
            HttpStatusCode.NotFound to PanelJson.error(e.message.orEmpty())
        } catch (e: PanelUnavailableException) {
            HttpStatusCode.ServiceUnavailable to PanelJson.error(e.message.orEmpty())
        } catch (e: PanelException) {
            HttpStatusCode.BadRequest to PanelJson.error(e.message.orEmpty())
        } catch (e: Exception) {
            logger.error(e) { "panel request ${request.local.uri} failed" }
            HttpStatusCode.InternalServerError to PanelJson.error("Gözlənilməz xəta baş verdi; ətraflı məlumat loqdadır.")
        }
    respondText(body, ContentType.Application.Json, code)
}

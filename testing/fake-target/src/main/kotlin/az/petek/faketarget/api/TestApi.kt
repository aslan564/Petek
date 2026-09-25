/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.api

import az.petek.faketarget.service.CompanyService
import az.petek.faketarget.service.Failure
import az.petek.faketarget.service.InviteRequest
import az.petek.faketarget.service.Outcome
import az.petek.faketarget.service.SeedRequest
import az.petek.faketarget.service.TestQueries
import az.petek.faketarget.web.publicBaseUrl
import az.petek.faketarget.web.status
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * The test-only API (docs/TARGET_CONTRACT.md section 4). Every call needs `X-Test-Token`; a missing or wrong token
 * gets `401` before anything else, even for unknown paths, so the API cannot be probed without the token.
 */
internal class TestApi(
    testToken: String,
    private val queries: TestQueries,
    private val companies: CompanyService,
    private val json: Json,
) {
    private val expectedToken = testToken.toByteArray()

    fun install(route: Route) {
        route.route("/test") {
            get("/otp/{phone}") { guarded { respondOutcome(queries.otp(param("phone"))) { OtpJson(it.phone, it.code) } } }
            get("/companies") {
                guarded {
                    val owner = call.request.queryParameters["owner"]
                    if (owner.isNullOrBlank()) return@guarded call.respondError(Failure.INVALID_REQUEST)
                    respondOutcome(queries.companyByOwner(owner)) { it.toJson() }
                }
            }
            post("/companies/seed") { guarded { seed() } }
            get("/companies/{id}") { guarded { respondOutcome(queries.company(param("id"))) { it.toJson() } } }
            delete("/companies/{id}") {
                guarded {
                    when (val outcome = companies.delete(param("id"))) {
                        is Outcome.Ok -> call.respond(HttpStatusCode.NoContent)
                        is Outcome.Failed -> call.respondError(outcome.failure)
                    }
                }
            }
            get("/announcements/latest") { guarded { respondOutcome(queries.latestAnnouncementBy(query("by"))) { it.toJson() } } }
            get("/announcements/{id}") { guarded { respondOutcome(queries.announcement(param("id"))) { it.toJson() } } }
            get("/announcements/{id}/receipts") {
                guarded {
                    val id = param("id")
                    respondOutcome(queries.receipts(id)) { receipts -> ReceiptsJson(id, receipts.map { it.toJson() }) }
                }
            }
            get("/tickets/latest") { guarded { respondOutcome(queries.latestTicketBy(query("by"))) { it.toJson() } } }
            get("/tickets/{id}") { guarded { respondOutcome(queries.ticket(param("id"))) { it.toJson() } } }
            get("/notifications") {
                guarded { respondOutcome(queries.notifications(query("user"))) { list -> NotificationsJson(list.map { it.toJson() }) } }
            }
            route("{...}") { handle { guarded { call.respond(HttpStatusCode.NotFound, ErrorJson("not_found", "Unknown test endpoint")) } } }
        }
    }

    private suspend fun RoutingContext.seed() {
        val request =
            try {
                json.decodeFromString<SeedRequestJson>(call.receiveText())
            } catch (_: IllegalArgumentException) {
                // Also covers kotlinx.serialization's SerializationException.
                return call.respondError(Failure.INVALID_REQUEST)
            }
        val command =
            SeedRequest(
                companyId = request.companyId,
                departments = request.departments,
                invites = request.invites.map { InviteRequest(it.email, it.name, it.role, it.department) },
            )
        when (val outcome = companies.seed(command, call.publicBaseUrl())) {
            is Outcome.Failed -> {
                call.respondError(outcome.failure)
            }

            is Outcome.Ok -> {
                val result = outcome.value
                call.respond(
                    SeedResponseJson(
                        companyId = result.company.id,
                        code = result.company.code,
                        departments = result.departments,
                        invites = result.inviteLinks.map { (email, link) -> InviteLinkJson(email, link) },
                    ),
                )
            }
        }
    }

    private suspend fun RoutingContext.guarded(block: suspend RoutingContext.() -> Unit) {
        val token = call.request.headers[TOKEN_HEADER]?.toByteArray()
        if (token == null || !MessageDigest.isEqual(token, expectedToken)) {
            call.respond(HttpStatusCode.Unauthorized, ErrorJson("invalid_test_token", "X-Test-Token is missing or wrong"))
            return
        }
        block()
    }

    private suspend inline fun <reified R : Any, T> RoutingContext.respondOutcome(
        outcome: Outcome<T>,
        render: (T) -> R,
    ) {
        when (outcome) {
            is Outcome.Ok -> call.respond(render(outcome.value))
            is Outcome.Failed -> call.respondError(outcome.failure)
        }
    }

    private fun RoutingContext.param(name: String): String = call.parameters[name].orEmpty()

    private fun RoutingContext.query(name: String): String = call.request.queryParameters[name].orEmpty()

    private companion object {
        const val TOKEN_HEADER = "X-Test-Token"
    }
}

internal suspend fun ApplicationCall.respondError(failure: Failure) {
    respond(failure.status, ErrorJson(failure))
}

/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.pre
import kotlinx.html.section
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/**
 * Mailpit-compatible REST API (v1) over [MailOutbox], served on the fake's mail port so Pətək's Mailpit client works
 * unchanged against it. Also serves a tiny HTML inbox at `/` for local demos.
 */
internal class MailpitApi(
    private val outbox: MailOutbox,
    private val json: Json,
) {
    fun install(application: Application) {
        application.install(ContentNegotiation) { json(json) }
        application.install(StatusPages) {
            exception<SerializationException> { call, _ -> call.respondText("invalid request body", status = HttpStatusCode.BadRequest) }
            exception<Throwable> { call, cause ->
                logger.error(cause) { "Mailpit API request failed" }
                call.respondText("internal error", status = HttpStatusCode.InternalServerError)
            }
        }
        application.routing {
            get("/livez") { call.respondText("ok") }
            get("/readyz") { call.respondText("ok") }
            route("/api/v1") { api() }
            get("/") { call.respondInbox() }
            get("/view/{id}") { call.respondMessagePage(call.parameters["id"].orEmpty()) }
        }
    }

    private fun Route.api() {
        get("/info") {
            val all = outbox.messages()
            call.respond(MailpitInfo(version = "v1.31.2-fake", database = "memory", messages = all.size, unread = all.count { !it.read }))
        }
        get("/messages") { call.respond(page(call, outbox.messages())) }
        put("/messages") { call.markRead() }
        put("/read") { call.markRead() }
        delete("/messages") {
            val request = call.decodeBody(MailpitDeleteRequest())
            outbox.delete(request.ids)
            call.respondText("ok")
        }
        get("/search") {
            val query = MailSearchQuery.parse(call.request.queryParameters["query"].orEmpty())
            call.respond(page(call, outbox.messages().filter(query::matches)))
        }
        delete("/search") {
            val query = MailSearchQuery.parse(call.request.queryParameters["query"].orEmpty())
            outbox.delete(outbox.messages().filter(query::matches).map { it.id })
            call.respondText("ok")
        }
        get("/message/{id}") {
            val mail = findForView(call.parameters["id"].orEmpty())
            if (mail == null) {
                call.respondText("message not found", status = HttpStatusCode.NotFound)
            } else {
                call.respond(mail.toMessage())
            }
        }
    }

    /** Like Mailpit, opening a message marks it read. */
    private fun findForView(id: String): SentMail? {
        val mail = (if (id == "latest") outbox.latest() else outbox.find(id)) ?: return null
        outbox.setRead(listOf(mail.id), read = true)
        return mail.copy(read = true)
    }

    private suspend fun ApplicationCall.markRead() {
        val request = decodeBody(MailpitReadRequest())
        val ids =
            if (request.ids.isEmpty() && request.search.isNotBlank()) {
                val query = MailSearchQuery.parse(request.search)
                outbox
                    .messages()
                    .filter(query::matches)
                    .map { it.id }
                    .ifEmpty { return respondText("ok") }
            } else {
                request.ids
            }
        outbox.setRead(ids, request.read)
        respondText("ok")
    }

    private suspend inline fun <reified T : Any> ApplicationCall.decodeBody(default: T): T {
        val text = receiveText()
        return if (text.isBlank()) default else json.decodeFromString<T>(text)
    }

    private fun page(
        call: ApplicationCall,
        matching: List<SentMail>,
    ): MailpitList {
        val all = outbox.messages()
        val start =
            call.request.queryParameters["start"]
                ?.toIntOrNull()
                ?.coerceAtLeast(0) ?: 0
        val limit =
            call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.takeIf { it > 0 } ?: DEFAULT_LIMIT
        val window = matching.drop(start).take(limit)
        return MailpitList(
            total = all.size,
            unread = all.count { !it.read },
            count = window.size,
            messagesCount = matching.size,
            messagesUnread = matching.count { !it.read },
            start = start,
            messages = window.map { it.toSummary() },
        )
    }

    private suspend fun ApplicationCall.respondInbox() {
        val messages = outbox.messages()
        respondHtml {
            head {
                meta(charset = "utf-8")
                title("Fake Mailpit")
            }
            body {
                h1 { +"Fake Mailpit (${messages.size})" }
                table {
                    tr {
                        th { +"Kimə" }
                        th { +"Mövzu" }
                        th { +"Tarix" }
                    }
                    messages.forEach { mail ->
                        tr {
                            td { +mail.to.joinToString { it.address } }
                            td { a(href = "/view/${mail.id}") { +mail.subject } }
                            td { +mail.createdAt.toString() }
                        }
                    }
                }
            }
        }
    }

    private suspend fun ApplicationCall.respondMessagePage(id: String) {
        val mail = findForView(id)
        if (mail == null) {
            respondText("message not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return
        }
        respondHtml {
            head {
                meta(charset = "utf-8")
                title(mail.subject)
            }
            body {
                p { a(href = "/") { +"← Bütün məktublar" } }
                h1 { +mail.subject }
                p { +"Kimə: ${mail.to.joinToString { it.address }}" }
                pre { +mail.text }
                // The HTML part is produced by the fake itself with every dynamic value escaped.
                section { unsafe { raw(mail.html) } }
            }
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val SNIPPET_LENGTH = 250
        val WHITESPACE = Regex("\\s+")

        fun MailAddress.toJson() = MailpitAddress(name, address)

        fun SentMail.size(): Int = text.toByteArray().size + html.toByteArray().size

        fun SentMail.toSummary() =
            MailpitSummary(
                id = id,
                messageId = messageId,
                read = read,
                from = from.toJson(),
                to = to.map { it.toJson() },
                subject = subject,
                created = createdAt.toString(),
                size = size(),
                snippet = text.replace(WHITESPACE, " ").trim().take(SNIPPET_LENGTH),
            )

        fun SentMail.toMessage() =
            MailpitMessage(
                id = id,
                messageId = messageId,
                from = from.toJson(),
                to = to.map { it.toJson() },
                returnPath = from.address,
                subject = subject,
                date = createdAt.toString(),
                text = text,
                html = html,
                size = size(),
            )
    }
}

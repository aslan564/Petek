/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.notes

import az.petek.faketarget.store.PasswordHash
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.textArea
import kotlinx.html.title
import kotlinx.html.ul
import java.net.URI
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/** Deliberate defects of [FakeNotesServer], for tests that must prove Pətək notices them. */
enum class NotesBug {
    /** `/notes/{id}` shows a note to anyone signed in, not only to its author (a direct-URL permission hole). */
    FOREIGN_NOTE_VISIBLE,
}

/**
 * The second fake site (docs/PLAN.md Faza 13): a plain notes application with no companies, no roles and no test API.
 * Anyone signs up with name, e-mail and password and is signed in at once; a signed-in user writes notes and reads
 * their own. It keeps the contract's sign-up, login and session `data-testid`s (`register-*`, `login-*`,
 * `current-user-name`, `logout`), so the default `sign_up` and `login` flows work, and adds `note-title`, `note-body`,
 * `note-submit`, `note-item` and `note-view`. Pətək's own e2e tests use it to prove that a campaign with
 * `tenant: none` and no oracle runs end to end. Loopback only, state in memory.
 */
class FakeNotesServer(
    private val bugs: Set<NotesBug> = emptySet(),
) : AutoCloseable {
    private val random = SecureRandom()
    private val users = ConcurrentHashMap<String, User>()
    private val sessions = ConcurrentHashMap<String, String>()
    private val notes = ConcurrentHashMap<Long, Note>()
    private val nextNote = AtomicLong(1)
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    private class User(
        val name: String,
        val email: String,
        val password: PasswordHash,
    )

    /** A note as stored; [author] is the author's e-mail. */
    data class Note(
        val id: Long,
        val author: String,
        val title: String,
        val body: String,
    )

    /** Where it listens, e.g. `http://127.0.0.1:18090`. Only valid after [start]. */
    lateinit var baseUrl: URI
        private set

    /** Every note written so far, for test assertions. */
    val allNotes: List<Note> get() = notes.values.sortedBy { it.id }

    /** How many accounts exist, for test assertions. */
    val accounts: Int get() = users.size

    /** An account that exists before a test, as the owner's own test account would. */
    fun seedAccount(
        name: String,
        email: String,
        password: String,
    ) {
        users[email.lowercase()] = User(name, email.lowercase(), PasswordHash.of(password, random))
    }

    /** A note of [author] (an account that need not exist), written before a test; returns its id. */
    fun seedNote(
        author: String,
        title: String,
    ): Long {
        val id = nextNote.getAndIncrement()
        notes[id] = Note(id, author.lowercase(), title, "")
        return id
    }

    fun start(port: Int = 0): FakeNotesServer {
        check(server == null) { "FakeNotesServer can be started only once" }
        val engine = embeddedServer(CIO, port = port, host = HOST) { routes(this) }.start(wait = false)
        server = engine
        val bound =
            runBlocking {
                engine.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
        baseUrl = URI("http://$HOST:$bound")
        logger.info { "Fake notes site on $baseUrl (bugs: $bugs)" }
        return this
    }

    override fun close() {
        server?.stop(GRACE_MILLIS, TIMEOUT_MILLIS)
    }

    private fun routes(application: io.ktor.server.application.Application) {
        application.routing {
            get("/") {
                val user = call.user()
                call.respondHtml { if (user == null) welcome() else home(user) }
            }
            get("/register") { call.respondHtml { registerPage(null) } }
            post("/register") {
                val params = call.receiveParameters()
                val name = params["name"].orEmpty().trim()
                val email = params["email"].orEmpty().trim().lowercase()
                val password = params["password"].orEmpty()
                val problem =
                    when {
                        name.isEmpty() -> "Ad boş ola bilməz."
                        !EMAIL.matches(email) -> "E-poçt ünvanı düzgün deyil."
                        password.length < MIN_PASSWORD -> "Parol ən azı $MIN_PASSWORD simvol olmalıdır."
                        else -> null
                    }
                if (problem != null) return@post call.respondHtml(HttpStatusCode.UnprocessableEntity) { registerPage(problem) }
                val user = User(name, email, PasswordHash.of(password, random))
                if (users.putIfAbsent(email, user) != null) {
                    return@post call.respondHtml(HttpStatusCode.Conflict) { registerPage("Bu e-poçtla hesab artıq var.") }
                }
                call.signIn(email)
            }
            get("/login") { call.respondHtml { loginPage(null) } }
            post("/login") {
                val params = call.receiveParameters()
                val email = params["email"].orEmpty().trim().lowercase()
                val user = users[email]
                if (user == null || !user.password.matches(params["password"].orEmpty())) {
                    return@post call.respondHtml(HttpStatusCode.Unauthorized) { loginPage("E-poçt və ya parol yanlışdır.") }
                }
                call.signIn(email)
            }
            post("/logout") {
                call.request.cookies[SESSION_COOKIE]?.let(sessions::remove)
                call.response.cookies.append(Cookie(SESSION_COOKIE, "", maxAge = 0, path = "/"))
                call.respondRedirect("/login")
            }
            post("/notes") {
                val user = call.user() ?: return@post call.respondRedirect("/login")
                val params = call.receiveParameters()
                val title = params["title"].orEmpty().trim()
                if (title.isEmpty()) {
                    return@post call.respondHtml(
                        HttpStatusCode.UnprocessableEntity,
                    ) { home(user, "Başlıq boş ola bilməz.") }
                }
                val id = nextNote.getAndIncrement()
                notes[id] = Note(id, user.email, title, params["body"].orEmpty().trim())
                call.respondRedirect("/notes/$id")
            }
            get("/notes/{id}") {
                val user = call.user() ?: return@get call.respondRedirect("/login")
                val note = call.parameters["id"]?.toLongOrNull()?.let(notes::get)
                val visible = note != null && (note.author == user.email || NotesBug.FOREIGN_NOTE_VISIBLE in bugs)
                if (!visible) return@get call.respondHtml(HttpStatusCode.NotFound) { notFound(user) }
                call.respondHtml { notePage(user, checkNotNull(note)) }
            }
        }
    }

    private fun ApplicationCall.user(): User? = request.cookies[SESSION_COOKIE]?.let(sessions::get)?.let(users::get)

    private suspend fun ApplicationCall.signIn(email: String) {
        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).let(HexFormat.of()::formatHex)
        sessions[token] = email
        response.cookies.append(Cookie(SESSION_COOKIE, token, path = "/", httpOnly = true))
        respondRedirect("/")
    }

    private fun HTML.page(
        title: String,
        user: User?,
        content: FlowContent.() -> Unit,
    ) {
        attributes["lang"] = "az"
        head {
            meta(charset = "utf-8")
            title("$title · Qeydlər")
        }
        body {
            div {
                a(href = "/") { +"Qeydlər" }
                if (user != null) {
                    +" · "
                    span {
                        attributes["data-testid"] = "current-user-name"
                        +user.name
                    }
                    form(action = "/logout", method = FormMethod.post) {
                        button {
                            attributes["data-testid"] = "logout"
                            +"Çıxış"
                        }
                    }
                }
            }
            content()
        }
    }

    private fun FlowContent.field(
        text: String,
        id: String,
        name: String,
        type: InputType = InputType.text,
    ) {
        div {
            label {
                htmlFor = id
                +text
            }
            input(type = type, name = name) {
                attributes["id"] = id
                attributes["data-testid"] = id
            }
        }
    }

    private fun FlowContent.error(
        id: String,
        message: String?,
    ) {
        if (message != null) {
            p {
                attributes["data-testid"] = id
                attributes["role"] = "alert"
                +message
            }
        }
    }

    private fun HTML.welcome() =
        page("Xoş gəldiniz", null) {
            h1 { +"Qeydlər" }
            p { +"Qeydlərinizi bir yerdə saxlayın." }
            a(href = "/register") { +"Qeydiyyat" }
            +" · "
            a(href = "/login") { +"Daxil ol" }
        }

    private fun HTML.registerPage(message: String?) =
        page("Qeydiyyat", null) {
            h1 { +"Qeydiyyat" }
            error("register-error", message)
            form(action = "/register", method = FormMethod.post) {
                field("Ad", "register-name", "name")
                field("E-poçt", "register-email", "email", InputType.email)
                field("Parol", "register-password", "password", InputType.password)
                button {
                    attributes["data-testid"] = "register-submit"
                    +"Qeydiyyatdan keç"
                }
            }
            a(href = "/login") { +"Hesabınız var? Daxil olun" }
        }

    private fun HTML.loginPage(message: String?) =
        page("Daxil ol", null) {
            h1 { +"Daxil ol" }
            error("login-error", message)
            form(action = "/login", method = FormMethod.post) {
                field("E-poçt", "login-email", "email", InputType.email)
                field("Parol", "login-password", "password", InputType.password)
                button {
                    attributes["data-testid"] = "login-submit"
                    +"Daxil ol"
                }
            }
            a(href = "/register") { +"Qeydiyyat" }
        }

    private fun HTML.home(
        user: User,
        message: String? = null,
    ) = page("Qeydlərim", user) {
        h1 { +"Qeydlərim" }
        error("note-error", message)
        form(action = "/notes", method = FormMethod.post) {
            field("Başlıq", "note-title", "title")
            div {
                label {
                    htmlFor = "note-body"
                    +"Mətn"
                }
                textArea {
                    attributes["id"] = "note-body"
                    name = "body"
                    attributes["data-testid"] = "note-body"
                }
            }
            button {
                attributes["data-testid"] = "note-submit"
                +"Yadda saxla"
            }
        }
        ul {
            notes.values.filter { it.author == user.email }.sortedBy { it.id }.forEach { note ->
                li {
                    attributes["data-testid"] = "note-item"
                    a(href = "/notes/${note.id}") { +note.title }
                }
            }
        }
    }

    private fun HTML.notePage(
        user: User,
        note: Note,
    ) = page(note.title, user) {
        div {
            attributes["data-testid"] = "note-view"
            h1 { +note.title }
            p { +note.body }
        }
        a(href = "/") { +"Qeydlərim" }
    }

    private fun HTML.notFound(user: User) =
        page("Tapılmadı", user) {
            h1 { +"Qeyd tapılmadı" }
        }

    private companion object {
        const val HOST = "127.0.0.1"
        const val SESSION_COOKIE = "notes_session"
        const val TOKEN_BYTES = 24
        const val MIN_PASSWORD = 8
        const val GRACE_MILLIS = 100L
        const val TIMEOUT_MILLIS = 2_000L
        val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
    }
}

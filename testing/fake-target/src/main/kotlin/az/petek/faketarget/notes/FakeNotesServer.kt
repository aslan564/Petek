/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.faketarget.notes

import az.petek.faketarget.store.PasswordHash
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
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
import io.ktor.server.response.respondText
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
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.textArea
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/** Deliberate defects of [FakeNotesServer], for tests that must prove Pətək notices them. */
enum class NotesBug {
    /** `/notes/{id}` shows a note to anyone signed in, not only to its author (a direct-URL permission hole). */
    FOREIGN_NOTE_VISIBLE,

    /** The welcome page links to a help page that does not exist (a dead link every visitor meets). */
    DEAD_LINK,

    /**
     * The welcome page's sign-up link turns into a tall block 40 px lower: a defect only the page's look shows (the
     * link still works), for comparing releases by their looks (docs/adr/0014).
     */
    SHIFTED_SIGN_UP,
}

/**
 * The second fake site (docs/PLAN.md Faza 13): a plain notes application with no companies and no roles. Anyone signs
 * up with name, e-mail and password and is signed in at once; a signed-in user writes notes and reads their own. It
 * keeps the contract's sign-up, login and session `data-testid`s (`register-*`, `login-*`, `current-user-name`,
 * `logout`), so the default `sign_up` and `login` flows work, and adds `note-title`, `note-body`, `note-submit`,
 * `note-item` and `note-view`. Pətək's own e2e tests use it to prove that a campaign with `tenant: none` runs end to
 * end without an oracle, and, with [testToken], that a site with a test API but no companies is tested from its
 * exploration to its report (Faza 25): then `GET /test/notes/latest?by=<e-mail>` and `GET /test/notes/{id}` answer
 * with the note as JSON for the `X-Test-Token` header, and nothing else of the contract's test API exists. Loopback
 * only, state in memory.
 *
 * With [liveClock] the welcome page shows what changes by itself on a real site: a clock ticking every 200 ms and
 * today's date. [deploy] swaps the defects while the site keeps running, as a new release on the same address.
 */
class FakeNotesServer(
    bugs: Set<NotesBug> = emptySet(),
    /** The test API's token; null: the site has no test API. */
    private val testToken: String? = null,
    private val liveClock: Boolean = false,
) : AutoCloseable {
    @Volatile
    private var bugs: Set<NotesBug> = bugs
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

    /** A new release of the site on the same address: from now on it has [bugs], and its accounts and notes stay. */
    fun deploy(bugs: Set<NotesBug>) {
        this.bugs = bugs
        logger.info { "Fake notes site deployed again (bugs: $bugs)" }
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
            if (testToken != null) testApi(this, testToken)
        }
    }

    /** The test API of a site that has one: its notes as JSON, for the `X-Test-Token` header only. */
    private fun testApi(
        routing: io.ktor.server.routing.Routing,
        token: String,
    ) {
        routing.get("/test/notes/latest") {
            if (call.request.headers[TOKEN_HEADER] != token) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
            val author =
                call.request.queryParameters["by"]
                    .orEmpty()
                    .trim()
                    .lowercase()
            val note = notes.values.filter { it.author == author }.maxByOrNull { it.id }
            if (note == null) return@get call.respondText("", status = HttpStatusCode.NotFound)
            call.respondText(json(note), ContentType.Application.Json)
        }
        routing.get("/test/notes/{id}") {
            if (call.request.headers[TOKEN_HEADER] != token) return@get call.respondText("", status = HttpStatusCode.Unauthorized)
            val note = call.parameters["id"]?.toLongOrNull()?.let(notes::get)
            if (note == null) return@get call.respondText("", status = HttpStatusCode.NotFound)
            call.respondText(json(note), ContentType.Application.Json)
        }
    }

    private fun json(note: Note): String =
        buildJsonObject {
            put("id", note.id)
            put("author", note.author)
            put("title", note.title)
            put("body", note.body)
        }.toString()

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
            meta(name = "description", content = "Qeydlərinizi bir yerdə saxlayın.")
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
            if (liveClock) {
                p {
                    attributes["data-testid"] = "welcome-today"
                    +"Bu gün: ${LocalDate.now().format(DAY)}"
                }
                p {
                    attributes["data-testid"] = "welcome-clock"
                    +LocalTime.now().format(TIME)
                }
                script {
                    unsafe {
                        raw(
                            "setInterval(function(){var d=new Date(),p=function(n){return String(n).padStart(2,'0')};" +
                                "document.querySelector('[data-testid=welcome-clock]').textContent=" +
                                "p(d.getHours())+':'+p(d.getMinutes())+':'+p(d.getSeconds())},200);",
                        )
                    }
                }
            }
            a(href = "/register") {
                if (NotesBug.SHIFTED_SIGN_UP in bugs) {
                    attributes["style"] =
                        "display:block;width:240px;height:48px;line-height:48px;margin-top:40px;text-align:center;" +
                        "background:#1f6feb;color:#fff;border-radius:8px"
                }
                +"Qeydiyyat"
            }
            +" · "
            a(href = "/login") { +"Daxil ol" }
            if (NotesBug.DEAD_LINK in bugs) {
                +" · "
                a(href = "/help") { +"Kömək" }
            }
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
        const val TOKEN_HEADER = "X-Test-Token"
        const val SESSION_COOKIE = "notes_session"
        const val TOKEN_BYTES = 24
        const val MIN_PASSWORD = 8
        const val GRACE_MILLIS = 100L
        const val TIMEOUT_MILLIS = 2_000L
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
    }
}

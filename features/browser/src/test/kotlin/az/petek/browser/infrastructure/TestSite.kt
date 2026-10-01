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

package az.petek.browser.infrastructure

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The tokens of [TestSite]'s token app, a page that signs its calls with a token instead of a cookie. */
internal object TokenApp {
    /** The access token the page keeps in localStorage and sends as `Authorization: Bearer …`. */
    const val TOKEN = "tok-leyla-7f3a9c2e"

    /** The CSRF token the page sends with its calls. */
    const val CSRF = "csrf-5d1e8b40"
}

/**
 * A small local website for the real-browser tests (no internet): forms, a cookie login, SSE, polling, a WebSocket
 * attempt, a slow page, a ticket that can be approved only once (form post: 303, then 409; JSON API: 200, then
 * 409) and pages to take looks of (`/look/...`). Pages are plain HTML with inline scripts so that what the browser does
 * is obvious.
 */
internal class TestSite : AutoCloseable {
    /** Ticket ids already approved; each test uses its own id. */
    private val approved = ConcurrentHashMap.newKeySet<String>()

    /** How often the site's home page was asked for. */
    val homeRequests = AtomicInteger()

    /** How many writes reached `/api/write` (a page elsewhere posts there with `/write-elsewhere?to=`). */
    val writes = AtomicInteger()

    /** How often the lazy image far below the fold of `/look/lazy` was asked for. */
    val lazyImages = AtomicInteger()

    /** How often `/look/random` was asked for: each answer shows another number. */
    private val randomLoads = AtomicInteger()

    /** The `/look/once/{how}` addresses already answered once. */
    private val answeredOnce = ConcurrentHashMap.newKeySet<String>()

    private val server =
        embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(SSE)
            routing {
                get("/") {
                    homeRequests.incrementAndGet()
                    call.respondText(HOME_PAGE, ContentType.Text.Html)
                }
                get("/form") { call.respondText(FORM_PAGE, ContentType.Text.Html) }
                get("/session-login") { call.respondText(SESSION_LOGIN_PAGE, ContentType.Text.Html) }
                get("/session-me") { call.respondText(SESSION_ME_PAGE, ContentType.Text.Html) }
                get("/health") { call.respondText(HEALTH_PAGE, ContentType.Text.Html) }
                get("/facts") { call.respondText(FACTS_PAGE, ContentType.Text.Html) }
                get("/api/broken") { call.respondText("boom", status = HttpStatusCode.InternalServerError) }
                get("/token-app") { call.respondText(TOKEN_APP_PAGE, ContentType.Text.Html) }
                get("/api/token-me") {
                    if (call.request.headers["Authorization"] != "Bearer ${TokenApp.TOKEN}") {
                        return@get call.respondText("anonymous", status = HttpStatusCode.Unauthorized)
                    }
                    call.respondText("leyla signed in with ${TokenApp.TOKEN}")
                }
                post("/api/token-admin") {
                    when {
                        call.request.headers["Authorization"] != "Bearer ${TokenApp.TOKEN}" -> {
                            call.respondText("anonymous", status = HttpStatusCode.Unauthorized)
                        }

                        call.request.headers["X-CSRF-Token"] != TokenApp.CSRF -> {
                            call.respondText("no csrf token", status = HttpStatusCode.fromValue(CSRF_MISSING))
                        }

                        else -> {
                            call.respondText("leyla (${TokenApp.TOKEN}) may not do this", status = HttpStatusCode.Forbidden)
                        }
                    }
                }
                get("/dynamic") { call.respondText(DYNAMIC_PAGE, ContentType.Text.Html) }
                get("/secret") {
                    val submitted = call.request.queryParameters["password"] != null
                    val page = if (submitted) SECRET_PAGE.replace("<body>", "<body><p>Forma göndərildi</p>") else SECRET_PAGE
                    call.respondText(page, ContentType.Text.Html)
                }
                get("/login") { call.respondText(LOGIN_PAGE, ContentType.Text.Html) }
                get("/do-login") {
                    val user = call.request.queryParameters["user"].orEmpty()
                    call.response.cookies.append("session", user, path = "/")
                    call.respondRedirect("/me")
                }
                get("/me") {
                    val user = call.request.cookies["session"]
                    call.respondText(mePage(user), ContentType.Text.Html)
                }
                get("/api/me") {
                    val user = call.request.cookies["session"]
                    if (user.isNullOrEmpty()) {
                        call.respondText("anonymous", status = HttpStatusCode.Unauthorized)
                    } else {
                        call.respondText(user)
                    }
                }
                post("/api/echo") {
                    val body = call.receiveText()
                    call.respondText("${call.request.contentType().withoutParameters()}|$body", status = HttpStatusCode.Created)
                }
                get("/redirect") { call.respondRedirect("/me") }
                get("/sse") { call.respondText(SSE_PAGE, ContentType.Text.Html) }
                sse("/events") {
                    delay(300)
                    send(ServerSentEvent(data = "Yeni elan"))
                    awaitCancellation()
                }
                get("/polling") { call.respondText(POLLING_PAGE, ContentType.Text.Html) }
                get("/api/poll") { call.respondText("ok") }
                get("/ws") { call.respondText(WEBSOCKET_PAGE, ContentType.Text.Html) }
                get("/slow") { call.respondText(SLOW_PAGE, ContentType.Text.Html) }
                get("/env") { call.respondText(ENV_PAGE, ContentType.Text.Html) }
                get("/csp") {
                    call.response.headers.append("Content-Security-Policy", "default-src 'self'; script-src 'self'")
                    call.respondText(CSP_PAGE, ContentType.Text.Html)
                }
                get("/csp.js") { call.respondText(CSP_SCRIPT, ContentType.Text.JavaScript) }
                get("/shadow") { call.respondText(SHADOW_PAGE, ContentType.Text.Html) }
                get("/reveal") { call.respondText(REVEAL_PAGE, ContentType.Text.Html) }
                get("/late-shadow") { call.respondText(LATE_SHADOW_PAGE, ContentType.Text.Html) }
                get("/dialogs") { call.respondText(DIALOG_PAGE, ContentType.Text.Html) }
                get("/ticket") { call.respondText(ticketPage(call.request.queryParameters["id"].orEmpty()), ContentType.Text.Html) }
                post("/tickets/{id}/approve") {
                    val id = call.parameters["id"].orEmpty()
                    if (approved.add(id)) {
                        call.response.headers.append("Location", "/ticket?id=$id&approved=1")
                        call.respondText("", status = HttpStatusCode.SeeOther)
                    } else {
                        call.respondText(DECIDED_PAGE, ContentType.Text.Html, HttpStatusCode.Conflict)
                    }
                }
                post("/api/tickets/{id}/approve") {
                    val first = approved.add(call.parameters["id"].orEmpty())
                    call.respondText(
                        if (first) "approved" else "decided",
                        status = if (first) HttpStatusCode.OK else HttpStatusCode.Conflict,
                    )
                }
                put("/api/tickets/{id}") { call.respondText("updated") }
                post("/api/write") {
                    writes.incrementAndGet()
                    call.respondText("written")
                }
                get("/write-elsewhere") { call.respondText(WRITE_ELSEWHERE_PAGE, ContentType.Text.Html) }
                delete("/api/tickets/{id}") { call.respondText("gone", status = HttpStatusCode.Forbidden) }
                lookPages()
            }
        }.start(wait = false)

    /**
     * Pages for looks (`BrowserSession.look`): still, moving, masked, tall, lazy, random and redirected ones, and ones
     * that answer only their first visit (`/look/once/moved`: then a redirect to `/look/still`; `/look/once/refused`:
     * then the same page with 429).
     */
    private fun Routing.lookPages() {
        get("/look/still") { call.respondText(LOOK_STILL_PAGE, ContentType.Text.Html) }
        get("/look/clock") { call.respondText(LOOK_CLOCK_PAGE, ContentType.Text.Html) }
        get("/look/spinner") { call.respondText(LOOK_SPINNER_PAGE, ContentType.Text.Html) }
        get("/look/masks") { call.respondText(lookMasksPage(call.request.local.localPort), ContentType.Text.Html) }
        get("/look/hidden") { call.respondText(LOOK_HIDDEN_PAGE, ContentType.Text.Html) }
        get("/look/frame") { call.respondText(LOOK_FRAME_PAGE, ContentType.Text.Html) }
        get("/look/tall") { call.respondText(LOOK_TALL_PAGE, ContentType.Text.Html) }
        get("/look/screen") { call.respondText(LOOK_SCREEN_PAGE, ContentType.Text.Html) }
        get("/look/lazy") { call.respondText(LOOK_LAZY_PAGE, ContentType.Text.Html) }
        get("/look/lazy.svg") {
            lazyImages.incrementAndGet()
            delay(LAZY_IMAGE_DELAY_MS)
            call.respondText(RED_SQUARE, ContentType.Image.SVG)
        }
        get("/look/random") {
            val number = randomLoads.incrementAndGet() * 7_919 % 100_000
            call.respondText(LOOK_RANDOM_PAGE.replace("NUMBER", number.toString()), ContentType.Text.Html)
        }
        get("/look/wandering") { call.respondText(LOOK_WANDERING_PAGE, ContentType.Text.Html) }
        get("/look/jump") { call.respondText(LOOK_JUMP_PAGE, ContentType.Text.Html) }
        get("/look/once/{how}") {
            val how = call.parameters["how"].orEmpty()
            when {
                answeredOnce.add(how) -> call.respondText(LOOK_STILL_PAGE, ContentType.Text.Html)
                how == "moved" -> call.respondRedirect("/look/still")
                else -> call.respondText(LOOK_STILL_PAGE, ContentType.Text.Html, HttpStatusCode.TooManyRequests)
            }
        }
        get("/look/old") { call.respondRedirect("/look/missing") }
        get("/look/missing") { call.respondText(LOOK_MISSING_PAGE, ContentType.Text.Html, HttpStatusCode.NotFound) }
    }

    val baseUrl: URI =
        runBlocking {
            URI("http://127.0.0.1:${server.engine.resolvedConnectors().first().port}")
        }

    override fun close() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    private companion object {
        const val LAZY_IMAGE_DELAY_MS = 300L

        /** A page that never changes by itself: a heading, a field and, far below, an anchor to land on. */
        val LOOK_STILL_PAGE =
            """
            <!doctype html>
            <html><head><title>Sakit</title><style>body { margin: 0; font: 16px/20px sans-serif }</style></head><body>
            <h1 data-testid="title" style="margin: 0; padding: 20px">Sakit səhifə</h1>
            <p id="intro" style="margin: 0 20px">Heç nə dəyişmir.</p>
            <input id="name" value="Ad" style="margin: 20px">
            <div id="bottom" style="position: absolute; top: 2400px; height: 20px">Son</div>
            </body></html>
            """.trimIndent()

        /** A clock written by script, a tick every 100 ms (`mm:ss`), so every frame of the page differs. */
        val LOOK_CLOCK_PAGE =
            """
            <!doctype html>
            <html><head><title>Saat</title></head><body style="margin: 0">
            <p id="clock" style="position: absolute; left: 20px; top: 40px; margin: 0; font: 16px/20px monospace">00:00</p>
            <script>
              var ticks = 0;
              var clock = document.getElementById('clock');
              setInterval(function () {
                ticks += 1;
                var two = function (n) { return String(n).padStart(2, '0'); };
                clock.textContent = two(Math.floor(ticks / 60) % 24) + ':' + two(ticks % 60);
              }, 100);
            </script>
            </body></html>
            """.trimIndent()

        /** An endless CSS spinner, a fade that ends, and a field focused as the page opens (its caret blinks). */
        val LOOK_SPINNER_PAGE =
            """
            <!doctype html>
            <html><head><title>Fırlanan</title><style>
              @keyframes spin { from { transform: rotate(0deg) } to { transform: rotate(360deg) } }
              @keyframes fade { from { opacity: 0 } to { opacity: 1 } }
              .spinner { position: absolute; left: 40px; top: 40px; width: 40px; height: 40px; border: 6px solid #ccc;
                border-top-color: #06c; border-radius: 50%; animation: spin 1s linear infinite }
              .fade { position: absolute; left: 140px; top: 40px; width: 80px; height: 40px; background: #c30;
                animation: fade 5s ease-in forwards }
              input { position: absolute; left: 40px; top: 120px }
            </style></head><body>
            <div class="spinner"></div><div class="fade"></div><input id="field" autofocus>
            </body></html>
            """.trimIndent()

        /**
         * Everything a look masks, at known places: an owner's selector, a step's selector, `data-petek-mask`, the run's
         * own name, mark and e-mail, dates and a time, a `<time>`, a frame of another origin (localhost against
         * 127.0.0.1) next to one of the site's own, and an element with a test id.
         */
        fun lookMasksPage(port: Int): String =
            """
            <!doctype html>
            <html><head><title>Maskalar</title><style>
              body { margin: 0; font: 16px/20px sans-serif }
              body > * { position: absolute; margin: 0 }
              iframe { border: 0; width: 200px; height: 100px }
            </style></head><body>
            <div class="banner" style="left: 10px; top: 10px; width: 200px; height: 50px; background: #fc0">Reklam</div>
            <div id="promo" style="left: 220px; top: 10px; width: 100px; height: 50px; background: #0cf">Aksiya</div>
            <div data-petek-mask="ticker" style="left: 10px; top: 70px; width: 300px; height: 30px">Xəbər lenti</div>
            <p style="left: 10px; top: 110px">Salam, Leyla Quliyeva</p>
            <p style="left: 10px; top: 140px">Bilet ab12-7 yaradıldı</p>
            <p style="left: 10px; top: 170px">Yeniləndi: 2026-10-01, saat 14:05</p>
            <time style="left: 10px; top: 200px">dünən</time>
            <input value="leyla@portal.example" style="left: 10px; top: 230px; width: 200px; height: 24px">
            <iframe src="http://localhost:$port/look/frame" style="left: 10px; top: 270px"></iframe>
            <iframe src="/look/frame" style="left: 220px; top: 270px"></iframe>
            <p style="display: none">Leyla Quliyeva 2026-10-01</p>
            <div data-testid="footer" style="left: 0; top: 400px; width: 400px; height: 40px">Alt</div>
            </body></html>
            """.trimIndent()

        /**
         * The run's name, dates and times where they have boxes but do not show: a collapsed menu (also a selector's
         * element and a `<time>` there) over a picture, a transparent line, a screen-reader-only text, a scrolled-away
         * line, a `clip-path` that hides all, and collapsed parts of a shadow root (its own text and a slotted one). Shown
         * are a date half cut by its box, one positioned out of a collapsed box that is not its containing block, and the
         * greeting at the bottom.
         */
        val LOOK_HIDDEN_PAGE =
            """
            <!doctype html>
            <html><head><title>Gizli</title><style>
              body { margin: 0; font: 16px/20px sans-serif }
              body > * { position: absolute; left: 10px; margin: 0 }
              .shut { height: 0; overflow: hidden }
            </style></head><body>
            <header style="top: 0; width: 600px; height: 20px">
              <ul class="shut" style="max-height: 0; height: auto; margin: 0; padding: 0; list-style: none">
                <li class="who">Daxil olub: Leyla Quliyeva</li>
                <li>Son giriş 2026-10-01 14:05 <time>dünən</time></li>
              </ul>
            </header>
            <div id="hero" style="top: 20px; width: 600px; height: 200px; background: #36c"></div>
            <p style="top: 240px; opacity: 0">Yeniləndi 2026-09-30</p>
            <p style="top: 280px"><span style="position: absolute; width: 1px; height: 1px; overflow: hidden;
              clip: rect(0 0 0 0); white-space: nowrap">Leyla Quliyeva</span>Qiymət</p>
            <div style="top: 320px; width: 300px; height: 10px; overflow: hidden">Son baxış 2026-09-29</div>
            <section style="top: 360px; width: 400px; height: 60px">
              <div class="shut"><p style="position: absolute; left: 10px; top: 20px; margin: 0">Görüş 2026-10-02</p></div>
            </section>
            <div style="top: 440px; width: 200px; height: 20px; overflow: auto"><p style="margin: 0; padding-top: 40px">2026-10-03</p></div>
            <div style="top: 500px; width: 300px; clip-path: inset(50%)">Saat 09:15</div>
            <div id="host" style="top: 540px; width: 300px; height: 40px"><span>2026-10-04</span></div>
            <p style="top: 600px">Salam, Leyla Quliyeva</p>
            <script>
              document.getElementById('host').attachShadow({ mode: 'open' }).innerHTML =
                '<div class="x" style="height: 0; overflow: hidden">Leyla Quliyeva <slot></slot></div>';
            </script>
            </body></html>
            """.trimIndent()

        const val LOOK_FRAME_PAGE = "<!doctype html><html><body style='margin: 0; background: #9c9'>Çərçivə</body></html>"

        /** 3000 px of three colour bands, 1000 px each. */
        val LOOK_TALL_PAGE =
            """
            <!doctype html>
            <html><head><title>Uzun</title></head><body style="margin: 0">
            <div style="height: 1000px; background: #e33"></div>
            <div style="height: 1000px; background: #3e3"></div>
            <div style="height: 1000px; background: #33e"></div>
            </body></html>
            """.trimIndent()

        /** A first band one screen high (`100vh`), then 2000 px of another colour. */
        val LOOK_SCREEN_PAGE =
            """
            <!doctype html>
            <html><head><title>Ekran</title></head><body style="margin: 0">
            <div style="height: 100vh; background: #33e"></div>
            <div style="height: 2000px; background: #e33"></div>
            </body></html>
            """.trimIndent()

        /** A lazy image 6000 px down, answered after [LAZY_IMAGE_DELAY_MS]: the browser asks for it only near the screen. */
        val LOOK_LAZY_PAGE =
            """
            <!doctype html>
            <html><head><title>Tənbəl</title></head><body style="margin: 0">
            <div style="height: 6000px">Uzun səhifə</div>
            <img loading="lazy" src="/look/lazy.svg" width="100" height="100" style="display: block" alt="Qırmızı">
            </body></html>
            """.trimIndent()

        const val RED_SQUARE =
            "<svg xmlns='http://www.w3.org/2000/svg' width='100' height='100'><rect width='100' height='100' fill='#f00'/></svg>"

        /** Another number on every load. */
        val LOOK_RANDOM_PAGE =
            """
            <!doctype html>
            <html><head><title>Təsadüfi</title></head><body style="margin: 0; font: 32px/40px sans-serif">
            <p style="margin: 20px">Bu dəfə: NUMBER</p>
            </body></html>
            """.trimIndent()

        /** Goes to another address of its own every 200 ms (`history.pushState`), as a slideshow that names each slide. */
        val LOOK_WANDERING_PAGE =
            """
            <!doctype html>
            <html><head><title>Gəzən</title></head><body>
            <p>Slayd</p>
            <script>
              var slide = 0;
              setInterval(function () { slide += 1; history.pushState(null, '', '/look/wandering/' + slide); }, 200);
            </script>
            </body></html>
            """.trimIndent()

        /** Goes to `/look/still` as soon as its field, focused as the page opens, loses the focus. */
        val LOOK_JUMP_PAGE =
            """
            <!doctype html>
            <html><head><title>Keçid</title></head><body>
            <input id="field" autofocus onblur="location.href = '/look/still'">
            </body></html>
            """.trimIndent()

        val LOOK_MISSING_PAGE =
            """
            <!doctype html>
            <html><head><title>Tapılmadı</title></head><body><h1>Səhifə tapılmadı</h1></body></html>
            """.trimIndent()

        /** What the token app answers a write without its CSRF token with (as some frameworks do). */
        const val CSRF_MISSING = 419

        /**
         * A single-page application that signs its calls with a token it keeps in localStorage, never a cookie: the
         * page asks `/api/token-me` with `Authorization: Bearer …` and an `X-CSRF-Token`, and shows the answer.
         */
        const val TOKEN_APP_PAGE =
            "<!doctype html><html><head><title>Token</title></head><body><p id='who'>...</p><script>" +
                "localStorage.setItem('token', '${TokenApp.TOKEN}');" +
                "fetch('/api/token-me', {headers: {'Authorization': 'Bearer ' + localStorage.getItem('token'), " +
                "'X-CSRF-Token': '${TokenApp.CSRF}'}})" +
                ".then(r => r.text()).then(t => { document.getElementById('who').textContent = t; });" +
                "</script></body></html>"

        /** Posts to the address in `?to=` as the page loads and says whether the request went out. */
        const val WRITE_ELSEWHERE_PAGE =
            "<!doctype html><html><head><title>Yazı</title></head><body><p id='state'>...</p><script>" +
                "const to = new URLSearchParams(location.search).get('to');" +
                "fetch(to, {method: 'POST', mode: 'no-cors', body: 'x'})" +
                ".then(() => { document.getElementById('state').textContent = 'sent'; })" +
                ".catch(() => { document.getElementById('state').textContent = 'blocked'; });" +
                "</script></body></html>"

        const val HOME_PAGE = "<!doctype html><html><head><title>Ana səhifə</title></head><body><p>Ana səhifə</p></body></html>"

        /** A single-page application's login: the user is kept in sessionStorage only. */
        const val SESSION_LOGIN_PAGE =
            """
            <!doctype html><html><head><title>Giriş</title></head><body>
            <p id="done"></p>
            <script>
              sessionStorage.setItem('user', new URLSearchParams(location.search).get('user') || '');
              document.getElementById('done').textContent = 'Yadda saxlandı';
            </script>
            </body></html>
            """

        /** Greets the user of sessionStorage; logging out clears it. */
        const val SESSION_ME_PAGE =
            """
            <!doctype html><html><head><title>Profil</title></head><body>
            <p id="who"></p>
            <button id="logout" onclick="sessionStorage.clear(); location.reload()">Çıxış</button>
            <script>
              const user = sessionStorage.getItem('user');
              document.getElementById('who').textContent = user ? 'Salam, ' + user : 'Anonim';
            </script>
            </body></html>
            """

        /**
         * What a visitor checks by reading (page_checks): two main headings, a missing anchor, a broken image, one without
         * alt, and its language versions.
         */
        const val FACTS_PAGE =
            """
            <!doctype html><html lang="az"><head><title>Fakt səhifəsi</title><meta name="description" content="Pətək sınağı">
            <link rel="alternate" hreflang="az" href="/facts"><link rel="alternate" hreflang="en" href="/en/facts">
            <link rel="stylesheet" href="data:text/css,"></head>
            <body><h1>Başlıq</h1><h1>İkinci başlıq</h1>
            <a href="#var">Var</a> <a href="#yoxdur">Yoxdur</a> <a href="https://example.org/x">Kənar</a>
            <section id="var">Bölmə</section>
            <img src="/missing.png" alt="Qırıq"> <img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7">
            </body></html>
            """

        /** A page that logs an error, calls a failing endpoint, links in and out of the site and is too wide for a phone. */
        const val HEALTH_PAGE =
            """
            <!doctype html><html><body>
            <a href="/me">Profil</a> <a href="/missing?x=1">Yoxdur</a> <a href="https://example.org/">Kənar</a>
            <a href="mailto:a@b.example">Poçt</a> <a href="#top">Yuxarı</a>
            <div style="width: 2000px">Geniş</div>
            <script>console.error("Pətək sınağı"); fetch("/api/broken");</script>
            </body></html>
            """

        /** A ticket with a form post, API calls by `fetch` and one read; `#result` shows each call's status. */
        fun ticketPage(id: String): String =
            """
            <!doctype html>
            <html><head><title>Bilet $id</title></head><body>
            <form method="post" action="/tickets/$id/approve?token=abc"><button id="approve">Təsdiqlə</button></form>
            <button id="api" onclick="call('POST', '/api/tickets/$id/approve')">API ilə təsdiqlə</button>
            <button id="update" onclick="call('PUT', '/api/tickets/$id')">Yenilə</button>
            <button id="delete" onclick="call('DELETE', '/api/tickets/$id')">Sil</button>
            <button id="read" onclick="call('GET', '/api/me')">Oxu</button>
            <p id="result"></p>
            <script>
              var calls = 0;
              function call(method, path) {
                fetch(path, { method: method }).then(function (response) {
                  calls += 1;
                  document.getElementById('result').textContent = 'call ' + calls + ': ' + method + ' ' + response.status;
                });
              }
            </script>
            </body></html>
            """.trimIndent()

        val DECIDED_PAGE =
            """
            <!doctype html>
            <html><head><title>Xəta</title></head><body><p>Bu müraciət artıq qərarlaşdırılıb</p></body></html>
            """.trimIndent()

        val DIALOG_PAGE =
            """
            <!doctype html>
            <html><head><title>Dialoqlar</title></head><body>
            <button id="confirm" onclick="answer(confirm('Bileti silək?') ? 'silindi' : 'saxlanıldı')">Sil</button>
            <button id="prompt" onclick="answer('ad=' + prompt('Adınız?', 'Əli'))">Ad</button>
            <button id="alert" onclick="alert('Yadda saxlandı'); answer('bağlandı')">Saxla</button>
            <label>Şifrə <input id="pw" type="password"></label>
            <button id="echo" onclick="alert('Şifrəniz: ' + document.getElementById('pw').value); answer('göstərildi')">Göstər</button>
            <p id="answer"></p>
            <script>
              function answer(text) { document.getElementById('answer').textContent = text; }
            </script>
            </body></html>
            """.trimIndent()

        val FORM_PAGE =
            """
            <!doctype html>
            <html><head><title>Forma</title></head><body>
            <h1>Qeydiyyat</h1>
            <form id="f">
              <label for="name">Ad</label><input id="name" name="name" data-testid="name-input">
              <input id="email" type="email" placeholder="E-poçt">
              <label>Şifrə <input id="password" type="password"></label>
              <select id="dept" aria-label="Şöbə">
                <option value="">—</option>
                <option value="mkt">Marketinq</option>
                <option value="sales">Satış</option>
              </select>
              <textarea id="note" title="Qeyd"></textarea>
              <input type="checkbox" id="agree" aria-labelledby="agree-label"><span id="agree-label">Razıyam</span>
              <input type="hidden" name="csrf" value="token">
              <button type="submit" data-testid="submit">Göndər</button>
              <button type="button" disabled>Deaktiv</button>
              <button type="button" style="display:none">Gizli düymə</button>
            </form>
            <div role="button" aria-label="Menyu" onclick="document.getElementById('result').textContent = 'Menyu açıldı'">☰</div>
            <a href="/me" data-testid="profile-link">Profil</a>
            <p id="result"></p>
            <p style="display:none">Gizli mətn</p>
            <script>
              document.getElementById('f').addEventListener('submit', function (event) {
                event.preventDefault();
                document.getElementById('result').textContent =
                  'Göndərildi: ' + document.getElementById('name').value + ' / ' + document.getElementById('dept').value;
              });
            </script>
            </body></html>
            """.trimIndent()

        val DYNAMIC_PAGE =
            """
            <!doctype html>
            <html><head><title>Dinamik</title></head><body>
            <div id="list"><button id="add" onclick="addButton()">Əlavə et</button></div>
            <script>
              function addButton() {
                var button = document.createElement('button');
                button.textContent = 'Yeni';
                document.getElementById('list').prepend(button);
              }
            </script>
            </body></html>
            """.trimIndent()

        val SECRET_PAGE =
            """
            <!doctype html>
            <html><head><title>Sirr</title></head><body>
            <label>Köhnə şifrə <input id="old" type="password" value="server-rendered-secret"></label>
            <label>Yeni şifrə <input id="new" type="text" autocomplete="new-password"></label>
            <form action="/secret" method="get">
              <label>Şifrə <input id="current" name="password" type="password"></label>
            </form>
            <button id="reveal" onclick="reveal()">Şifrəni göstər</button>
            <p id="echo"></p>
            <script>
              function reveal() {
                var field = document.getElementById('current');
                field.type = 'text';
                document.getElementById('echo').textContent = 'Daxil etdiyiniz şifrə: ' + field.value;
              }
            </script>
            </body></html>
            """.trimIndent()

        val LOGIN_PAGE =
            """
            <!doctype html>
            <html><head><title>Giriş</title></head><body>
            <form action="/do-login" method="get" onsubmit="localStorage.setItem('agent', this.user.value)">
              <input name="user" aria-label="İstifadəçi" data-testid="user">
              <button type="submit" data-testid="login">Daxil ol</button>
            </form>
            </body></html>
            """.trimIndent()

        fun mePage(user: String?): String =
            """
            <!doctype html>
            <html><head><title>Profil</title></head><body>
            <p id="greeting">${if (user.isNullOrEmpty()) "Anonim" else "Salam, $user"}</p>
            <p id="local"></p>
            <script>
              document.getElementById('local').textContent = 'local=' + (localStorage.getItem('agent') || '-');
            </script>
            </body></html>
            """.trimIndent()

        val SSE_PAGE =
            """
            <!doctype html>
            <html><head><title>SSE</title></head><body>
            <ul id="log"></ul>
            <script>
              var source = new EventSource('/events?token=abc');
              source.onmessage = function (event) {
                var item = document.createElement('li');
                item.textContent = event.data;
                document.getElementById('log').appendChild(item);
              };
            </script>
            </body></html>
            """.trimIndent()

        val POLLING_PAGE =
            """
            <!doctype html>
            <html><head><title>Polling</title></head><body>
            <ul id="polls"></ul>
            <script>
              var polls = 0;
              setInterval(function () {
                fetch('/api/poll?since=' + Date.now()).then(function () {
                  polls += 1;
                  var item = document.createElement('li');
                  item.textContent = 'poll ' + polls + ' done';
                  document.getElementById('polls').appendChild(item);
                });
              }, 200);
            </script>
            </body></html>
            """.trimIndent()

        val WEBSOCKET_PAGE =
            """
            <!doctype html>
            <html><head><title>WebSocket</title></head><body>
            <p id="state">connecting</p>
            <script>
              var socket = new WebSocket('ws://' + location.host + '/socket?token=abc');
              socket.onerror = function () { document.getElementById('state').textContent = 'closed'; };
              socket.onclose = function () { document.getElementById('state').textContent = 'closed'; };
            </script>
            </body></html>
            """.trimIndent()

        val ENV_PAGE =
            """
            <!doctype html>
            <html><head><title>Mühit</title></head><body>
            <p id="env"></p>
            <script>
              document.getElementById('env').textContent =
                navigator.language + '|' + Intl.DateTimeFormat().resolvedOptions().timeZone;
            </script>
            </body></html>
            """.trimIndent()

        /** Served with a Content-Security-Policy that forbids inline scripts and `eval`, as hardened sites do. */
        val CSP_PAGE =
            """
            <!doctype html>
            <html><head><title>CSP</title></head><body>
            <p>Salam</p>
            <script src="/csp.js"></script>
            </body></html>
            """.trimIndent()

        val CSP_SCRIPT =
            """
            setTimeout(function () {
              var late = document.createElement('p');
              late.id = 'late';
              late.textContent = 'Gec mətn';
              document.body.appendChild(late);
            }, 300);
            """.trimIndent()

        /** A web component: its text lives in an open shadow root, as in design-system toasts and badges. */
        val SHADOW_PAGE =
            """
            <!doctype html>
            <html><head><title>Kölgə</title></head><body>
            <p>Adi mətn</p>
            <petek-toast message="Kölgədə bildiriş"></petek-toast>
            <petek-toast message="Gizli kölgə" style="display:none"></petek-toast>
            <script>
              customElements.define('petek-toast', class extends HTMLElement {
                connectedCallback() {
                  const root = this.attachShadow({ mode: 'open' });
                  const message = this.getAttribute('message');
                  setTimeout(function () {
                    root.innerHTML = '<style>div { color: teal }</style><div class="toast">' + message + '</div><button>Bağla</button>';
                  }, 300);
                }
              });
            </script>
            </body></html>
            """.trimIndent()

        /** A text that is in the page from the start, hidden, and shown after one second by a style change. */
        val REVEAL_PAGE =
            """
            <!doctype html>
            <html><head><title>Gizli</title></head><body>
            <p id="late" style="display:none">Gizli elan</p>
            <script>
              setTimeout(function () {
                var late = document.getElementById('late');
                late.style.display = 'block';
                late.dataset.shownAt = String(performance.timeOrigin + performance.now());
              }, 1000);
            </script>
            </body></html>
            """.trimIndent()

        /** A shadow root that shows its text after one second: no change in the document itself announces it. */
        val LATE_SHADOW_PAGE =
            """
            <!doctype html>
            <html><head><title>Gec kölgə</title></head><body>
            <petek-late id="host"></petek-late>
            <script>
              customElements.define('petek-late', class extends HTMLElement {
                connectedCallback() {
                  const root = this.attachShadow({ mode: 'open' });
                  setTimeout(function () {
                    const shownAt = String(performance.timeOrigin + performance.now());
                    root.innerHTML = '<div>Kölgədən gələn elan</div><i id="stamp" data-shown-at="' + shownAt + '"></i>';
                  }, 1000);
                }
              });
            </script>
            </body></html>
            """.trimIndent()

        val SLOW_PAGE =
            """
            <!doctype html>
            <html><head><title>Yavaş</title></head><body>
            <p id="status">Gözləyin…</p>
            <script>
              setTimeout(function () {
                var ready = document.createElement('p');
                ready.id = 'ready';
                ready.textContent = 'Hazırdır';
                ready.dataset.shownAt = String(performance.timeOrigin + performance.now());
                document.body.appendChild(ready);
              }, 1000);
            </script>
            </body></html>
            """.trimIndent()
    }
}

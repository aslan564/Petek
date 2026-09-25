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

/**
 * A small local website for the real-browser tests (no internet): forms, a cookie login, SSE, polling, a WebSocket
 * attempt, a slow page and a ticket that can be approved only once (form post: 303, then 409; JSON API: 200, then
 * 409). Pages are plain HTML with inline scripts so that what the browser does is obvious.
 */
internal class TestSite : AutoCloseable {
    /** Ticket ids already approved; each test uses its own id. */
    private val approved = ConcurrentHashMap.newKeySet<String>()

    private val server =
        embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(SSE)
            routing {
                get("/form") { call.respondText(FORM_PAGE, ContentType.Text.Html) }
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
                delete("/api/tickets/{id}") { call.respondText("gone", status = HttpStatusCode.Forbidden) }
            }
        }.start(wait = false)

    val baseUrl: URI =
        runBlocking {
            URI("http://127.0.0.1:${server.engine.resolvedConnectors().first().port}")
        }

    override fun close() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    private companion object {
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

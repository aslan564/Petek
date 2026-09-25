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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.URI

/**
 * A small local website for the real-browser tests (no internet): forms, a cookie login, SSE, polling, a WebSocket
 * attempt and a slow page. Pages are plain HTML with inline scripts so that what the browser does is obvious.
 */
internal class TestSite : AutoCloseable {
    private val server =
        embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(SSE)
            routing {
                get("/form") { call.respondText(FORM_PAGE, ContentType.Text.Html) }
                get("/dynamic") { call.respondText(DYNAMIC_PAGE, ContentType.Text.Html) }
                get("/secret") { call.respondText(SECRET_PAGE, ContentType.Text.Html) }
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
            <label>Şifrə <input id="current" type="password"></label>
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

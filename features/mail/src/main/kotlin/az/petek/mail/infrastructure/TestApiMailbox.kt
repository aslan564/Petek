package az.petek.mail.infrastructure

import az.petek.core.security.Secret
import az.petek.mail.domain.MailMessage
import az.petek.mail.domain.Mailbox
import az.petek.mail.domain.MailboxException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * [Mailbox] over the target's own test API (docs/KADROHR_READINESS.md, P0 item 2): in test mode the target keeps the
 * e-mail it would send to the test domain and returns it, so no Mailpit is needed. Construct it with the test API's
 * base address (a path in it is kept) and `PETEK_TEST_TOKEN`; the app selects it with `PETEK_MAIL_SOURCE=test-api`.
 *
 * - Search: `GET test/emails?to=<address>` with `X-Test-Token`, answering the messages newest first:
 *   `[{"id", "to", "subject", "text", "html", "links": [], "created_at", "read"}]` (`to` may be one address or a
 *   list; unknown keys are ignored). The recipient, `since` (against `created_at`) and the read flag are checked here
 *   as well, so a server that filters loosely cannot hand one tester another tester's code. Links the API lists
 *   separately are appended to the text when the text does not contain them, so the extractor sees every link.
 * - Read state: `POST test/emails/{id}/read`. A target without that endpoint (404 or 405, detected once) is served
 *   from a local set of used message ids instead; used ids are remembered locally in any case, so an old code is never
 *   reused within this process.
 * - Transport failures, timeouts, unexpected statuses and malformed JSON become [MailboxException] naming the action
 *   and the address; the token never appears in a message. Redirects are not followed, so the token only ever goes to
 *   the configured host.
 *
 * Owns (and [close]s) its HTTP client unless one is injected; an injected client keeps its own settings and should not
 * follow redirects.
 */
class TestApiMailbox(
    baseUrl: URI,
    testToken: Secret?,
    client: HttpClient? = null,
    requestTimeout: Duration = 10.seconds,
) : Mailbox,
    AutoCloseable {
    private val token: String? = usableToken(testToken)
    private val base: String = baseOf(baseUrl)
    private val ownsClient = client == null
    private val http: HttpClient = client ?: defaultClient(requestTimeout)
    private val used: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val readEndpointMissing = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        if (token == null) logger.warn { "No test token for the mail test API at $base; its requests will be refused in test mode" }
    }

    override suspend fun findLatest(
        to: String,
        since: Instant,
        unreadOnly: Boolean,
    ): MailMessage? = findRecent(to, since, unreadOnly, limit = 1).firstOrNull()

    override suspend fun findRecent(
        to: String,
        since: Instant,
        unreadOnly: Boolean,
        limit: Int,
    ): List<MailMessage> {
        require(limit > 0) { "limit must be positive, was $limit" }
        val address = recipientAddress(to)
        return search(address)
            .asSequence()
            .filter { message -> message.to.any { it.equals(address, ignoreCase = true) } }
            .filter { !it.receivedAt.isBefore(since) && (!unreadOnly || !it.read) }
            .sortedByDescending { it.receivedAt }
            .take(limit)
            .toList()
    }

    override suspend fun markRead(messageId: String) {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        used += messageId
        if (readEndpointMissing.get()) return
        val action = "mark message $messageId read"
        val reply = exchange(action) { http.post(endpoint("test", "emails", messageId, "read").build()) { configure() } }
        when {
            reply.status.isSuccess() -> {
                return
            }

            reply.status in ENDPOINT_MISSING -> {
                if (readEndpointMissing.compareAndSet(false, true)) {
                    logger.info {
                        "The test API at $base cannot mark mail read (HTTP ${reply.status.value}); used mail is remembered locally"
                    }
                }
            }

            else -> {
                throw failure(action, reply)
            }
        }
    }

    override fun close() {
        if (ownsClient) http.close()
    }

    override fun toString(): String = "TestApiMailbox($base)"

    private suspend fun search(address: String): List<MailMessage> {
        val action = "search for mail to $address"
        val url = endpoint("test", "emails").apply { parameters.append("to", address) }
        val reply = exchange(action) { http.get(url.build()) { configure() } }
        if (!reply.status.isSuccess()) throw failure(action, reply)
        val items = parse(reply.body, action)
        return items.mapNotNull { message(it, action) }
    }

    private fun HttpRequestBuilder.configure() {
        expectSuccess = false
        accept(ContentType.Application.Json)
        token?.let { header(TOKEN_HEADER, it) }
    }

    private fun parse(
        body: String,
        action: String,
    ): List<JsonElement> =
        try {
            val root = json.parseToJsonElement(body)
            when (root) {
                is JsonArray -> root
                is JsonObject -> (root["emails"] ?: root["messages"])?.jsonArray ?: throw unexpected(action, "an object without emails")
                else -> throw unexpected(action, "neither a list nor an object")
            }
        } catch (e: SerializationException) {
            throw unexpected(action, e.message.orEmpty())
        } catch (e: IllegalArgumentException) {
            throw unexpected(action, e.message.orEmpty())
        }

    /** One listed e-mail; null (and logged) when it has no id or no readable time, like Mailpit's adapter does. */
    private fun message(
        element: JsonElement,
        action: String,
    ): MailMessage? {
        val mail = element as? JsonObject ?: throw unexpected(action, "a listed e-mail is not an object")
        val id = mail.text("id")?.takeIf { it.isNotBlank() }
        if (id == null) {
            logger.warn { "Ignoring an e-mail without id from $base" }
            return null
        }
        val receivedAt = parseInstant(mail.text("created_at"))
        if (receivedAt == null) {
            logger.warn { "Ignoring e-mail $id from $base: unreadable created_at '${mail.text("created_at")}'" }
            return null
        }
        val html = mail.text("html")?.takeIf { it.isNotBlank() }
        return MailMessage(
            id = id,
            to = addresses(mail["to"]),
            subject = mail.text("subject").orEmpty(),
            receivedAt = receivedAt,
            text = withListedLinks(mail.text("text").orEmpty(), html, links(mail["links"])),
            html = html,
            read = (mail["read"] as? JsonPrimitive)?.booleanOrNull == true || id in used,
        )
    }

    /** [text] followed by the listed links neither part contains, one per line, so the extractor sees all of them. */
    private fun withListedLinks(
        text: String,
        html: String?,
        links: List<String>,
    ): String {
        val missing = links.filterNot { it in text || html?.contains(it) == true }
        return (listOf(text).filter(String::isNotEmpty) + missing).joinToString("\n")
    }

    private fun endpoint(vararg segments: String): URLBuilder = URLBuilder(base).appendPathSegments(segments.toList(), encodeSlash = true)

    private suspend fun exchange(
        action: String,
        request: suspend () -> HttpResponse,
    ): Reply =
        try {
            val response = request()
            Reply(response.status, response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MailboxException(redact("Test API at $base: $action failed (${e::class.simpleName}: ${e.message})"))
        }

    private fun failure(
        action: String,
        reply: Reply,
    ): MailboxException {
        val hint =
            if (reply.status == HttpStatusCode.Unauthorized || reply.status == HttpStatusCode.Forbidden) {
                " (check PETEK_TEST_TOKEN and that the target runs in test mode)"
            } else {
                ""
            }
        return MailboxException(redact("Test API at $base: $action answered HTTP ${reply.status.value}$hint: ${snippet(reply.body)}"))
    }

    private fun unexpected(
        action: String,
        problem: String,
    ) = MailboxException(redact("Test API at $base: $action returned unexpected JSON ($problem)"))

    private fun redact(message: String): String = token?.let { message.replace(it, "***") } ?: message

    private class Reply(
        val status: HttpStatusCode,
        val body: String,
    )

    private companion object {
        const val TOKEN_HEADER = "X-Test-Token"
        const val SNIPPET_LENGTH = 200
        val ENDPOINT_MISSING = setOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed)

        fun usableToken(testToken: Secret?): String? {
            val token = testToken?.reveal()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            require(token.none(Char::isISOControl)) {
                "PETEK_TEST_TOKEN contains a line break or another control character; it cannot be sent as an HTTP header"
            }
            return token
        }

        /** Scheme, host, port and path prefix of [baseUrl]; user info is dropped so it never reaches a message. */
        fun baseOf(baseUrl: URI): String {
            require(baseUrl.scheme?.lowercase() in setOf("http", "https") && !baseUrl.host.isNullOrEmpty()) {
                val shown = baseUrl.rawUserInfo?.let { baseUrl.toString().replace("$it@", "***@") } ?: baseUrl.toString()
                "The test API URL must be an absolute http(s) URL, was $shown"
            }
            val hostAndPort = baseUrl.rawAuthority.substringAfterLast('@')
            return "${baseUrl.scheme}://$hostAndPort${baseUrl.rawPath.orEmpty().trimEnd('/')}"
        }

        fun defaultClient(requestTimeout: Duration): HttpClient {
            require(requestTimeout.isPositive()) { "requestTimeout must be positive, was $requestTimeout" }
            return HttpClient(CIO) {
                expectSuccess = false
                followRedirects = false
                install(HttpTimeout) {
                    requestTimeoutMillis = requestTimeout.inWholeMilliseconds
                    connectTimeoutMillis = minOf(requestTimeout, 5.seconds).inWholeMilliseconds
                    socketTimeoutMillis = requestTimeout.inWholeMilliseconds
                }
            }
        }

        fun recipientAddress(to: String): String {
            val address = to.trim()
            require(address.isNotEmpty() && address.none { it.isWhitespace() }) { "Recipient must be a plain e-mail address, was '$to'" }
            return address
        }

        /** `"a@x"`, `["a@x", "b@x"]` or `[{"address": "a@x"}]`. */
        fun addresses(element: JsonElement?): List<String> =
            when (element) {
                is JsonArray -> element.flatMap(::addresses)
                is JsonObject -> listOfNotNull(element.text("address") ?: element.text("email"))
                is JsonPrimitive -> listOfNotNull(element.textOrNull())
                else -> emptyList()
            }.map(String::trim).filter(String::isNotEmpty)

        /** `["https://…"]` or `[{"url": "https://…"}]`. */
        fun links(element: JsonElement?): List<String> =
            (element as? JsonArray)
                .orEmpty()
                .mapNotNull { link ->
                    when (link) {
                        is JsonPrimitive -> link.textOrNull()
                        is JsonObject -> link.text("url") ?: link.text("href")
                        else -> null
                    }
                }.map(String::trim)
                .filter(String::isNotEmpty)

        /** RFC 3339 with an offset (`2026-09-25T12:00:00+04:00`) or a UTC instant (`…Z`). */
        fun parseInstant(value: String?): Instant? =
            value?.trim()?.let {
                try {
                    OffsetDateTime.parse(it).toInstant()
                } catch (_: DateTimeParseException) {
                    null
                }
            }

        fun snippet(body: String): String =
            body
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(SNIPPET_LENGTH)
                .ifEmpty { "(empty body)" }

        fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.textOrNull()

        fun JsonPrimitive.textOrNull(): String? = takeUnless { it is JsonNull }?.content
    }
}

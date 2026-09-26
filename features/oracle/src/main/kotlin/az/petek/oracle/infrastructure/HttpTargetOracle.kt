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

package az.petek.oracle.infrastructure

import az.petek.core.security.Secret
import az.petek.oracle.domain.OracleException
import az.petek.oracle.domain.OraclePaths
import az.petek.oracle.domain.OracleResponse
import az.petek.oracle.domain.OracleSafetyException
import az.petek.oracle.domain.SeedCompanyRequest
import az.petek.oracle.domain.SeedCompanyResult
import az.petek.oracle.domain.TargetOracle
import az.petek.oracle.domain.TestCompany
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * [TargetOracle] over the target's test API exactly as docs/TARGET_CONTRACT.md section 4 describes it. Built by the
 * composition root from `PETEK_TARGET` and `PETEK_TEST_TOKEN`.
 *
 * - Every call carries `X-Test-Token`. Without a (non-blank) token [isAvailable] is false and every call fails fast
 *   with [OracleException] without contacting the target. Surrounding whitespace of the token (a CRLF `.env`) is
 *   dropped; a token with a control character inside is refused at construction, because HTTP clients echo invalid
 *   header values in their exception messages.
 * - Paths are joined to [baseUrl] (its own path prefix kept, no double slashes, query strings kept). Values the oracle
 *   puts into URLs itself are percent-encoded (`/test/otp/%2B99450…`, `?owner=eli%2Bqa%40…`); in rendered paths a `+`
 *   of the query is sent as `%2B`, because templates insert raw values (`?by=eli+qa@…`) and servers read `+` as a
 *   space there. Absolute URLs and `.`/`..` segments are refused and redirects are not followed, so the token is only
 *   ever sent to the target host, under the target's base path. Credentials in [baseUrl] are ignored and never shown.
 * - Lookups map 404 to null. [deleteCompany] first reads the company and refuses ([OracleSafetyException]) unless the
 *   target knows it under that id with `is_test` set (CLAUDE.md rule 8); a 403 on the delete itself is a refusal too.
 * - Failures become [OracleException] with the request and status; the token is redacted from every message.
 *
 * Owns (and [close]s) its HTTP client unless one is injected. An injected client must not follow redirects and should
 * have timeouts configured; the default client does both.
 */
class HttpTargetOracle(
    baseUrl: URI,
    testToken: Secret?,
    client: HttpClient? = null,
    requestTimeout: Duration = 15.seconds,
    /** Where the test API answers; the contract's paths unless the target profile names others. */
    private val paths: OraclePaths = OraclePaths.CONTRACT,
) : TargetOracle,
    AutoCloseable {
    /** The token as sent; null when none is configured. Validated before the HTTP client exists, so nothing leaks. */
    private val token: String? = usableToken(testToken)

    override val isAvailable: Boolean = token != null

    private val base: String = baseOf(baseUrl)
    private val ownsClient = client == null
    private val http: HttpClient = client ?: defaultClient(requestTimeout)
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    init {
        val host = baseUrl.host.lowercase()
        if (baseUrl.rawUserInfo != null) {
            logger.warn { "Credentials in the target URL are ignored by the oracle (only X-Test-Token is sent to $host)" }
        }
        if (isAvailable && baseUrl.scheme.equals("http", ignoreCase = true) && host !in LOCAL_HOSTS) {
            logger.warn { "The test token is sent to $host over plain HTTP; use https for remote targets" }
        }
    }

    override suspend fun get(path: String): OracleResponse {
        val reply = send(HttpMethod.Get, path)
        return OracleResponse(reply.status, parseOrNull(reply.body), reply.body)
    }

    override suspend fun latestOtp(phone: String): String? {
        require(phone.isNotBlank()) { "phone must not be blank" }
        val action = "latest OTP for $phone"
        val otp = lookup(action, paths.otp.replace("{phone}", UrlEncoding.component(phone.trim()))) ?: return null
        return otp.asObject(action).text("code") ?: throw protocolError(action, "the answer has no code")
    }

    override suspend fun companyByOwner(ownerEmail: String): TestCompany? {
        require(ownerEmail.isNotBlank()) { "ownerEmail must not be blank" }
        val action = "company of owner $ownerEmail"
        val answer = lookup(action, paths.companyByOwner.replace("{owner}", UrlEncoding.component(ownerEmail.trim()))) ?: return null
        // The contract answers one object; a list (one company per owner anyway) is accepted as well.
        val company = if (answer is JsonArray) answer.firstOrNull() ?: return null else answer
        return company.asObject(action).toTestCompany(action)
    }

    override suspend fun company(companyId: String): TestCompany? {
        val action = "company ${requireCompanyId(companyId)}"
        return lookup(action, companyPath(companyId))?.asObject(action)?.toTestCompany(action)
    }

    override suspend fun seedCompany(request: SeedCompanyRequest): SeedCompanyResult {
        requireCompanyId(request.companyId)
        val action = "seed company ${request.companyId}"
        val body = json.encodeToString(SeedCompanyBody.serializer(), SeedCompanyBody.of(request))
        val reply = send(HttpMethod.Post, paths.seedCompany, body)
        if (reply.status !in SUCCESS) throw statusError(action, reply)
        val answer =
            parseOrNull(reply.body)?.asObject(action) ?: throw protocolError(action, "the answer is not JSON: ${snippet(reply.body)}")
        return SeedCompanyResult(
            companyId = answer.text("company_id") ?: request.companyId,
            companyCode = answer.text("code"),
            departmentIds =
                (answer["departments"] as? JsonObject)
                    .orEmpty()
                    .mapNotNull { (name, id) ->
                        id.text()?.let { name to it }
                    }.toMap(),
            inviteLinks = (answer["invites"] as? JsonArray).orEmpty().mapNotNull(::inviteLink).toMap(),
        )
    }

    override suspend fun deleteCompany(companyId: String) {
        val id = requireCompanyId(companyId)
        val company = company(id) ?: throw OracleSafetyException("Refusing to delete company $id: the target does not know it")
        if (company.id != id) {
            throw OracleSafetyException("Refusing to delete company $id: the target answered for company ${company.id}")
        }
        if (!company.isTest) {
            throw OracleSafetyException("Refusing to delete company $id ('${company.name}'): it is not flagged is_test")
        }
        val reply = send(HttpMethod.Delete, companyPath(id))
        when (reply.status) {
            in SUCCESS -> logger.info { "Deleted test company $id" }
            NOT_FOUND -> logger.info { "Test company $id was already deleted" }
            FORBIDDEN -> throw OracleSafetyException("The target refused to delete company $id (HTTP 403): it is not a test company")
            else -> throw statusError("delete company $id", reply)
        }
    }

    override fun close() {
        if (ownsClient) http.close()
    }

    override fun toString(): String = "HttpTargetOracle($base, available=$isAvailable)"

    /** A successful answer's JSON, null on 404; any other status is an [OracleException]. */
    private suspend fun lookup(
        action: String,
        path: String,
    ): JsonElement? {
        val reply = send(HttpMethod.Get, path)
        return when (reply.status) {
            NOT_FOUND -> null
            in SUCCESS -> parseOrNull(reply.body) ?: throw protocolError(action, "the answer is not JSON: ${snippet(reply.body)}")
            else -> throw statusError(action, reply)
        }
    }

    private suspend fun send(
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): Reply {
        val token = token ?: throw OracleException(UNAVAILABLE)
        val url = urlFor(path)
        val request = "${method.value} $url"
        return try {
            val response =
                http.request(url) {
                    this.method = method
                    expectSuccess = false
                    header(TOKEN_HEADER, token)
                    accept(ContentType.Application.Json)
                    if (body != null) setBody(TextContent(body, ContentType.Application.Json))
                }
            Reply(request, response.status.value, response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The cause is kept for its stack trace unless something in its chain quotes the token.
            val cause = e.takeUnless { mentionsToken(it) }
            throw OracleException(redact("Oracle request $request failed (${e::class.simpleName}: ${e.message})"), cause)
        }
    }

    private fun urlFor(path: String): String {
        val trimmed = path.trim()
        require(!NOT_RELATIVE.containsMatchIn(trimmed)) { "Oracle paths must be relative to the target, was '$path'" }
        require(!UrlEncoding.hasDotSegment(trimmed)) { "Oracle paths must not contain '.' or '..' segments, was '$path'" }
        return "$base/${UrlEncoding.repair(trimmed).trimStart('/')}"
    }

    private fun companyPath(companyId: String) = paths.company.replace("{id}", UrlEncoding.component(companyId))

    private fun parseOrNull(body: String): JsonElement? =
        if (body.isBlank()) {
            null
        } else {
            try {
                json.parseToJsonElement(body).takeIf { it.isStrictJson() }
            } catch (_: SerializationException) {
                null
            }
        }

    private fun JsonElement.asObject(action: String): JsonObject =
        this as? JsonObject ?: throw protocolError(action, "expected a JSON object, got ${snippet(toString())}")

    private fun JsonObject.toTestCompany(action: String) =
        TestCompany(
            id = text("id") ?: throw protocolError(action, "the company has no id"),
            name = text("name").orEmpty(),
            code = text("code"),
            isTest = get("is_test").isTrueFlag(),
        )

    private fun inviteLink(element: JsonElement): Pair<String, String>? {
        val invite = element as? JsonObject ?: return null
        val email = invite.text("email") ?: return null
        val link = invite.text("link")?.takeIf { it.isNotBlank() } ?: return null
        return email to link
    }

    private fun statusError(
        action: String,
        reply: Reply,
    ): OracleException {
        val hint =
            if (reply.status == UNAUTHORIZED ||
                reply.status == FORBIDDEN
            ) {
                " (check PETEK_TEST_TOKEN and that the target runs in test mode)"
            } else {
                ""
            }
        return OracleException(redact("Oracle $action: ${reply.request} answered HTTP ${reply.status}$hint: ${snippet(reply.body)}"))
    }

    private fun protocolError(
        action: String,
        problem: String,
    ) = OracleException(redact("Oracle $action: $problem"))

    private fun redact(message: String): String = token?.let { message.replace(it, "***") } ?: message

    private fun mentionsToken(error: Throwable): Boolean {
        val secret = token ?: return false
        return generateSequence(error) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .flatMap { sequenceOf(it) + it.suppressed.asSequence() }
            .any { secret in it.toString() }
    }

    private class Reply(
        val request: String,
        val status: Int,
        val body: String,
    )

    private companion object {
        const val TOKEN_HEADER = "X-Test-Token"
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val SNIPPET_LENGTH = 200
        const val MAX_CAUSE_DEPTH = 16
        const val UNAVAILABLE = "The target test API is not configured (PETEK_TEST_TOKEN is empty); oracle calls are unavailable"
        val SUCCESS = 200..299
        val JSON_NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")
        val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

        /** `https://…`, `mailto:…` or `//host/…`: anything that could point the token at another host. */
        val NOT_RELATIVE = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*:|//|\\\\)""")

        /** Scheme, host, port and path prefix of [baseUrl]; user info is dropped so it never reaches a message. */
        fun baseOf(baseUrl: URI): String {
            require(baseUrl.scheme?.lowercase() in setOf("http", "https") && !baseUrl.host.isNullOrEmpty()) {
                "Target URL must be an absolute http(s) URL, was ${withoutUserInfo(baseUrl)}"
            }
            val hostAndPort = baseUrl.rawAuthority.substringAfterLast('@')
            return "${baseUrl.scheme}://$hostAndPort${baseUrl.rawPath.orEmpty().trimEnd('/')}"
        }

        fun withoutUserInfo(url: URI): String = url.rawUserInfo?.let { url.toString().replace("$it@", "***@") } ?: url.toString()

        /** Surrounding whitespace is dropped (a CRLF `.env`); a control character inside cannot be a header value. */
        fun usableToken(testToken: Secret?): String? {
            val token = testToken?.reveal()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            require(token.none(Char::isISOControl)) {
                "PETEK_TEST_TOKEN contains a line break or another control character; it cannot be sent as an HTTP header"
            }
            return token
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

        fun requireCompanyId(companyId: String): String {
            require(companyId.isNotBlank() && companyId.trim('.').isNotEmpty()) { "Invalid company id '$companyId'" }
            return companyId
        }

        /** Content of a JSON string/number/boolean; null for JSON null, objects, arrays and missing keys. */
        fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

        fun JsonObject.text(key: String): String? = get(key).text()

        /** `true`, `1`, `"true"` and `"1"` all mean yes (PHP/MySQL targets send integers); anything else is no. */
        fun JsonElement?.isTrueFlag(): Boolean {
            val primitive = this as? JsonPrimitive ?: return false
            if (primitive is JsonNull) return false
            return primitive.booleanOrNull ?: (primitive.content.trim().lowercase() in setOf("true", "1"))
        }

        /** kotlinx reads unquoted words (`<html>Login</html>`) as primitives; only real JSON literals count. */
        fun JsonElement.isStrictJson(): Boolean =
            when (this) {
                is JsonNull -> true
                is JsonPrimitive -> isString || content == "true" || content == "false" || JSON_NUMBER.matches(content)
                is JsonArray -> all { it.isStrictJson() }
                is JsonObject -> values.all { it.isStrictJson() }
            }

        fun snippet(body: String): String =
            body
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(SNIPPET_LENGTH)
                .ifEmpty { "(empty body)" }
    }
}

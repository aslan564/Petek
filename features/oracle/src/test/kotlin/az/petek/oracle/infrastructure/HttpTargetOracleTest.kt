package az.petek.oracle.infrastructure

import az.petek.core.security.Secret
import az.petek.oracle.domain.Invitee
import az.petek.oracle.domain.OracleException
import az.petek.oracle.domain.OracleResponse
import az.petek.oracle.domain.OracleSafetyException
import az.petek.oracle.domain.SeedCompanyRequest
import az.petek.oracle.domain.SeedCompanyResult
import az.petek.oracle.domain.TestCompany
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds

class HttpTargetOracleTest {
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun tearDown() {
        closeables.asReversed().forEach { it.close() }
    }

    private fun <T : AutoCloseable> T.closing(): T = also { closeables += it }

    private fun server(handler: suspend (RecordedRequest) -> StubResponse) = StubHttpServer(handler).closing()

    private fun oracle(
        server: StubHttpServer,
        path: String = "",
        token: Secret? = Secret(TOKEN),
    ) = HttpTargetOracle(URI("${server.baseUrl}$path"), token).closing()

    /** A small in-memory test API following docs/TARGET_CONTRACT.md section 4. */
    private fun testApi(
        companies: Map<String, String> = mapOf("c1" to COMPANY_C1),
        deleteStatus: Int = 204,
    ) = server { request ->
        when {
            request.method == "GET" && request.path == "/test/otp/%2B994501234567" -> {
                StubResponse(body = """{"phone": "+994501234567", "code": "123456"}""")
            }

            request.method == "GET" && request.path == "/test/companies" && request.query["owner"] == listOf(OWNER) -> {
                StubResponse(body = COMPANY_C1)
            }

            request.method == "GET" && request.path.startsWith("/test/companies/") && request.path != "/test/companies/seed" -> {
                companies[request.path.removePrefix("/test/companies/")]?.let { StubResponse(body = it) }
                    ?: StubResponse(404, """{"error": "not found"}""")
            }

            request.method == "POST" && request.path == "/test/companies/seed" -> {
                StubResponse(201, """{"company_id": "c1", "code": "PTK-4821", "departments": {"IT": "d1"}, "invites": []}""")
            }

            request.method == "DELETE" && request.path.startsWith("/test/companies/") -> {
                StubResponse(deleteStatus)
            }

            request.method == "GET" && request.path == "/test/tickets/42" -> {
                StubResponse(body = """{"id": "t42", "status": "open"}""")
            }

            else -> {
                StubResponse(404, """{"error": "not found"}""")
            }
        }
    }

    @Test
    fun `is available only with a non-blank token`() {
        val server = testApi()

        oracle(server).isAvailable shouldBe true
        oracle(server, token = null).isAvailable shouldBe false
        oracle(server, token = Secret("  ")).isAvailable shouldBe false
    }

    @Test
    fun `every call carries the test token header`() {
        val server = testApi()
        val oracle = oracle(server)

        runBlocking {
            oracle.get("/test/tickets/42")
            oracle.latestOtp("+994501234567")
            oracle.companyByOwner(OWNER)
            oracle.company("c1")
            oracle.seedCompany(SeedCompanyRequest("c1", listOf("IT"), emptyList()))
            oracle.deleteCompany("c1")
        }

        server.requests.size shouldBe 7
        server.requests.map { it.headers["x-test-token"] }.distinct() shouldContainExactly listOf(TOKEN)
    }

    @Test
    fun `without a token every call fails without contacting the target`() {
        val server = testApi()

        listOf(null, Secret(""), Secret("   ")).forEach { token ->
            val oracle = oracle(server, token = token)
            runBlocking {
                shouldThrow<OracleException> { oracle.get("/test/tickets/42") }.message.shouldNotBeNull() shouldContain "PETEK_TEST_TOKEN"
                shouldThrow<OracleException> { oracle.latestOtp("+994501234567") }
                shouldThrow<OracleException> { oracle.companyByOwner(OWNER) }
                shouldThrow<OracleException> { oracle.company("c1") }
                shouldThrow<OracleException> { oracle.seedCompany(SeedCompanyRequest("c1", emptyList(), emptyList())) }
                shouldThrow<OracleException> { oracle.deleteCompany("c1") }
            }
        }
        server.requests shouldBe emptyList()
    }

    @Test
    fun `get returns status, parsed JSON and raw text for any status`() {
        val server =
            server { request ->
                when (request.path) {
                    "/ok" -> StubResponse(body = """{"status": "published"}""")
                    "/text" -> StubResponse(404, "Not found", "text/plain")
                    "/html" -> StubResponse(500, "<html><body>Error</body></html>", "text/html")
                    "/json-error" -> StubResponse(409, """{"error": "already decided"}""")
                    "/word" -> StubResponse(200, "OK", "text/plain")
                    "/number" -> StubResponse(200, "42")
                    "/bare-word-inside" -> StubResponse(200, """{"status": published}""")
                    else -> StubResponse(204)
                }
            }
        val oracle = oracle(server)

        runBlocking {
            oracle.get("/ok") shouldBe
                OracleResponse(200, Json.parseToJsonElement("""{"status": "published"}"""), """{"status": "published"}""")
            oracle.get("/text") shouldBe OracleResponse(404, null, "Not found")
            oracle.get("/html") shouldBe OracleResponse(500, null, "<html><body>Error</body></html>")
            oracle.get("/json-error") shouldBe
                OracleResponse(409, Json.parseToJsonElement("""{"error": "already decided"}"""), """{"error": "already decided"}""")
            oracle.get("/empty") shouldBe OracleResponse(204, null, "")
            oracle.get("/word") shouldBe OracleResponse(200, null, "OK")
            oracle.get("/number") shouldBe OracleResponse(200, JsonPrimitive(42), "42")
            oracle.get("/bare-word-inside").body.shouldBeNull()
        }
    }

    @Test
    fun `paths are joined to the base URL without double slashes and keep their query`() {
        val server = server { StubResponse(body = "{}") }

        runBlocking {
            oracle(server, path = "/").get("/test/tickets/latest?by=eli.k7x2.a07@test.kadrohr.com")
            oracle(server, path = "/staging/").get("test/tickets/42")
            oracle(server, path = "/staging").get("/test/announcements/a1/receipts")
        }

        server.requests.map { it.path } shouldContainExactly
            listOf("/test/tickets/latest", "/staging/test/tickets/42", "/staging/test/announcements/a1/receipts")
        server.requests.none { "//" in it.uri } shouldBe true
        server.requests
            .first()
            .query["by"] shouldBe listOf("eli.k7x2.a07@test.kadrohr.com")
    }

    @Test
    fun `characters that are illegal in a URL are escaped while valid escapes are kept`() {
        val server = server { StubResponse(body = "{}") }

        runBlocking { oracle(server).get("/test/announcements/latest?by=əli@x.az&q=a b&r=50%&s=%2B1") }

        val request = server.requests.single()
        request.query["by"] shouldBe listOf("əli@x.az")
        request.query["q"] shouldBe listOf("a b")
        request.query["r"] shouldBe listOf("50%")
        request.query["s"] shouldBe listOf("+1")
    }

    @Test
    fun `absolute URLs are refused so the token never leaves the target`() {
        val server = testApi()
        val oracle = oracle(server)

        runBlocking {
            listOf("https://evil.example/steal", "http://evil.example", "//evil.example/x", "mailto:x@y.z", "\\\\evil\\x").forEach { path ->
                shouldThrow<IllegalArgumentException> { oracle.get(path) }
            }
        }
        server.requests shouldBe emptyList()
    }

    @Test
    fun `redirects are not followed so the token is never sent elsewhere`() {
        val elsewhere = server { StubResponse(body = "{}") }
        val target = server { StubResponse(302, "", "text/plain", headers = mapOf("Location" to "${elsewhere.baseUrl}/steal")) }

        val response = runBlocking { oracle(target).get("/test/tickets/42") }

        response.status shouldBe 302
        elsewhere.requests shouldBe emptyList()
    }

    @Test
    fun `latestOtp encodes the plus sign of the phone and returns the code`() {
        val server = testApi()
        val oracle = oracle(server)

        runBlocking { oracle.latestOtp("+994501234567") } shouldBe "123456"

        server.requests.single().uri shouldBe "/test/otp/%2B994501234567"
    }

    @Test
    fun `latestOtp is null when the target has no code and accepts numeric codes`() {
        val server =
            server { request ->
                if (request.path == "/test/otp/%2B994500000000") StubResponse(404, "") else StubResponse(body = """{"code": 654321}""")
            }
        val oracle = oracle(server)

        runBlocking {
            oracle.latestOtp("+994500000000").shouldBeNull()
            oracle.latestOtp("+994501111111") shouldBe "654321"
        }
    }

    @Test
    fun `latestOtp without a code in the answer is a protocol error`() {
        val server = server { StubResponse(body = """{"phone": "+994501234567"}""") }

        val error = shouldThrow<OracleException> { runBlocking { oracle(server).latestOtp("+994501234567") } }

        error.message.shouldNotBeNull() shouldContain "no code"
    }

    @Test
    fun `companyByOwner url-encodes the e-mail and maps the company`() {
        val server = server { StubResponse(body = COMPANY_C1) }

        val company = runBlocking { oracle(server).companyByOwner("eli+qa@test.kadrohr.com") }

        company shouldBe TestCompany(id = "c1", name = "Pətək Test MMC", code = "PTK-4821", isTest = true)
        val request = server.requests.single()
        request.uri shouldBe "/test/companies?owner=eli%2Bqa%40test.kadrohr.com"
        request.query["owner"] shouldBe listOf("eli+qa@test.kadrohr.com")
    }

    @Test
    fun `companyByOwner is null on 404 and accepts a list answer`() {
        val server =
            server { request ->
                when (request.query["owner"]?.single()) {
                    "none@test.kadrohr.com" -> StubResponse(404, "")
                    "empty@test.kadrohr.com" -> StubResponse(body = "[]")
                    else -> StubResponse(body = "[$COMPANY_C1]")
                }
            }
        val oracle = oracle(server)

        runBlocking {
            oracle.companyByOwner("none@test.kadrohr.com").shouldBeNull()
            oracle.companyByOwner("empty@test.kadrohr.com").shouldBeNull()
            oracle.companyByOwner(OWNER)?.id shouldBe "c1"
        }
    }

    @Test
    fun `company maps snake_case fields and tolerates numeric ids and integer flags`() {
        val server =
            server { request ->
                when (request.path) {
                    "/test/companies/7" -> {
                        StubResponse(
                            body = """{"id": 7, "name": "Pətək Test MMC", "code": null, "is_test": 1, "extra": [1]}""",
                        )
                    }

                    "/test/companies/8" -> {
                        StubResponse(body = """{"id": "8", "name": "Real LLC"}""")
                    }

                    "/test/companies/9" -> {
                        StubResponse(body = """{"id": "9", "name": "Str", "is_test": "true"}""")
                    }

                    else -> {
                        StubResponse(body = """{"id": "10", "name": "No", "is_test": "false"}""")
                    }
                }
            }
        val oracle = oracle(server)

        runBlocking {
            oracle.company("7") shouldBe TestCompany("7", "Pətək Test MMC", null, isTest = true)
            oracle.company("8") shouldBe TestCompany("8", "Real LLC", null, isTest = false)
            oracle.company("9")?.isTest shouldBe true
            oracle.company("10")?.isTest shouldBe false
        }
    }

    @Test
    fun `company ids are encoded as one path segment and dot segments are refused`() {
        val server = server { StubResponse(404, "") }
        val oracle = oracle(server)

        runBlocking {
            oracle.company("a/b c").shouldBeNull()
            listOf("", " ", ".", "..").forEach { id -> shouldThrow<IllegalArgumentException> { oracle.company(id) } }
            shouldThrow<IllegalArgumentException> { oracle.deleteCompany("..") }
        }
        server.requests.map { it.uri } shouldContainExactly listOf("/test/companies/a%2Fb%20c")
    }

    @Test
    fun `seedCompany posts snake_case JSON and maps the answer`() {
        val server =
            server {
                StubResponse(
                    body =
                        """
                        {"company_id": "c1", "code": "PTK-4821", "departments": {"IT": "d1", "HR": 2},
                         "invites": [{"email": "rena@test.kadrohr.com", "link": "https://staging.kadrohr.com/invite/tok1"},
                                     {"email": "no-link@test.kadrohr.com", "link": null}], "unknown": true}
                        """.trimIndent(),
                )
            }
        val request =
            SeedCompanyRequest(
                companyId = "c1",
                departments = listOf("IT", "HR"),
                invites =
                    listOf(
                        Invitee("rena@test.kadrohr.com", "Rəna Əliyeva", "manager", "IT"),
                        Invitee("admin2@test.kadrohr.com", "İkinci Admin", "admin", null),
                    ),
            )

        val result = runBlocking { oracle(server).seedCompany(request) }

        result shouldBe
            SeedCompanyResult(
                companyId = "c1",
                companyCode = "PTK-4821",
                departmentIds = mapOf("IT" to "d1", "HR" to "2"),
                inviteLinks = mapOf("rena@test.kadrohr.com" to "https://staging.kadrohr.com/invite/tok1"),
            )
        val post = server.requests.single()
        post.method shouldBe "POST"
        post.path shouldBe "/test/companies/seed"
        post.headers["content-type"].shouldNotBeNull() shouldStartWith "application/json"
        Json.parseToJsonElement(post.body) shouldBe
            Json.parseToJsonElement(
                """
                {"company_id": "c1", "departments": ["IT", "HR"], "invites": [
                  {"email": "rena@test.kadrohr.com", "name": "Rəna Əliyeva", "role": "manager", "department": "IT"},
                  {"email": "admin2@test.kadrohr.com", "name": "İkinci Admin", "role": "admin"}
                ]}
                """.trimIndent(),
            )
    }

    @Test
    fun `seedCompany keeps the requested company id when the answer omits it`() {
        val server = server { StubResponse(body = "{}") }

        runBlocking { oracle(server).seedCompany(SeedCompanyRequest("c9", emptyList(), emptyList())) } shouldBe
            SeedCompanyResult("c9", null, emptyMap(), emptyMap())
    }

    @Test
    fun `a failed seed becomes an OracleException with the status and body`() {
        val server = server { StubResponse(422, """{"error": "unknown department"}""") }

        val error =
            shouldThrow<OracleException> { runBlocking { oracle(server).seedCompany(SeedCompanyRequest("c1", emptyList(), emptyList())) } }

        error.message.shouldNotBeNull() shouldContain "HTTP 422"
        error.message.shouldNotBeNull() shouldContain "unknown department"
        error.message.shouldNotBeNull() shouldContain "POST ${server.baseUrl}/test/companies/seed"
    }

    @Test
    fun `deleteCompany checks the company first and then deletes a test company`() {
        val server = testApi()

        runBlocking { oracle(server).deleteCompany("c1") }

        server.requests.map { "${it.method} ${it.path}" } shouldContainExactly listOf("GET /test/companies/c1", "DELETE /test/companies/c1")
    }

    @Test
    fun `deleteCompany refuses a company that is not flagged is_test`() {
        val server = testApi(companies = mapOf("c2" to """{"id": "c2", "name": "Real LLC", "code": "RL-1", "is_test": false}"""))

        val error = shouldThrow<OracleSafetyException> { runBlocking { oracle(server).deleteCompany("c2") } }

        error.message.shouldNotBeNull() shouldContain "is_test"
        server.requests.none { it.method == "DELETE" } shouldBe true
    }

    @Test
    fun `deleteCompany refuses a company the target does not know`() {
        val server = testApi(companies = emptyMap())

        shouldThrow<OracleSafetyException> { runBlocking { oracle(server).deleteCompany("c404") } }

        server.requests.none { it.method == "DELETE" } shouldBe true
    }

    @Test
    fun `deleteCompany refuses when the target answers for another company`() {
        val server = testApi(companies = mapOf("c3" to COMPANY_C1))

        shouldThrow<OracleSafetyException> { runBlocking { oracle(server).deleteCompany("c3") } }

        server.requests.none { it.method == "DELETE" } shouldBe true
    }

    @Test
    fun `a 403 on delete is a safety refusal`() {
        val server = testApi(deleteStatus = 403)

        shouldThrow<OracleSafetyException> { runBlocking { oracle(server).deleteCompany("c1") } }
    }

    @Test
    fun `deleting a company that is already gone is not an error`() {
        val server = testApi(deleteStatus = 404)

        runBlocking { oracle(server).deleteCompany("c1") }
    }

    @Test
    fun `other delete failures become OracleException`() {
        val server = testApi(deleteStatus = 500)

        val error = shouldThrow<OracleException> { runBlocking { oracle(server).deleteCompany("c1") } }

        error.message.shouldNotBeNull() shouldContain "HTTP 500"
    }

    @Test
    fun `a rejected token becomes an OracleException with a hint and without the token`() {
        val server = server { StubResponse(401, "invalid X-Test-Token", "text/plain") }

        val error = shouldThrow<OracleException> { runBlocking { oracle(server).company("c1") } }

        error.message.shouldNotBeNull() shouldContain "HTTP 401"
        error.message.shouldNotBeNull() shouldContain "PETEK_TEST_TOKEN"
        error.shouldNotLeakToken()
    }

    @Test
    fun `the token is redacted even when the target echoes it`() {
        val server = server { request -> StubResponse(500, "token ${request.headers["x-test-token"]} is broken", "text/plain") }

        val error = shouldThrow<OracleException> { runBlocking { oracle(server).companyByOwner(OWNER) } }

        error.message.shouldNotBeNull() shouldContain "token *** is broken"
        error.shouldNotLeakToken()
    }

    @Test
    fun `a target that answers a lookup with something other than JSON is an OracleException`() {
        val server = server { StubResponse(body = "<html>Login</html>", contentType = "text/html") }

        val error = shouldThrow<OracleException> { runBlocking { oracle(server).company("c1") } }

        error.message.shouldNotBeNull() shouldContain "not JSON"
        shouldThrow<OracleException> { runBlocking { oracle(server).companyByOwner(OWNER) } }
    }

    @Test
    fun `a company without an id is a protocol error`() {
        val server = server { StubResponse(body = """{"name": "Nameless"}""") }

        shouldThrow<OracleException> { runBlocking { oracle(server).company("c1") } }
    }

    @Test
    fun `an unreachable target becomes an OracleException without the token`() {
        val stopped = StubHttpServer { StubResponse() }
        val url = stopped.baseUrl
        stopped.close()

        val error = shouldThrow<OracleException> { runBlocking { HttpTargetOracle(url, Secret(TOKEN)).closing().get("/test/tickets/42") } }

        error.cause.shouldNotBeNull()
        error.message.shouldNotBeNull() shouldContain "GET $url/test/tickets/42"
        error.shouldNotLeakToken()
    }

    @Test
    fun `a hanging target times out as an OracleException`() {
        val release = CompletableDeferred<Unit>()
        val server =
            server {
                release.await()
                StubResponse()
            }
        try {
            val oracle = HttpTargetOracle(server.baseUrl, Secret(TOKEN), requestTimeout = 300.milliseconds).closing()

            shouldThrow<OracleException> { runBlocking { oracle.get("/test/tickets/42") } }.shouldNotLeakToken()
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `the base URL must be an absolute http URL`() {
        shouldThrow<IllegalArgumentException> { HttpTargetOracle(URI("ftp://staging.kadrohr.com"), Secret(TOKEN)) }
        shouldThrow<IllegalArgumentException> { HttpTargetOracle(URI("/test"), Secret(TOKEN)) }
    }

    @Test
    fun `blank lookup keys are rejected`() {
        val oracle = oracle(testApi())

        runBlocking {
            shouldThrow<IllegalArgumentException> { oracle.latestOtp(" ") }
            shouldThrow<IllegalArgumentException> { oracle.companyByOwner("") }
        }
    }

    @Test
    fun `closing the oracle leaves an injected client open`() {
        val server = testApi()
        val client = HttpClient(CIO).closing()

        HttpTargetOracle(server.baseUrl, Secret(TOKEN), client).close()

        runBlocking { client.get("${server.baseUrl}/test/tickets/42") }.status.value shouldBe 200
    }

    @Test
    fun `the token is never part of the oracle's string form`() {
        oracle(testApi()).toString() shouldNotContain TOKEN
    }

    private fun Throwable.shouldNotLeakToken() {
        generateSequence(this) { it.cause }.forEach { throwable ->
            throwable.toString() shouldNotContain TOKEN
            throwable.stackTraceToString() shouldNotContain TOKEN
        }
    }

    private companion object {
        const val TOKEN = "s3cr3t-test-token-4f9a"
        const val OWNER = "eli.k7x2.a01@test.kadrohr.com"
        const val COMPANY_C1 = """{"id": "c1", "name": "Pətək Test MMC", "code": "PTK-4821", "is_test": true}"""
    }
}

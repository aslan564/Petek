package az.petek.app.cli

import az.petek.app.testing.CliHarness
import az.petek.app.testing.FakeBrowserEngine
import az.petek.app.testing.scriptedLlm
import az.petek.faketarget.FakeTargetServer
import az.petek.llm.domain.LlmException
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DoctorCommandTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var target: FakeTargetServer
    private val healthyLlm = scriptedLlm { buildJsonObject { put("ok", true) } }

    @BeforeEach
    fun start() {
        target = FakeTargetServer().start()
    }

    @AfterEach
    fun stop() = target.close()

    private fun cli(
        vararg environment: Pair<String, String>,
        llm: az.petek.llm.domain.LlmClient = healthyLlm,
        browser: FakeBrowserEngine = FakeBrowserEngine(),
    ) = CliHarness(
        dir,
        mapOf(
            "PETEK_TARGET" to target.baseUrl.toString(),
            "PETEK_MAILPIT_URL" to target.mailpitUrl.toString(),
            "PETEK_TEST_TOKEN" to "dev-token",
        ) + environment,
        llm = llm,
        browser = browser,
    )

    /** The table row of [check], e.g. `│ ✓ │ Mailpit │ HTTP 200 from … │`. */
    private fun row(
        output: String,
        check: String,
    ): String = output.lines().single { " $check " in it }

    @Test
    fun `everything in place gives a green table and exit code 0`() =
        runBlocking<Unit> {
            val browser = FakeBrowserEngine()
            val result = cli(browser = browser).run("doctor")

            result.statusCode shouldBe 0
            listOf("Configuration", "Target policy", "Target reachable", "Chromium", "Mailpit", "Test API", "LLM provider").forEach {
                row(result.stdout, it) shouldContain "✓"
            }
            row(result.stdout, "Target reachable") shouldContain "HTTP 3"
            row(result.stdout, "Test API") shouldContain "token accepted (HTTP 404"
            row(result.stdout, "LLM provider") shouldContain "claude-cli (claude-sonnet-5) answered a structured request"
            browser.sessions.single().closed shouldBe true
            browser.stopCount shouldBe 1
            Files.exists(dir.resolve("evidence/petek.db")) shouldBe false
        }

    @Test
    fun `a wrong test token is reported as rejected`() =
        runBlocking<Unit> {
            val result = cli("PETEK_TEST_TOKEN" to "wrong-token").run("doctor")

            result.statusCode shouldBe 1
            row(result.stdout, "Test API") shouldContain "✗"
            row(result.stdout, "Test API") shouldContain "HTTP 401: the target rejects PETEK_TEST_TOKEN"
            result.output shouldNotContain "wrong-token"
        }

    @Test
    fun `a missing test token is reported`() =
        runBlocking<Unit> {
            val cli = cli()
            cli.env.remove("PETEK_TEST_TOKEN")

            val result = cli.run("doctor")

            result.statusCode shouldBe 1
            row(result.stdout, "Test API") shouldContain "PETEK_TEST_TOKEN is empty"
        }

    @Test
    fun `an unreachable Mailpit and target show the exact connection error`() =
        runBlocking<Unit> {
            val result = cli("PETEK_MAILPIT_URL" to "http://127.0.0.1:9", "PETEK_TARGET" to "http://127.0.0.1:9").run("doctor")

            result.statusCode shouldBe 1
            row(result.stdout, "Mailpit") shouldContain "ConnectException"
            row(result.stdout, "Mailpit") shouldContain "docker compose up -d"
            row(result.stdout, "Target reachable") shouldContain "ConnectException"
            row(result.stdout, "Test API") shouldContain "connection error"
        }

    @Test
    fun `the LLM provider's own error is shown verbatim`() =
        runBlocking<Unit> {
            val broke =
                scriptedLlm {
                    throw LlmException.Unavailable(
                        "Claude CLI cannot answer: Credit balance is too low. Run `claude`, then /login with your Claude plan account.",
                    )
                }

            val result = cli(llm = broke).run("doctor")

            result.statusCode shouldBe 1
            row(result.stdout, "LLM provider") shouldContain "Credit balance is too low"
            row(result.stdout, "LLM provider") shouldContain "/login"
        }

    @Test
    fun `a browser that cannot start is reported`() =
        runBlocking<Unit> {
            val result = cli(browser = FakeBrowserEngine(failStart = "Executable doesn't exist")).run("doctor")

            result.statusCode shouldBe 1
            row(result.stdout, "Chromium") shouldContain "Executable doesn't exist"
        }

    @Test
    fun `a refused target is not contacted`() =
        runBlocking<Unit> {
            val result = cli("PETEK_PRODUCTION_HOSTS" to "127.0.0.1").run("doctor")

            result.statusCode shouldBe 2
            row(result.stdout, "Target policy") shouldContain "production host"
            row(result.stdout, "Target reachable") shouldContain "not contacted"
            row(result.stdout, "Test API") shouldContain "not contacted"
            target.store.companies.shouldBeEmpty()
        }

    @Test
    fun `an invalid configuration is a failed check listing every problem`() =
        runBlocking<Unit> {
            val cli = cli("PETEK_LLM_CONCURRENCY" to "many")
            cli.env.remove("PETEK_TARGET")

            val result = cli.run("doctor")

            result.statusCode shouldBe 2
            row(result.stdout, "Configuration") shouldContain "PETEK_TARGET is required"
            row(result.stdout, "Configuration") shouldContain "PETEK_LLM_CONCURRENCY"
            row(result.stdout, "LLM provider") shouldContain "not checked"
        }
}

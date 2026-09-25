package az.petek.app.diagnostics

import az.petek.app.config.PetekConfig
import az.petek.app.di.AppContainer
import az.petek.browser.domain.SessionOptions
import az.petek.core.security.TargetVerdict
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmMessage
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI

enum class CheckStatus { OK, FAILED, SKIPPED }

/** One line of the `petek doctor` table. [detail] is shown verbatim (errors included) and never contains a secret. */
data class CheckResult(
    val name: String,
    val status: CheckStatus,
    val detail: String,
)

/**
 * `petek doctor`: checks that everything a run needs is in place, independently of each other (and concurrently,
 * since the browser and the LLM take seconds): the target policy, the target answering HTTP, Chromium starting,
 * Mailpit answering, the target's test API accepting the token, and the LLM provider answering one tiny structured
 * request (its own [LlmProviders][az.petek.app.di.LlmProviders] timeout, no retries, the provider's exact error).
 *
 * A target the policy refuses is not contacted at all; its checks are SKIPPED. Nothing is written to the target.
 */
class Doctor(
    private val container: AppContainer,
    private val http: HttpProbe,
) {
    private val config: PetekConfig get() = container.config

    suspend fun run(): List<CheckResult> {
        val policy = policy()
        val allowed = policy.status == CheckStatus.OK
        return coroutineScope {
            listOf(
                async { policy },
                async { if (allowed) targetReachable() else skipped(TARGET) },
                async { chromium() },
                async { mailpit() },
                async { if (allowed) testApi() else skipped(TEST_API) },
                async { llm() },
            ).awaitAll()
        }
    }

    private fun policy(): CheckResult =
        when (val verdict = config.targetPolicy.verify(config.target)) {
            TargetVerdict.Allowed -> CheckResult(POLICY, CheckStatus.OK, "${PetekConfig.masked(config.target)} is allowed")
            is TargetVerdict.Refused -> CheckResult(POLICY, CheckStatus.FAILED, verdict.reason)
        }

    private suspend fun targetReachable(): CheckResult =
        when (val answer = http.get(config.target)) {
            is HttpCheck.Answered -> {
                val status = if (answer.status < SERVER_ERROR) CheckStatus.OK else CheckStatus.FAILED
                CheckResult(TARGET, status, "$answer from ${PetekConfig.masked(config.target)}")
            }

            is HttpCheck.Unreachable -> {
                CheckResult(TARGET, CheckStatus.FAILED, answer.error)
            }
        }

    private suspend fun chromium(): CheckResult {
        val engine = container.browserEngine
        val browserConfig = container.browserConfig(headless = true)
        return try {
            val factory = engine.start(browserConfig)
            factory.open(SessionOptions(label = "doctor", baseUrl = config.target)).close()
            CheckResult(CHROMIUM, CheckStatus.OK, "Chromium started (${browserConfig.topology.name.lowercase().replace('_', '-')})")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CheckResult(CHROMIUM, CheckStatus.FAILED, HttpProbe.describe(e))
        } finally {
            engine.stop()
        }
    }

    private suspend fun mailpit(): CheckResult {
        val url = URI(config.mailpitUrl.toString().trimEnd('/') + MAILPIT_INFO)
        return when (val answer = http.get(url)) {
            is HttpCheck.Answered -> {
                if (answer.status == OK_STATUS) {
                    CheckResult(MAILPIT, CheckStatus.OK, "$answer from ${PetekConfig.masked(url)}")
                } else {
                    CheckResult(MAILPIT, CheckStatus.FAILED, "$answer from ${PetekConfig.masked(url)}; is this Mailpit?")
                }
            }

            is HttpCheck.Unreachable -> {
                CheckResult(MAILPIT, CheckStatus.FAILED, "${answer.error}; start it with `docker compose up -d`")
            }
        }
    }

    private suspend fun testApi(): CheckResult {
        val oracle = container.oracle
        if (!oracle.isAvailable) {
            return CheckResult(
                TEST_API,
                CheckStatus.FAILED,
                "PETEK_TEST_TOKEN is empty: oracle assertions are skipped and teardown cannot run",
            )
        }
        return try {
            val status = oracle.get(PROBE_PATH).status
            when (status) {
                OK_STATUS, NOT_FOUND -> CheckResult(TEST_API, CheckStatus.OK, "token accepted (HTTP $status for GET $PROBE_PATH)")
                UNAUTHORIZED -> CheckResult(TEST_API, CheckStatus.FAILED, "HTTP 401: the target rejects PETEK_TEST_TOKEN")
                else -> CheckResult(TEST_API, CheckStatus.FAILED, "HTTP $status for GET $PROBE_PATH (expected 200 or 404)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CheckResult(TEST_API, CheckStatus.FAILED, "connection error: ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun llm(): CheckResult {
        val client = container.diagnosticLlm()
        val who = "${config.llmProvider.key} (${config.llmModel})"
        return try {
            val response = client.complete(PING)
            if (response.output["ok"] == JsonPrimitive(true)) {
                CheckResult(LLM, CheckStatus.OK, "$who answered a structured request (model ${response.model})")
            } else {
                CheckResult(LLM, CheckStatus.FAILED, "$who answered, but not as asked: ${response.output}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            CheckResult(LLM, CheckStatus.FAILED, "$who: ${e.message}")
        } catch (e: Exception) {
            CheckResult(LLM, CheckStatus.FAILED, "$who: ${HttpProbe.describe(e)}")
        } finally {
            (client as? AutoCloseable)?.close()
        }
    }

    private fun skipped(name: String) = CheckResult(name, CheckStatus.SKIPPED, "not contacted: the target policy refuses the target")

    companion object {
        const val CONFIGURATION = "Configuration"
        const val POLICY = "Target policy"
        const val TARGET = "Target reachable"
        const val CHROMIUM = "Chromium"
        const val MAILPIT = "Mailpit"
        const val TEST_API = "Test API"
        const val LLM = "LLM provider"

        /** A fake number: a 404 (no OTP) or 200 proves the token is accepted without touching real data. */
        const val PROBE_PATH = "/test/otp/%2B994500000000"
        private const val MAILPIT_INFO = "/api/v1/info"
        private const val OK_STATUS = 200
        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
        private const val SERVER_ERROR = 500

        /** The rows shown when the configuration itself cannot be loaded. */
        fun configurationFailed(problems: List<String>): List<CheckResult> =
            listOf(CheckResult(CONFIGURATION, CheckStatus.FAILED, problems.joinToString("; "))) +
                listOf(POLICY, TARGET, CHROMIUM, MAILPIT, TEST_API, LLM).map {
                    CheckResult(it, CheckStatus.SKIPPED, "not checked: the configuration is invalid")
                }

        fun configurationValid(config: PetekConfig): CheckResult =
            CheckResult(CONFIGURATION, CheckStatus.OK, "target ${PetekConfig.masked(config.target)}, evidence ${config.evidenceDir}")

        private val PING =
            LlmRequest(
                system = "You are a health check. Answer only with the JSON object you are asked for.",
                messages = listOf(LlmMessage(LlmRole.USER, "Answer with {\"ok\": true}.")),
                responseSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") { putJsonObject("ok") { put("type", "boolean") } }
                        putJsonArray("required") { add("ok") }
                        put("additionalProperties", false)
                    },
                maxOutputTokens = 256,
                label = "doctor",
            )
    }
}

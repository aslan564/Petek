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

package az.petek.app.diagnostics

import az.petek.app.config.MailSource
import az.petek.app.config.PetekConfig
import az.petek.app.config.WebUrls
import az.petek.app.di.AppContainer
import az.petek.browser.domain.SessionOptions
import az.petek.core.security.TargetVerdict
import az.petek.llm.application.FallbackLlmClient
import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmMessage
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmRole
import az.petek.ownership.domain.OwnershipStatus
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
import java.time.Instant

/** [NOT_USED]: the part is switched off on purpose (e.g. no test API), so it neither passes nor fails the doctor. */
enum class CheckStatus { OK, FAILED, SKIPPED, NOT_USED }

/** One line of the `petek doctor` table. [detail] is shown verbatim (errors included) and never contains a secret. */
data class CheckResult(
    val name: String,
    val status: CheckStatus,
    val detail: String,
)

/**
 * `petek doctor`: checks that everything a run needs is in place, independently of each other (and concurrently,
 * since the browser and the LLM take seconds): the target policy, the target answering HTTP, Chromium starting,
 * the test inbox answering (Mailpit, or the target's `GET /test/emails` when `PETEK_MAIL_SOURCE=test-api`), the
 * target's test API accepting the token, the site's ownership being proved (runs write only then, ADR-0012; loopback
 * and private addresses need no proof), and the LLM provider answering one tiny structured
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
                async { mail(allowed) },
                async { if (allowed) testApi() else skipped(TEST_API) },
                async { if (allowed) ownership() else skipped(OWNERSHIP) },
                async { llm() },
            ).awaitAll()
        }
    }

    /** The target and, when it lives elsewhere, the test API are both judged: the API writes and deletes (rule 8). */
    private fun policy(): CheckResult {
        val judged = listOf(config.target, config.testApiBase).distinct()
        val refused = judged.map { config.targetPolicy.verify(WebUrls.canonical(it)) }.filterIsInstance<TargetVerdict.Refused>()
        return if (refused.isEmpty()) {
            CheckResult(POLICY, CheckStatus.OK, judged.joinToString(", ") { PetekConfig.masked(it) } + " allowed")
        } else {
            CheckResult(POLICY, CheckStatus.FAILED, refused.joinToString("; ") { it.reason })
        }
    }

    private suspend fun targetReachable(): CheckResult =
        when (val answer = http.get(config.target)) {
            is HttpCheck.Answered -> {
                val cdn = CdnErrorPage.of(answer)
                val status = if (answer.status < SERVER_ERROR && cdn == null) CheckStatus.OK else CheckStatus.FAILED
                CheckResult(TARGET, status, "$answer from ${PetekConfig.masked(config.target)}" + cdn?.let { ": $it" }.orEmpty())
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

    /** The test inbox of `PETEK_MAIL_SOURCE`; the target's one is not contacted when the policy refuses the target. */
    private suspend fun mail(allowed: Boolean): CheckResult =
        when (config.mailSource) {
            MailSource.MAILPIT -> mailpit()
            MailSource.TEST_API -> if (allowed) testApiMail() else skipped(MAIL)
            MailSource.IMAP -> imapMail()
            MailSource.MANUAL -> manualMail()
        }

    /** `PETEK_MAIL_SOURCE=imap`: logs in and searches the owner's box for a fake address (nothing is read or changed). */
    private suspend fun imapMail(): CheckResult {
        val imap = checkNotNull(config.imap)
        return try {
            container.mailbox.findRecent(MAIL_PROBE_ADDRESS, Instant.now(), unreadOnly = false, limit = 1)
            val plus = config.mailInbox?.let { ", testers get its + addresses ($it)" }.orEmpty()
            CheckResult(MAIL, CheckStatus.OK, "IMAP: logged in to $imap$plus")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CheckResult(MAIL, CheckStatus.FAILED, "IMAP: ${e.message ?: e::class.simpleName}")
        }
    }

    /** `PETEK_MAIL_SOURCE=manual`: nothing to contact; the owner types every code into the panel. */
    private fun manualMail(): CheckResult =
        CheckResult(
            MAIL,
            CheckStatus.OK,
            "manual: you type each code into the panel (Kodu daxil et); meant for the explorer's 1-3 sessions, not a swarm",
        )

    private suspend fun mailpit(): CheckResult {
        val url = URI(config.mailpitUrl.toString().trimEnd('/') + MAILPIT_INFO)
        return when (val answer = http.get(url)) {
            is HttpCheck.Answered -> {
                if (answer.status == OK_STATUS) {
                    CheckResult(MAIL, CheckStatus.OK, "Mailpit: $answer from ${PetekConfig.masked(url)}")
                } else {
                    CheckResult(MAIL, CheckStatus.FAILED, "Mailpit: $answer from ${PetekConfig.masked(url)}; is this Mailpit?")
                }
            }

            is HttpCheck.Unreachable -> {
                CheckResult(MAIL, CheckStatus.FAILED, "Mailpit: ${answer.error}; start it with `docker compose up -d`")
            }
        }
    }

    /** `PETEK_MAIL_SOURCE=test-api`: the target's `GET /test/emails` must answer for a fake address (nothing is written). */
    private suspend fun testApiMail(): CheckResult {
        val oracle = container.oracle
        if (!oracle.isAvailable) return CheckResult(MAIL, CheckStatus.FAILED, "test API mail needs PETEK_TEST_TOKEN")
        val path = "$MAIL_PROBE_PATH@${config.mailDomain}"
        return try {
            when (val status = oracle.get(path).status) {
                OK_STATUS -> CheckResult(MAIL, CheckStatus.OK, "test API mail: HTTP 200 for GET $path")
                UNAUTHORIZED -> CheckResult(MAIL, CheckStatus.FAILED, "test API mail: HTTP 401, the target rejects PETEK_TEST_TOKEN")
                NOT_FOUND -> CheckResult(MAIL, CheckStatus.FAILED, "test API mail: HTTP 404, the target has no GET /test/emails")
                else -> CheckResult(MAIL, CheckStatus.FAILED, "test API mail: HTTP $status for GET $path (expected 200)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CheckResult(MAIL, CheckStatus.FAILED, "test API mail: connection error: ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun testApi(): CheckResult {
        if (!config.oracle) {
            return CheckResult(
                TEST_API,
                CheckStatus.NOT_USED,
                "not used (PETEK_ORACLE=none): findings rest on the screen and the network, no test API is asked",
            )
        }
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
        val version = container.llmBinaryVersion()?.let { ", ${config.effectiveLlmBin.orEmpty()} $it" }.orEmpty()
        val who = "${config.llmProvider} (${config.llmModelLabel}$version)"
        val fallbacks =
            config.llmFallbacks
                .takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "; then ")
                .orEmpty()
        val why = " [${config.llmProviderReason}$fallbacks]"
        var client: LlmClient? = null
        return try {
            // Built inside the try: a provider that cannot even be constructed is a failed row, not a crashed doctor.
            val response = container.diagnosticLlm().also { client = it }.complete(PING)
            val passed = (client as? FallbackLlmClient)?.skipped.orEmpty()
            val answered =
                client
                    ?.provider
                    ?.takeIf { it != config.llmProvider }
                    ?.let { " by $it" }
                    .orEmpty()
            val note = passed.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "; passed over: ").orEmpty()
            if (response.output["ok"] == JsonPrimitive(true)) {
                CheckResult(LLM, CheckStatus.OK, "$who answered a structured request$answered (model ${response.model})$why$note")
            } else {
                CheckResult(LLM, CheckStatus.FAILED, "$who answered, but not as asked: ${response.output}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            CheckResult(LLM, CheckStatus.FAILED, "$who: ${e.message}$why")
        } catch (e: Exception) {
            CheckResult(LLM, CheckStatus.FAILED, "$who: ${HttpProbe.describe(e)}")
        } finally {
            (client as? AutoCloseable)?.close()
        }
    }

    /** Looks for the proof now and remembers nothing, so the doctor leaves no database behind; never writes to the site. */
    private suspend fun ownership(): CheckResult =
        when (val status = container.ownership.inspect(config.target)) {
            is OwnershipStatus.Exempt -> {
                CheckResult(OWNERSHIP, CheckStatus.OK, "no proof needed: ${status.host} is a loopback or private-network address")
            }

            is OwnershipStatus.Verified -> {
                CheckResult(OWNERSHIP, CheckStatus.OK, "proved: the ${status.record.method.key} carries this machine's token")
            }

            is OwnershipStatus.Unverified -> {
                val challenge = status.challenge
                val dns = challenge.dnsName?.let { " or the DNS TXT record $it" }.orEmpty()
                CheckResult(
                    OWNERSHIP,
                    CheckStatus.FAILED,
                    "not proved, so runs are refused and the explorer only reads: publish ${challenge.proofLine} in " +
                        "${challenge.fileUrl}$dns (petek verify shows how)",
                )
            }
        }

    private fun skipped(name: String) = CheckResult(name, CheckStatus.SKIPPED, "not contacted: the target policy refuses the target")

    companion object {
        private const val MAIL_PROBE_ADDRESS = "petek-doctor-probe@example.invalid"
        const val CONFIGURATION = "Configuration"
        const val POLICY = "Target policy"
        const val TARGET = "Target reachable"
        const val CHROMIUM = "Chromium"
        const val MAIL = "Test inbox"
        const val TEST_API = "Test API"
        const val OWNERSHIP = "Site ownership"
        const val LLM = "LLM provider"

        /** A fake number: a 404 (no OTP) or 200 proves the token is accepted without touching real data. */
        const val PROBE_PATH = "/test/otp/%2B994500000000"

        /** A fake recipient on the test domain: an empty list (200) proves the mail endpoint answers. */
        private const val MAIL_PROBE_PATH = "/test/emails?to=petek-doctor"
        private const val MAILPIT_INFO = "/api/v1/info"
        private const val OK_STATUS = 200
        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
        private const val SERVER_ERROR = 500

        /** The rows shown when the configuration itself cannot be loaded. */
        fun configurationFailed(problems: List<String>): List<CheckResult> =
            listOf(CheckResult(CONFIGURATION, CheckStatus.FAILED, problems.joinToString("; "))) +
                listOf(POLICY, TARGET, CHROMIUM, MAIL, TEST_API, OWNERSHIP, LLM).map {
                    CheckResult(it, CheckStatus.SKIPPED, "not checked: the configuration is invalid")
                }

        fun configurationValid(config: PetekConfig): CheckResult =
            CheckResult(
                CONFIGURATION,
                CheckStatus.OK,
                "target ${PetekConfig.masked(config.target)}, evidence ${config.evidenceDir}" +
                    if (config.browserIgnoreTlsErrors) "; TLS certificate errors are ignored (PETEK_BROWSER_IGNORE_TLS_ERRORS)" else "",
            )

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

/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.diagnostics

import az.petek.app.config.MailSource
import az.petek.app.config.PetekConfig
import az.petek.app.config.WebUrls
import az.petek.app.di.AppContainer
import az.petek.browser.domain.SessionOptions
import az.petek.core.security.TargetVerdict
import az.petek.llm.domain.LlmClient
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
 * the test inbox answering (Mailpit, or the target's `GET /test/emails` when `PETEK_MAIL_SOURCE=test-api`), the
 * target's test API accepting the token, and the LLM provider answering one tiny structured
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

    /** The test inbox of `PETEK_MAIL_SOURCE`; the target's one is not contacted when the policy refuses the target. */
    private suspend fun mail(allowed: Boolean): CheckResult =
        when (config.mailSource) {
            MailSource.MAILPIT -> mailpit()
            MailSource.TEST_API -> if (allowed) testApiMail() else skipped(MAIL)
        }

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
        val who = "${config.llmProvider.key} (${config.llmModel})"
        var client: LlmClient? = null
        return try {
            // Built inside the try: a provider that cannot even be constructed is a failed row, not a crashed doctor.
            val response = container.diagnosticLlm().also { client = it }.complete(PING)
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
        const val MAIL = "Test inbox"
        const val TEST_API = "Test API"
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
                listOf(POLICY, TARGET, CHROMIUM, MAIL, TEST_API, LLM).map {
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

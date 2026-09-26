/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.config

import az.petek.browser.domain.BrowserTopology
import az.petek.core.security.Secret
import az.petek.core.security.TargetPolicy
import az.petek.core.security.TargetVerdict
import az.petek.llm.domain.LlmProviderId
import java.net.URI
import java.nio.file.Path

/**
 * Typed, validated settings of one Pətək process, read from `.env` and the environment ([ConfigLoader]); every key
 * of `.env.example` has a field here. Paths are absolute (resolved against the working directory at load time).
 *
 * Secrets stay wrapped in [Secret]; [toString] shows only whether they are set, and credentials in URLs are masked,
 * so a config can be logged or printed safely.
 *
 * @property target the system under test (`PETEK_TARGET`); it replaces `campaign.target` of every campaign.
 * @property productionHosts hosts refused as a target unless [allowProduction] (CLAUDE.md rule 8).
 * @property testToken `X-Test-Token` for the target's `/test/...` API; null disables oracle assertions.
 * @property testApiUrl where the `/test/...` API lives when it is not on the target's own origin (`PETEK_TEST_API_URL`,
 *   e.g. KadroHR's `api.` host); null means the target itself, see [testApiBase].
 * @property mailSource where verification mail is read from (`PETEK_MAIL_SOURCE`); the test API needs [testToken].
 * @property identitySecret key of the password derivation; from `PETEK_IDENTITY_SECRET` or `~/.petek/identity.secret`.
 * @property llmConcurrency LLM calls allowed in flight at once across all agents.
 * @property dbPath the evidence database (`PETEK_DB`, default `<evidenceDir>/petek.db`).
 */
data class PetekConfig(
    val target: URI,
    val productionHosts: Set<String> = DEFAULT_PRODUCTION_HOSTS,
    val allowProduction: Boolean = false,
    val testToken: Secret? = null,
    val testApiUrl: URI? = null,
    val mailSource: MailSource = MailSource.MAILPIT,
    val mailpitUrl: URI = URI(DEFAULT_MAILPIT_URL),
    val mailDomain: String = DEFAULT_MAIL_DOMAIN,
    val identitySecret: Secret,
    val llmProvider: LlmProviderId = LlmProviderId.CLAUDE_CLI,
    val llmModel: String = DEFAULT_LLM_MODEL,
    val claudeBin: String = DEFAULT_CLAUDE_BIN,
    val llmConcurrency: Int = DEFAULT_LLM_CONCURRENCY,
    val anthropicApiKey: Secret? = null,
    val browserHeadless: Boolean = true,
    val browserTopology: BrowserTopology = BrowserTopology.SHARED_SERVER,
    /** Accept TLS certificates the browser does not trust (self-signed staging, re-signing proxy). Off by default. */
    val browserIgnoreTlsErrors: Boolean = false,
    val evidenceDir: Path,
    val dbPath: Path = evidenceDir.resolve(DEFAULT_DB_FILE),
) {
    init {
        require(llmConcurrency >= 1) { "llmConcurrency must be at least 1, was $llmConcurrency" }
        require(!identitySecret.isBlank) { "identitySecret must not be blank" }
        require(llmProvider != LlmProviderId.ANTHROPIC_API || anthropicApiKey?.isBlank == false) {
            "the anthropic-api provider needs an API key"
        }
        require(mailSource != MailSource.TEST_API || testToken?.isBlank == false) {
            "the test-api mail source needs the test token"
        }
        require(testApiUrl == null || targetPolicy.verify(testApiUrl) == TargetVerdict.Allowed) {
            "the test API URL names a production host; set PETEK_ALLOW_PRODUCTION=true to allow it"
        }
    }

    /** The production guard for [target] and for URLs given on the command line. */
    val targetPolicy: TargetPolicy get() = TargetPolicy(productionHosts, allowProduction)

    /** Base address of the `/test/...` API (the oracle and the test-API mailbox): [testApiUrl], else [target]. */
    val testApiBase: URI get() = testApiUrl ?: target

    /** Where the log file lives: `<evidenceDir>/logs`. */
    val logDirectory: Path get() = evidenceDir.resolve("logs")

    override fun toString(): String =
        "PetekConfig(target=${masked(target)}, productionHosts=$productionHosts, allowProduction=$allowProduction, " +
            "testToken=${setOrUnset(testToken)}, testApiUrl=${testApiUrl?.let(::masked)}, mailSource=${mailSource.key}, " +
            "mailpitUrl=${masked(mailpitUrl)}, mailDomain=$mailDomain, " +
            "identitySecret=***, llmProvider=${llmProvider.key}, llmModel=$llmModel, claudeBin=$claudeBin, " +
            "llmConcurrency=$llmConcurrency, anthropicApiKey=${setOrUnset(anthropicApiKey)}, " +
            "browserHeadless=$browserHeadless, browserTopology=$browserTopology, browserIgnoreTlsErrors=$browserIgnoreTlsErrors, " +
            "evidenceDir=$evidenceDir, dbPath=$dbPath)"

    companion object {
        val DEFAULT_PRODUCTION_HOSTS: Set<String> = setOf("kadrohr.com", "www.kadrohr.com")
        const val DEFAULT_MAILPIT_URL = "http://localhost:8025"
        const val DEFAULT_MAIL_DOMAIN = "test.kadrohr.com"

        /** Effort-controllable, so agents can run at `--effort low` (docs: LLM defaults). */
        const val DEFAULT_LLM_MODEL = "claude-sonnet-5"
        const val DEFAULT_CLAUDE_BIN = "claude"
        const val DEFAULT_LLM_CONCURRENCY = 6
        const val DEFAULT_EVIDENCE_DIR = "evidence"
        const val DEFAULT_DB_FILE = "petek.db"

        /** Shows a URL with its user info (credentials) replaced by three asterisks. */
        fun masked(url: URI): String = url.rawUserInfo?.let { url.toString().replace("$it@", "***@") } ?: url.toString()

        private fun setOrUnset(secret: Secret?): String = if (secret == null || secret.isBlank) "unset" else "set"
    }
}

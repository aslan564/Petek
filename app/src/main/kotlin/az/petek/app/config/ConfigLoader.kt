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
import java.net.URISyntaxException
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Builds a [PetekConfig] from `.env` and the process environment: real environment variables override `.env`
 * (so CI can inject secrets without a file), then every value is parsed and validated. All problems are collected
 * and thrown together as one [ConfigException]; no message contains a value, only variable names.
 *
 * Defaults (when a key is missing or blank) follow `.env.example`, except `PETEK_TARGET`, which is required: running
 * tests against an unintended system is the one mistake a default must not make. Relative paths are resolved
 * against [workingDirectory]; URLs are stored in their canonical spelling ([WebUrls.canonical]), so the production
 * guard sees the host it compares. A blank `PETEK_IDENTITY_SECRET` falls back to [identitySecrets] (by default the file
 * `~/.petek/identity.secret`), which is only consulted when no explicit secret is configured.
 */
class ConfigLoader(
    private val environment: Map<String, String>,
    private val workingDirectory: Path,
    private val identitySecrets: IdentitySecretSource,
) {
    /** Loads [envFile] (absent = empty) under the environment and validates the result. */
    fun load(envFile: Path?): PetekConfig {
        val fileValues = envFile?.let { EnvFile.load(workingDirectory.resolve(it)) }.orEmpty()
        return fromValues(fileValues + environment)
    }

    /** Validates already merged values (`.env` + environment). */
    fun fromValues(values: Map<String, String>): PetekConfig = Reading(values).config()

    /** One validation pass collecting problems. */
    private inner class Reading(
        private val values: Map<String, String>,
    ) {
        private val problems = mutableListOf<String>()

        fun config(): PetekConfig {
            val target = url(Keys.TARGET, default = null)
            if (target == null && text(Keys.TARGET) == null) problems += "${Keys.TARGET} is required (the URL of the system under test)"
            val productionHosts = hosts(Keys.PRODUCTION_HOSTS)
            val allowProduction = flag(Keys.ALLOW_PRODUCTION, default = false)
            val testToken = token(Keys.TEST_TOKEN)
            val testApiUrl = url(Keys.TEST_API_URL, default = null)
            // The test API writes and deletes: it is judged by the same production-host policy as the target (rule 8).
            testApiUrl?.let { api ->
                val verdict = TargetPolicy(productionHosts, allowProduction).verify(api)
                if (verdict is TargetVerdict.Refused) problems += "${Keys.TEST_API_URL}: ${verdict.reason}"
            }
            val mailSource = mailSource()
            if (mailSource == MailSource.TEST_API && testToken == null) {
                problems += "${Keys.TEST_TOKEN} is required when ${Keys.MAIL_SOURCE} is ${MailSource.TEST_API.key}"
            }
            val mailpitUrl = url(Keys.MAILPIT_URL, default = PetekConfig.DEFAULT_MAILPIT_URL)
            val mailDomain = mailDomain()
            val provider = provider()
            val model = text(Keys.LLM_MODEL) ?: PetekConfig.DEFAULT_LLM_MODEL
            val claudeBin = text(Keys.CLAUDE_BIN) ?: PetekConfig.DEFAULT_CLAUDE_BIN
            val concurrency = concurrency()
            val apiKey = text(Keys.ANTHROPIC_API_KEY)?.let(::Secret)
            if (provider == LlmProviderId.ANTHROPIC_API && apiKey == null) {
                problems += "${Keys.ANTHROPIC_API_KEY} is required when ${Keys.LLM_PROVIDER} is ${LlmProviderId.ANTHROPIC_API.key}"
            }
            val headless = flag(Keys.BROWSER_HEADLESS, default = true)
            val ignoreTlsErrors = flag(Keys.BROWSER_IGNORE_TLS_ERRORS, default = false)
            val topology = topology()
            val evidenceDir = path(Keys.EVIDENCE_DIR, PetekConfig.DEFAULT_EVIDENCE_DIR)
            val dbPath = if (text(Keys.DB) != null) path(Keys.DB, default = null) else evidenceDir?.resolve(PetekConfig.DEFAULT_DB_FILE)
            val identitySecret = identitySecret()
            if (problems.isNotEmpty()) throw ConfigException(problems.toList())
            return PetekConfig(
                target = checkNotNull(target),
                productionHosts = productionHosts,
                allowProduction = allowProduction,
                testToken = testToken,
                testApiUrl = testApiUrl,
                mailSource = checkNotNull(mailSource),
                mailpitUrl = checkNotNull(mailpitUrl),
                mailDomain = checkNotNull(mailDomain),
                identitySecret = checkNotNull(identitySecret),
                llmProvider = checkNotNull(provider),
                llmModel = model,
                claudeBin = claudeBin,
                llmConcurrency = checkNotNull(concurrency),
                anthropicApiKey = apiKey,
                browserHeadless = headless,
                browserTopology = checkNotNull(topology),
                browserIgnoreTlsErrors = ignoreTlsErrors,
                evidenceDir = checkNotNull(evidenceDir),
                dbPath = checkNotNull(dbPath),
            )
        }

        /** The trimmed value, or null when the key is missing or blank. */
        private fun text(key: String): String? = values[key]?.trim()?.takeIf { it.isNotEmpty() }

        private fun url(
            key: String,
            default: String?,
        ): URI? {
            val raw = text(key) ?: default ?: return null
            val url =
                try {
                    URI(raw)
                } catch (_: URISyntaxException) {
                    problems += "$key is not a valid URL"
                    return null
                }
            val problem =
                when {
                    url.scheme?.lowercase() !in WEB_SCHEMES -> "$key must be an absolute http(s) URL"
                    url.host.isNullOrBlank() -> "$key must name a host"
                    url.rawUserInfo != null -> "$key must not contain credentials (user:password@)"
                    else -> null
                }
            if (problem != null) {
                problems += problem
                return null
            }
            return WebUrls.canonical(url)
        }

        private fun hosts(key: String): Set<String> {
            val raw = text(key) ?: return PetekConfig.DEFAULT_PRODUCTION_HOSTS
            val hosts =
                raw
                    .split(',')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
            hosts.filterNot { HOST.matches(it) }.forEach { problems += "$key contains '$it', which is not a host name" }
            return hosts.toSet()
        }

        private fun flag(
            key: String,
            default: Boolean,
        ): Boolean {
            val raw = text(key)?.lowercase() ?: return default
            return when (raw) {
                in TRUE -> true
                in FALSE -> false
                else -> default.also { problems += "$key must be true or false" }
            }
        }

        /** The value is never echoed: a mistyped token is still a token. */
        private fun token(key: String): Secret? {
            val raw = text(key) ?: return null
            if (raw.any(Char::isISOControl)) {
                problems += "$key contains a control character; it cannot be sent as an HTTP header"
                return null
            }
            return Secret(raw)
        }

        private fun mailDomain(): String? {
            val domain = (text(Keys.MAIL_DOMAIN) ?: PetekConfig.DEFAULT_MAIL_DOMAIN).lowercase().removePrefix("@")
            if (!HOST.matches(domain)) {
                problems += "${Keys.MAIL_DOMAIN} must be a bare domain such as test.kadrohr.com"
                return null
            }
            return domain
        }

        private fun mailSource(): MailSource? {
            val raw = text(Keys.MAIL_SOURCE) ?: return MailSource.MAILPIT
            val source = MailSource.fromKey(raw)
            if (source == null) problems += "${Keys.MAIL_SOURCE} must be one of ${MailSource.entries.joinToString { it.key }}, was '$raw'"
            return source
        }

        private fun provider(): LlmProviderId? {
            val raw = text(Keys.LLM_PROVIDER) ?: return LlmProviderId.CLAUDE_CLI
            val provider = LlmProviderId.fromKey(raw)
            if (provider == null) {
                problems += "${Keys.LLM_PROVIDER} must be one of ${LlmProviderId.entries.joinToString { it.key }}, was '$raw'"
            }
            return provider
        }

        private fun concurrency(): Int? {
            val raw = text(Keys.LLM_CONCURRENCY) ?: return PetekConfig.DEFAULT_LLM_CONCURRENCY
            val value = raw.toIntOrNull()
            if (value == null || value !in 1..MAX_LLM_CONCURRENCY) {
                problems += "${Keys.LLM_CONCURRENCY} must be a whole number between 1 and $MAX_LLM_CONCURRENCY, was '$raw'"
                return null
            }
            return value
        }

        private fun topology(): BrowserTopology? {
            val raw = text(Keys.BROWSER_TOPOLOGY) ?: return BrowserTopology.SHARED_SERVER
            val topology = TOPOLOGIES[raw.lowercase().replace('_', '-')]
            if (topology == null) problems += "${Keys.BROWSER_TOPOLOGY} must be one of ${TOPOLOGIES.keys.joinToString()}, was '$raw'"
            return topology
        }

        private fun path(
            key: String,
            default: String?,
        ): Path? {
            val value = text(key) ?: default ?: return null
            return try {
                workingDirectory.resolve(value).toAbsolutePath().normalize()
            } catch (_: InvalidPathException) {
                problems += "$key is not a valid path"
                null
            }
        }

        private fun identitySecret(): Secret? {
            val explicit = values[Keys.IDENTITY_SECRET]?.trim().orEmpty()
            if (explicit.isNotEmpty()) {
                if (explicit.length < MIN_SECRET_LENGTH) {
                    problems += "${Keys.IDENTITY_SECRET} must be at least $MIN_SECRET_LENGTH characters long " +
                        "(or empty to use ~/.petek/${IdentitySecretFile.FILE_NAME})"
                    return null
                }
                return Secret(explicit)
            }
            return try {
                identitySecrets.secret()
            } catch (e: ConfigException) {
                problems += e.problems
                null
            }
        }
    }

    /** Environment variable names, as in `.env.example`. */
    object Keys {
        const val TARGET = "PETEK_TARGET"
        const val PRODUCTION_HOSTS = "PETEK_PRODUCTION_HOSTS"
        const val ALLOW_PRODUCTION = "PETEK_ALLOW_PRODUCTION"
        const val TEST_TOKEN = "PETEK_TEST_TOKEN"
        const val TEST_API_URL = "PETEK_TEST_API_URL"
        const val MAIL_SOURCE = "PETEK_MAIL_SOURCE"
        const val MAILPIT_URL = "PETEK_MAILPIT_URL"
        const val MAIL_DOMAIN = "PETEK_MAIL_DOMAIN"
        const val IDENTITY_SECRET = "PETEK_IDENTITY_SECRET"
        const val LLM_PROVIDER = "PETEK_LLM_PROVIDER"
        const val LLM_MODEL = "PETEK_LLM_MODEL"
        const val CLAUDE_BIN = "PETEK_CLAUDE_BIN"
        const val LLM_CONCURRENCY = "PETEK_LLM_CONCURRENCY"
        const val ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY"
        const val BROWSER_HEADLESS = "PETEK_BROWSER_HEADLESS"
        const val BROWSER_TOPOLOGY = "PETEK_BROWSER_TOPOLOGY"
        const val BROWSER_IGNORE_TLS_ERRORS = "PETEK_BROWSER_IGNORE_TLS_ERRORS"
        const val EVIDENCE_DIR = "PETEK_EVIDENCE_DIR"
        const val DB = "PETEK_DB"
    }

    private companion object {
        const val MAX_LLM_CONCURRENCY = 64
        const val MIN_SECRET_LENGTH = 16
        val WEB_SCHEMES = setOf("http", "https")
        val TRUE = setOf("true", "yes", "on", "1")
        val FALSE = setOf("false", "no", "off", "0")
        val HOST = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*")
        val TOPOLOGIES = mapOf("shared-server" to BrowserTopology.SHARED_SERVER, "per-session" to BrowserTopology.PER_SESSION)
    }
}

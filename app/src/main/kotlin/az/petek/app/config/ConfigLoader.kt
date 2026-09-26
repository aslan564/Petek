/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.config

import az.petek.app.di.LlmProviders
import az.petek.browser.domain.BrowserProxy
import az.petek.browser.domain.BrowserTopology
import az.petek.campaign.domain.SecretRef
import az.petek.campaign.domain.TargetSpecException
import az.petek.campaign.infrastructure.YamlTargetSpecSource
import az.petek.core.model.WorkingLanguage
import az.petek.core.security.Secret
import az.petek.core.security.TargetPolicy
import az.petek.core.security.TargetVerdict
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.infrastructure.http.StructuredMode
import az.petek.mail.infrastructure.ImapSettings
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
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
    /** Whether a CLI is installed; used only to resolve `PETEK_LLM_PROVIDER=auto`. */
    private val onPath: (String) -> Boolean = LlmProviderResolver.pathLookup(environment),
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
            val profiles = targets()
            val targets = profiles.map { it.first }
            val namedProfile = text(Keys.TARGET)?.let { raw -> profiles.firstOrNull { it.first.spec.name == raw.lowercase() } }
            namedProfile?.second?.let { problems += it }
            val named = namedProfile?.first
            val target = if (named != null) WebUrls.canonical(named.spec.url) else url(Keys.TARGET, default = null)
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
            val mailInbox = mailInbox()
            val mailDomain = mailInbox?.substringAfter('@') ?: mailDomain()
            val imap = if (mailSource == MailSource.IMAP) imap(mailInbox) else null
            val resolution = provider()
            val provider = resolution?.provider
            val model = text(Keys.LLM_MODEL)
            val bin = text(Keys.LLM_BIN) ?: text(Keys.CLAUDE_BIN)
            val baseUrl = url(Keys.LLM_BASE_URL, default = null) ?: resolution?.baseUrl
            val apiKey = provider?.let(::apiKey)
            val structured = structured()
            val effort = text(Keys.LLM_EFFORT)
            when (provider) {
                LlmProviderKey.ANTHROPIC_API -> {
                    if (apiKey == null) {
                        problems += "${Keys.ANTHROPIC_API_KEY} (or ${Keys.LLM_API_KEY}) is required when ${Keys.LLM_PROVIDER} is $provider"
                    }
                }

                LlmProviderKey.OPENAI_COMPAT -> {
                    if (baseUrl == null) problems += "${Keys.LLM_BASE_URL} is required when ${Keys.LLM_PROVIDER} is $provider"
                    if (model == null) {
                        problems += "${Keys.LLM_MODEL} is required when ${Keys.LLM_PROVIDER} is $provider (${resolution.reason})"
                    }
                }

                else -> {
                    // The CLIs need no key or endpoint: they use the owner's own login.
                }
            }
            val concurrency = concurrency()
            val language = WorkingLanguage.of(text(Keys.LANGUAGE))
            val headless = flag(Keys.BROWSER_HEADLESS, default = true)
            val ignoreTlsErrors = flag(Keys.BROWSER_IGNORE_TLS_ERRORS, default = false)
            val topology = topology()
            val evidenceDir = path(Keys.EVIDENCE_DIR, PetekConfig.DEFAULT_EVIDENCE_DIR)
            val dbPath = if (text(Keys.DB) != null) path(Keys.DB, default = null) else evidenceDir?.resolve(PetekConfig.DEFAULT_DB_FILE)
            val identitySecret = identitySecret()
            val telemetry = telemetry()
            val oracle = oracle()
            val proxies = proxies()
            if (problems.isNotEmpty()) throw ConfigException(problems.toList())
            val loaded =
                PetekConfig(
                    target = checkNotNull(target),
                    productionHosts = productionHosts,
                    allowProduction = allowProduction,
                    testToken = testToken,
                    testApiUrl = testApiUrl,
                    mailSource = checkNotNull(mailSource),
                    mailpitUrl = checkNotNull(mailpitUrl),
                    mailDomain = checkNotNull(mailDomain),
                    mailInbox = mailInbox,
                    imap = imap,
                    identitySecret = checkNotNull(identitySecret),
                    llmProvider = checkNotNull(provider),
                    llmProviderReason = checkNotNull(resolution).reason,
                    llmModel = model,
                    llmBin = bin,
                    llmBaseUrl = baseUrl,
                    llmApiKey = apiKey,
                    llmStructured = checkNotNull(structured),
                    llmEffort = effort,
                    llmConcurrency = checkNotNull(concurrency),
                    language = language,
                    browserHeadless = headless,
                    browserTopology = checkNotNull(topology),
                    browserIgnoreTlsErrors = ignoreTlsErrors,
                    evidenceDir = checkNotNull(evidenceDir),
                    dbPath = checkNotNull(dbPath),
                    telemetry = telemetry,
                    targets = targets,
                    targetsDir = path(Keys.TARGETS_DIR, DEFAULT_TARGETS_DIR),
                    correlationHeader = flag(Keys.CORRELATION_HEADER, default = false),
                    traceLog = if (text(Keys.TRACE_LOG) != null) path(Keys.TRACE_LOG, default = null) else null,
                    oracle = oracle,
                    proxies = proxies,
                )
            // PETEK_TARGET naming a profile takes that profile's settings; a URL keeps the .env ones.
            return if (named != null) TargetProfileConfig.apply(loaded, named) else loaded
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
                problems += "${Keys.MAIL_DOMAIN} must be a bare domain such as test.example.com"
                return null
            }
            return domain
        }

        /** The owner's box: one plain address whose local part has no `+` of its own (Pətək adds the tag). */
        private fun mailInbox(): String? {
            val raw = text(Keys.MAIL_INBOX)?.lowercase() ?: return null
            if (!MAILBOX.matches(raw)) {
                problems += "${Keys.MAIL_INBOX} must be a plain e-mail address such as test@company.az (without '+')"
                return null
            }
            return raw
        }

        /** `PETEK_IMAP_*`; the user defaults to the owner's box, the port to 993 with TLS and 143 without. */
        private fun imap(mailInbox: String?): ImapSettings? {
            val host = text(Keys.IMAP_HOST)
            val user = text(Keys.IMAP_USER) ?: mailInbox
            val password = text(Keys.IMAP_PASSWORD)
            val tls = flag(Keys.IMAP_TLS, default = true)
            val required = "is required when ${Keys.MAIL_SOURCE} is ${MailSource.IMAP.key}"
            if (host == null) problems += "${Keys.IMAP_HOST} $required"
            if (user == null) problems += "${Keys.IMAP_USER} (or ${Keys.MAIL_INBOX}) $required"
            if (password == null) problems += "${Keys.IMAP_PASSWORD} $required"
            val defaultPort = if (tls) ImapSettings.DEFAULT_TLS_PORT else ImapSettings.DEFAULT_PLAIN_PORT
            val rawPort = text(Keys.IMAP_PORT)
            val port = if (rawPort == null) defaultPort else rawPort.toIntOrNull()?.takeIf { it in 1..MAX_PORT }
            if (port == null) problems += "${Keys.IMAP_PORT} must be a port number, was '$rawPort'"
            if (host == null || user == null || password == null || port == null) return null
            return ImapSettings(host, user, Secret(password), port, tls, text(Keys.IMAP_FOLDER) ?: ImapSettings.DEFAULT_FOLDER)
        }

        /**
         * `targets/<name>.yaml` (`PETEK_TARGETS_DIR`) with every `${VAR}` they reference resolved. A variable that is not set
         * leaves that secret empty (the site then runs without it, and `doctor` shows it); only the profile
         * `PETEK_TARGET` names must have all of its variables, see [config].
         */
        private fun targets(): List<Pair<ResolvedTarget, List<String>>> {
            val directory = path(Keys.TARGETS_DIR, DEFAULT_TARGETS_DIR) ?: return emptyList()
            val specs =
                try {
                    YamlTargetSpecSource().loadAll(directory)
                } catch (e: TargetSpecException) {
                    problems += e.issues.map { "${Keys.TARGETS_DIR} (${e.file}): $it" }
                    return emptyList()
                }
            return specs.map { spec ->
                val missing = mutableListOf<String>()

                fun resolve(ref: SecretRef?): Secret? {
                    if (ref == null) return null
                    val value = text(ref.variable)
                    if (value == null) missing += "${ref.variable}, referenced by target '${spec.name}', is not set"
                    return value?.let(::Secret)
                }
                ResolvedTarget(spec, resolve(spec.testToken), spec.accounts.map { ResolvedAccount.of(it, resolve(it.password)) }) to missing
            }
        }

        private fun mailSource(): MailSource? {
            val raw = text(Keys.MAIL_SOURCE) ?: return MailSource.MAILPIT
            val source = MailSource.fromKey(raw)
            if (source == null) problems += "${Keys.MAIL_SOURCE} must be one of ${MailSource.entries.joinToString { it.key }}, was '$raw'"
            return source
        }

        /** `auto` (or empty) goes through [LlmProviderResolver]; anything else must be a registered provider. */
        private fun provider(): LlmProviderResolver.Resolution? {
            val raw = text(Keys.LLM_PROVIDER)?.lowercase()
            val explicit =
                if (raw == null || raw == AUTO) {
                    null
                } else {
                    val key = LlmProviderKey.of(raw)?.takeIf { it in LlmProviders.KEYS }
                    if (key == null) {
                        problems += "${Keys.LLM_PROVIDER} must be $AUTO or one of ${LlmProviders.KEYS.joinToString()}, was '$raw'"
                        return null
                    }
                    key
                }
            return LlmProviderResolver(workingDirectory, onPath).resolve(explicit, values)
        }

        /** `PETEK_LLM_API_KEY`, else the provider's usual variable (never echoed). */
        private fun apiKey(provider: LlmProviderKey): Secret? {
            val aliases =
                when (provider) {
                    LlmProviderKey.ANTHROPIC_API -> listOf(Keys.ANTHROPIC_API_KEY)
                    LlmProviderKey.OPENAI_COMPAT -> listOf(Keys.OPENAI_API_KEY, Keys.GEMINI_API_KEY)
                    else -> emptyList()
                }
            return (listOf(Keys.LLM_API_KEY) + aliases).firstNotNullOfOrNull { text(it) }?.let(::Secret)
        }

        private fun structured(): StructuredMode? {
            val raw = text(Keys.LLM_STRUCTURED) ?: return StructuredMode.SCHEMA
            val mode = StructuredMode.fromKey(raw)
            if (mode == null) {
                problems += "${Keys.LLM_STRUCTURED} must be one of ${StructuredMode.entries.joinToString { it.key }}, was '$raw'"
            }
            return mode
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

        /**
         * `PETEK_PROXIES`: comma-separated `scheme://[user:password@]host:port` addresses (http, https, socks5). The
         * password becomes a [Secret]; a bad entry is a problem naming its position, never its text (it may hold one).
         */
        private fun proxies(): List<BrowserProxy> =
            text(Keys.PROXIES)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.mapIndexedNotNull { index, raw ->
                    proxy(raw)
                        ?: null.also { problems += "${Keys.PROXIES} entry ${index + 1} is not scheme://host:port" }
                }.orEmpty()

        private fun proxy(raw: String): BrowserProxy? {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()?.takeIf { it in PROXY_SCHEMES } ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            if (uri.port !in 1..MAX_PORT) return null
            val user = uri.rawUserInfo?.substringBefore(':')?.let { URLDecoder.decode(it, Charsets.UTF_8) }
            val password =
                uri.rawUserInfo
                    ?.takeIf { ':' in it }
                    ?.substringAfter(':')
                    ?.let { Secret(URLDecoder.decode(it, Charsets.UTF_8)) }
            return BrowserProxy("$scheme://$host:${uri.port}", user, password)
        }

        /** `PETEK_ORACLE`: `test-api` (default) asks the test API when a token is set; `none` never does. */
        private fun oracle(): Boolean =
            when (val raw = text(Keys.ORACLE)?.lowercase() ?: "test-api") {
                "test-api", "test_api" -> true
                "none" -> false
                else -> true.also { problems += "${Keys.ORACLE} must be test-api or none, was '$raw'" }
            }

        /** `off` (default) or `local`; nothing else, so no typo ever turns counting on. */
        private fun telemetry(): Boolean =
            when (val raw = text(Keys.TELEMETRY)?.lowercase() ?: "off") {
                "off" -> false
                "local" -> true
                else -> false.also { problems += "${Keys.TELEMETRY} must be off or local, was '$raw'" }
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
        const val MAIL_INBOX = "PETEK_MAIL_INBOX"
        const val TARGETS_DIR = "PETEK_TARGETS_DIR"
        const val CORRELATION_HEADER = "PETEK_CORRELATION_HEADER"
        const val TRACE_LOG = "PETEK_TRACE_LOG"
        const val IMAP_HOST = "PETEK_IMAP_HOST"
        const val IMAP_PORT = "PETEK_IMAP_PORT"
        const val IMAP_USER = "PETEK_IMAP_USER"
        const val IMAP_PASSWORD = "PETEK_IMAP_PASSWORD"
        const val IMAP_TLS = "PETEK_IMAP_TLS"
        const val IMAP_FOLDER = "PETEK_IMAP_FOLDER"
        const val IDENTITY_SECRET = "PETEK_IDENTITY_SECRET"
        const val LLM_PROVIDER = "PETEK_LLM_PROVIDER"
        const val LLM_MODEL = "PETEK_LLM_MODEL"
        const val LLM_BIN = "PETEK_LLM_BIN"

        /** Old name of [LLM_BIN], still read. */
        const val CLAUDE_BIN = "PETEK_CLAUDE_BIN"
        const val LLM_BASE_URL = "PETEK_LLM_BASE_URL"
        const val LLM_API_KEY = "PETEK_LLM_API_KEY"
        const val LLM_STRUCTURED = "PETEK_LLM_STRUCTURED"
        const val LLM_EFFORT = "PETEK_LLM_EFFORT"
        const val OPENAI_API_KEY = "OPENAI_API_KEY"
        const val GEMINI_API_KEY = "GEMINI_API_KEY"
        const val LLM_CONCURRENCY = "PETEK_LLM_CONCURRENCY"
        const val LANGUAGE = "PETEK_LANGUAGE"
        const val ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY"
        const val BROWSER_HEADLESS = "PETEK_BROWSER_HEADLESS"
        const val BROWSER_TOPOLOGY = "PETEK_BROWSER_TOPOLOGY"
        const val BROWSER_IGNORE_TLS_ERRORS = "PETEK_BROWSER_IGNORE_TLS_ERRORS"
        const val EVIDENCE_DIR = "PETEK_EVIDENCE_DIR"
        const val DB = "PETEK_DB"
        const val TELEMETRY = "PETEK_TELEMETRY"
        const val ORACLE = "PETEK_ORACLE"
        const val PROXIES = "PETEK_PROXIES"
    }

    private companion object {
        const val MAX_LLM_CONCURRENCY = 64
        const val MAX_PORT = 65_535
        val PROXY_SCHEMES = setOf("http", "https", "socks5")
        const val DEFAULT_TARGETS_DIR = "targets"
        val MAILBOX = Regex("[a-z0-9._-]{1,48}@[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")
        const val AUTO = "auto"
        const val MIN_SECRET_LENGTH = 16
        val WEB_SCHEMES = setOf("http", "https")
        val TRUE = setOf("true", "yes", "on", "1")
        val FALSE = setOf("false", "no", "off", "0")
        val HOST = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*")
        val TOPOLOGIES = mapOf("shared-server" to BrowserTopology.SHARED_SERVER, "per-session" to BrowserTopology.PER_SESSION)
    }
}

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

package az.petek.app.config

import az.petek.browser.domain.BrowserProxy
import az.petek.browser.domain.BrowserTopology
import az.petek.core.model.WorkingLanguage
import az.petek.core.security.Secret
import az.petek.core.security.TargetPolicy
import az.petek.core.security.TargetVerdict
import az.petek.llm.domain.LlmProviderKey
import az.petek.llm.infrastructure.http.StructuredMode
import az.petek.mail.infrastructure.ImapSettings
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
 * @property productionHosts hosts refused as a target unless [allowProduction] (AGENTS.md rule 8).
 * @property testToken `X-Test-Token` for the target's `/test/...` API; null disables oracle assertions.
 * @property testApiUrl where the `/test/...` API lives when it is not on the target's own origin (`PETEK_TEST_API_URL`,
 *   e.g. KadroHR's `api.` host); null means the target itself, see [testApiBase].
 * @property mailSource where verification mail is read from (`PETEK_MAIL_SOURCE`); the test API needs [testToken].
 * @property identitySecret key of the password derivation; from `PETEK_IDENTITY_SECRET` or `~/.petek/identity.secret`.
 * @property llmProvider the AI provider that answers (`PETEK_LLM_PROVIDER`; `auto` is resolved while loading, see
 *   [LlmProviderResolver]); [llmProviderReason] says why this one, e.g. `auto: AGENTS.md found`; [llmFallbacks] are the
 *   other providers found on the machine, tried in order when this one is unavailable.
 * @property llmModel the model (`PETEK_LLM_MODEL`); null means the provider's own default ([effectiveLlmModel]).
 * @property llmBin the CLI binary (`PETEK_LLM_BIN`); null means the provider's usual name (the `cli` provider needs it).
 * @property llmArgs the argument template of the `cli` provider (`PETEK_LLM_ARGS`), already split into words.
 * @property llmEnvUnset variables removed from an AI tool's environment, `NAME` or `PREFIX*` (`PETEK_LLM_ENV_UNSET`).
 * @property llmBaseUrl an OpenAI-compatible endpoint (`PETEK_LLM_BASE_URL`, e.g. `http://localhost:11434/v1`).
 * @property llmApiKey the provider's API key (`PETEK_LLM_API_KEY`, aliases `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`,
 *   `GEMINI_API_KEY`).
 * @property llmStructured how an OpenAI-compatible endpoint is asked for JSON (`PETEK_LLM_STRUCTURED`).
 * @property llmEffort reasoning effort (`PETEK_LLM_EFFORT`), passed only to providers that support it; null means the
 *   provider's default ([effectiveLlmEffort]).
 * @property llmConcurrency LLM calls allowed in flight at once across all agents.
 * @property language the language the AI writes for the owner in (`PETEK_LANGUAGE`; `auto` follows the owner's own
 *   text), see [WorkingLanguage].
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
    /** The owner's own box (`PETEK_MAIL_INBOX`, e.g. `test@company.az`): testers get its `+` addresses (Faza 16). */
    val mailInbox: String? = null,
    /** How to read the owner's box when [mailSource] is IMAP (`PETEK_IMAP_*`). */
    val imap: ImapSettings? = null,
    val identitySecret: Secret,
    val llmProvider: LlmProviderKey = LlmProviderKey.NONE,
    val llmProviderReason: String = "default",
    val llmFallbacks: List<LlmProviderKey> = emptyList(),
    val llmModel: String? = null,
    val llmBin: String? = null,
    val llmArgs: List<String> = emptyList(),
    val llmEnvUnset: List<String> = emptyList(),
    val llmBaseUrl: URI? = null,
    val llmApiKey: Secret? = null,
    val llmStructured: StructuredMode = StructuredMode.SCHEMA,
    val llmEffort: String? = null,
    val llmConcurrency: Int = DEFAULT_LLM_CONCURRENCY,
    val language: WorkingLanguage = WorkingLanguage.AUTO,
    val browserHeadless: Boolean = true,
    val browserTopology: BrowserTopology = BrowserTopology.SHARED_SERVER,
    /** Accept TLS certificates the browser does not trust (self-signed staging, re-signing proxy). Off by default. */
    val browserIgnoreTlsErrors: Boolean = false,
    val evidenceDir: Path,
    val dbPath: Path = evidenceDir.resolve(DEFAULT_DB_FILE),
    /** `PETEK_TELEMETRY=local`: counters only, into [telemetryFile]; off by default (ADR-0011). */
    val telemetry: Boolean = false,
    /** The sites of `targets/<name>.yaml` (`PETEK_TARGETS_DIR`), secrets resolved; a run on one of them uses its settings. */
    val targets: List<ResolvedTarget> = emptyList(),
    /** Where target profiles live (`PETEK_TARGETS_DIR`); the panel adds accounts there. */
    val targetsDir: Path? = null,
    /** `PETEK_CORRELATION_HEADER`: testers send `X-Petek-Correlation-Id` (the site's CORS must allow it). */
    val correlationHeader: Boolean = false,
    /** `PETEK_TRACE_LOG`: the target's log file, searched for the correlation ids of a finding's step. */
    val traceLog: Path? = null,
    /** `PETEK_ORACLE=none` (or a profile's `test_api.mode: none`): no test API is asked, even with a token. */
    val oracle: Boolean = true,
    /** Where the test API answers the oracle (a profile's `test_api.paths`); empty: docs/TARGET_CONTRACT.md §4. */
    val oraclePaths: Map<String, String> = emptyMap(),
    /** `PETEK_PROXIES`: one proxy per live tester, so the site sees each from its own IP (Faza 21). */
    val proxies: List<BrowserProxy> = emptyList(),
) {
    init {
        require(llmConcurrency >= 1) { "llmConcurrency must be at least 1, was $llmConcurrency" }
        require(!identitySecret.isBlank) { "identitySecret must not be blank" }
        require(llmProvider != LlmProviderKey.ANTHROPIC_API || (llmApiKey?.isBlank == false && llmModel != null)) {
            "the anthropic-api provider needs an API key and a model"
        }
        require(llmProvider != LlmProviderKey.CLI || !llmBin.isNullOrBlank()) { "the cli provider needs the tool to run (PETEK_LLM_BIN)" }
        require(llmProvider != LlmProviderKey.OPENAI_COMPAT || (llmBaseUrl != null && llmModel != null)) {
            "the openai-compat provider needs a base URL and a model"
        }
        require(mailSource != MailSource.TEST_API || testToken?.isBlank == false) {
            "the test-api mail source needs the test token"
        }
        require(mailSource != MailSource.IMAP || imap != null) { "the imap mail source needs its IMAP settings" }
        require(testApiUrl == null || targetPolicy.verify(testApiUrl) == TargetVerdict.Allowed) {
            "the test API URL names a production host; set PETEK_ALLOW_PRODUCTION=true to allow it"
        }
    }

    /** The production guard for [target] and for URLs given on the command line. */
    val targetPolicy: TargetPolicy get() = TargetPolicy(productionHosts, allowProduction)

    /** Base address of the `/test/...` API (the oracle and the test-API mailbox): [testApiUrl], else [target]. */
    val testApiBase: URI get() = testApiUrl ?: target

    /** The model that answers: [llmModel], else null, the model the provider or tool is configured for. */
    val effectiveLlmModel: String? get() = llmModel

    /** How the model is named in doctor, reports and usage. */
    val llmModelLabel: String get() = effectiveLlmModel ?: "default"

    /** The CLI binary: [llmBin], else the provider's usual executable name; null for providers that run none. */
    val effectiveLlmBin: String? get() = llmBin ?: DEFAULT_BINARIES[llmProvider]

    /** [llmEffort], else `low` for the providers that take it (fast, cheap agent decisions). */
    val effectiveLlmEffort: String? get() = llmEffort ?: DEFAULT_EFFORT.takeIf { llmProvider in EFFORT_PROVIDERS }

    /** Where opt-in telemetry counters are appended: `<evidenceDir>/telemetry/usage.jsonl`. */
    val telemetryFile: Path get() = evidenceDir.resolve("telemetry").resolve("usage.jsonl")

    /** The profile of [site] (same scheme, host and port), if `targets/` has one. */
    fun profileFor(site: URI): ResolvedTarget? = targets.firstOrNull { sameSite(it.spec.url, site) }

    /** Where the log file lives: `<evidenceDir>/logs`. */
    val logDirectory: Path get() = evidenceDir.resolve("logs")

    override fun toString(): String =
        "PetekConfig(target=${masked(target)}, productionHosts=$productionHosts, allowProduction=$allowProduction, " +
            "testToken=${setOrUnset(testToken)}, testApiUrl=${testApiUrl?.let(::masked)}, mailSource=${mailSource.key}, " +
            "mailpitUrl=${masked(mailpitUrl)}, mailDomain=$mailDomain, mailInbox=${mailInbox ?: "unset"}, imap=${imap ?: "unset"}, " +
            "identitySecret=***, llmProvider=$llmProvider ($llmProviderReason), llmModel=$llmModelLabel, llmBin=$effectiveLlmBin, " +
            "llmBaseUrl=${llmBaseUrl?.let(::masked)}, llmApiKey=${setOrUnset(llmApiKey)}, llmStructured=${llmStructured.key}, " +
            "llmEffort=$effectiveLlmEffort, llmConcurrency=$llmConcurrency, language=$language, " +
            "browserHeadless=$browserHeadless, browserTopology=$browserTopology, browserIgnoreTlsErrors=$browserIgnoreTlsErrors, " +
            "evidenceDir=$evidenceDir, dbPath=$dbPath, proxies=${proxies.size}, " +
            "telemetry=${if (telemetry) "local" else "off"}, targets=${targets.map {
                it.spec.name
            }})"

    companion object {
        /** No site is production by default; a site's own hosts come from `PETEK_PRODUCTION_HOSTS` or its target profile. */
        val DEFAULT_PRODUCTION_HOSTS: Set<String> = emptySet()
        const val DEFAULT_MAILPIT_URL = "http://localhost:8025"

        /** A catch-all under the reserved `.test` TLD; a site whose mail must look real names its own domain. */
        const val DEFAULT_MAIL_DOMAIN = "petek.test"

        /** Agent decisions are short: effort-controllable providers run at `low` unless `PETEK_LLM_EFFORT` says otherwise. */
        const val DEFAULT_EFFORT = "low"

        /** The executables of the agents Pətək knows by name; the `cli` provider runs whatever `PETEK_LLM_BIN` names. */
        val DEFAULT_BINARIES: Map<LlmProviderKey, String> =
            mapOf(
                LlmProviderKey.CODEX_CLI to "codex",
                LlmProviderKey.GEMINI_CLI to "gemini",
                LlmProviderKey.OPENCODE_CLI to "opencode",
            )

        /** Providers Pətək passes an effort level to. */
        val EFFORT_PROVIDERS: Set<LlmProviderKey> = setOf(LlmProviderKey.CODEX_CLI)
        const val DEFAULT_LLM_CONCURRENCY = 6
        const val DEFAULT_EVIDENCE_DIR = "evidence"
        const val DEFAULT_DB_FILE = "petek.db"

        private fun sameSite(
            a: URI,
            b: URI,
        ): Boolean =
            a.scheme.equals(b.scheme, ignoreCase = true) &&
                a.host
                    .orEmpty()
                    .trimEnd('.')
                    .equals(b.host.orEmpty().trimEnd('.'), ignoreCase = true) &&
                port(a) == port(b)

        private fun port(url: URI): Int =
            if (url.port != -1) {
                url.port
            } else if (url.scheme.equals("https", ignoreCase = true)) {
                443
            } else {
                80
            }

        /** Shows a URL with its user info (credentials) replaced by three asterisks. */
        fun masked(url: URI): String = url.rawUserInfo?.let { url.toString().replace("$it@", "***@") } ?: url.toString()

        private fun setOrUnset(secret: Secret?): String = if (secret == null || secret.isBlank) "unset" else "set"
    }
}

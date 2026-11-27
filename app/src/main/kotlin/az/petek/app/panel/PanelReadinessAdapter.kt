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

package az.petek.app.panel

import az.petek.app.config.ConfigException
import az.petek.app.config.EnvFile
import az.petek.app.config.EnvFileWriter
import az.petek.app.config.PetekConfig
import az.petek.app.di.AppContainer
import az.petek.app.diagnostics.Doctor
import az.petek.app.diagnostics.HttpProbe
import az.petek.app.diagnostics.TargetAnswer
import az.petek.app.init.McpLaunch
import az.petek.dashboard.domain.AiCheckView
import az.petek.dashboard.domain.AiChoice
import az.petek.dashboard.domain.AiOptionView
import az.petek.dashboard.domain.AiOptionsView
import az.petek.dashboard.domain.AiView
import az.petek.dashboard.domain.FieldProblem
import az.petek.dashboard.domain.OwnershipView
import az.petek.dashboard.domain.PanelConflictException
import az.petek.dashboard.domain.PanelException
import az.petek.dashboard.domain.PanelReadiness
import az.petek.dashboard.domain.PanelRequestException
import az.petek.dashboard.domain.PanelUnavailableException
import az.petek.dashboard.domain.ReadinessView
import az.petek.dashboard.domain.SiteCheckView
import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmProviderKey
import az.petek.ownership.domain.OwnershipStatus
import az.petek.scenarios.domain.SecretRedactor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * The setup screen's checks for the panel's own site, with the same parts `petek doctor` and a run use: the site's
 * reachability ([AppContainer.reachability]), its ownership ([AppContainer.ownership]; a fresh look remembers a proof
 * it finds, as `petek verify` does) and the AI answering the doctor's tiny request ([Doctor.PING]) through its own
 * client with the doctor's timeout. Nothing is written to the site. What the AI or its provider answered on a failure
 * reaches the page only redacted ([SecretRedactor]: the configured secrets and anything shaped like a key or a
 * password) and clipped; the log gets the whole of it, redacted too (AGENTS.md rule 10).
 */
internal class PanelReadinessAdapter(
    private val container: AppContainer,
    /** The configuration file the panel was started with, shown so the owner can find it; null when there is none. */
    private val configurationFile: Path?,
    /**
     * Reads the configuration again from its file and the environment, as a start would (Faza 23: the AI chosen here
     * is taken without restarting); null where the AI cannot be chosen here (`petek mcp`).
     */
    private val reload: (() -> PetekConfig)? = null,
    /** Whether a test, an exploration or a run is going: the AI is not switched under them. */
    private val busy: () -> Boolean = { false },
) : PanelReadiness {
    private val config: PetekConfig get() = container.config
    private val choosing = Mutex()

    /** Every secret the page must not see, as configured now (a key chosen here included). */
    private val redactor: SecretRedactor
        get() = SecretRedactor(listOfNotNull(config.testToken, config.llmApiKey, config.identitySecret))

    override suspend fun readiness(): ReadinessView =
        ReadinessView(
            target = PetekConfig.masked(config.target),
            configuration = configurationFile?.takeIf { Files.isRegularFile(it) }?.toAbsolutePath()?.toString(),
            ai =
                AiView(
                    provider = config.llmProvider.value,
                    model = config.llmModelLabel,
                    reason = config.llmProviderReason,
                    fallbacks = config.llmFallbacks.map { it.value },
                    configured = config.llmProvider != LlmProviderKey.NONE,
                ),
        )

    override suspend fun checkSite(): SiteCheckView =
        when (val answer = container.reachability.check(config.target)) {
            TargetAnswer.Reachable -> SiteCheckView(reachable = true, detail = PetekConfig.masked(config.target))
            is TargetAnswer.Unreachable -> SiteCheckView(reachable = false, detail = answer.reason)
        }

    override suspend fun checkOwnership(fresh: Boolean): OwnershipView {
        val status = if (fresh) container.ownership.verify(config.target) else container.ownership.check(config.target)
        return when (status) {
            is OwnershipStatus.Exempt -> {
                OwnershipView(EXEMPT, status.host, null, null, null, null, emptyList())
            }

            is OwnershipStatus.Verified -> {
                OwnershipView(VERIFIED, status.host, status.record.method.key, null, null, null, emptyList())
            }

            is OwnershipStatus.Unverified -> {
                val challenge = status.challenge
                OwnershipView(
                    UNVERIFIED,
                    status.host,
                    null,
                    challenge.fileUrl.toString(),
                    challenge.proofLine,
                    challenge.dnsName,
                    status.looked,
                )
            }
        }
    }

    override suspend fun testAi(): AiCheckView {
        val provider = config.llmProvider.value
        val started = System.nanoTime()

        fun millis() = (System.nanoTime() - started) / NANOS_PER_MILLI
        var client: LlmClient? = null
        return try {
            val response = container.diagnosticLlm().also { client = it }.complete(Doctor.PING)
            val ok = response.output["ok"] == JsonPrimitive(true)
            val detail = if (ok) "" else failure("answered, but not as asked: ${response.output}")
            AiCheckView(ok, client?.provider?.value ?: provider, response.model, detail, millis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            AiCheckView(false, provider, null, failure(e.message.orEmpty()), millis())
        } catch (e: Exception) {
            AiCheckView(false, provider, null, failure(HttpProbe.describe(e)), millis())
        } finally {
            (client as? AutoCloseable)?.close()
        }
    }

    override suspend fun aiOptions(): AiOptionsView {
        val file = configurationFile?.takeIf { Files.isRegularFile(it) }
        val written = file?.let { runCatching { withContext(Dispatchers.IO) { EnvFile.load(it) } }.getOrNull() }.orEmpty()
        val path = System.getenv(PATH).orEmpty()
        return AiOptionsView(
            options =
                CHOICES.map { (key, label) ->
                    val binary = PetekConfig.DEFAULT_BINARIES[LlmProviderKey.of(key)]
                    AiOptionView(key, label, binary == null || McpLaunch.onPath(binary, path), NEEDS[key].orEmpty())
                },
            chosen = written[PROVIDER]?.takeIf { it.isNotBlank() } ?: config.llmProvider.value,
            model = config.llmModel,
            endpoint = config.llmBaseUrl?.toString(),
            keySet = config.llmApiKey != null,
            unavailable =
                when {
                    reload == null -> "AI bu paneldə seçilmir; onu konfiqurasiya faylında (PETEK_LLM_PROVIDER) dəyişin."
                    configurationFile == null -> "Konfiqurasiya faylı yoxdur, seçim saxlanmaz; `petek init` ilə yaradın."
                    else -> null
                },
        )
    }

    override suspend fun chooseAi(choice: AiChoice): ReadinessView {
        val load = reload ?: throw PanelUnavailableException("AI bu paneldə seçilmir; onu konfiqurasiya faylında dəyişin.")
        val file = configurationFile ?: throw PanelRequestException(listOf(FieldProblem(PROVIDER_FIELD, NO_FILE)))
        val provider = choice.provider.trim().lowercase()
        val model = choice.model?.trim().orEmpty()
        val endpoint = choice.endpoint?.trim().orEmpty()
        val key = choice.key?.takeIf { it.isNotBlank() }
        problems(provider, model, endpoint, key).takeIf { it.isNotEmpty() }?.let { throw PanelRequestException(it) }
        return choosing.withLock {
            if (busy()) throw PanelConflictException("Test, kəşfiyyat və ya run gedir; AI-ı o bitəndən sonra dəyişin.")
            val fresh =
                withContext(Dispatchers.IO) {
                    val before = if (Files.exists(file)) Files.readAllBytes(file) else null
                    try {
                        EnvFileWriter.set(file, PROVIDER, provider)
                        EnvFileWriter.set(file, MODEL, model)
                        EnvFileWriter.set(file, BASE_URL, endpoint)
                        key?.let { EnvFileWriter.set(file, API_KEY, it) }
                        val loaded = load()
                        // A variable of the environment wins over the file: the choice would not be the one in force.
                        if (provider != AUTO && loaded.llmProvider.value != provider) throw OverriddenException()
                        loaded
                    } catch (e: Exception) {
                        if (before == null) Files.deleteIfExists(file) else Files.write(file, before)
                        throw refusal(e)
                    }
                }
            container.refresh(fresh)
            logger.info { "The AI was switched on the setup screen: ${fresh.llmProvider} (${fresh.llmProviderReason})" }
            readiness()
        }
    }

    private fun problems(
        provider: String,
        model: String,
        endpoint: String,
        key: String?,
    ): List<FieldProblem> =
        buildList {
            if (CHOICES.none { it.first == provider }) {
                add(FieldProblem(PROVIDER_FIELD, "Bu AI seçilə bilməz: $provider (${CHOICES.joinToString { it.first }})."))
                return@buildList
            }
            if (provider in NEEDS_MODEL && model.isEmpty()) add(FieldProblem(MODEL_FIELD, "$provider üçün model adı lazımdır."))
            if (provider == OPENAI_COMPAT && !WEB_URL.matches(endpoint)) {
                add(FieldProblem(ENDPOINT_FIELD, "$provider üçün endpoint http(s) ünvanıdır (məs. http://localhost:11434/v1)."))
            }
            if (provider == ANTHROPIC_API && key == null && config.llmApiKey == null) {
                add(FieldProblem(KEY_FIELD, "$provider üçün API açarı lazımdır."))
            }
            if (key != null && (key.length > MAX_KEY || key.any(Char::isISOControl))) {
                add(FieldProblem(KEY_FIELD, "API açarı 1–$MAX_KEY simvol olmalı, sətir keçidi olmamalıdır."))
            }
            if (model.length > MAX_TEXT || endpoint.length > MAX_TEXT) add(FieldProblem(MODEL_FIELD, "Model və endpoint çox uzundur."))
        }

    /** Why the configuration did not take the choice; the loader names variables, never values. */
    private fun refusal(e: Exception): PanelException =
        when (e) {
            is OverriddenException -> {
                PanelRequestException(
                    listOf(
                        FieldProblem(
                            PROVIDER_FIELD,
                            "$PROVIDER mühit dəyişəni (environment) kimi də verilib və faylı üstələyir; onu silin və ya orada dəyişin. " +
                                "Heç nə dəyişmədi.",
                        ),
                    ),
                )
            }

            is ConfigException -> {
                PanelRequestException(
                    listOf(FieldProblem(PROVIDER_FIELD, "Bu seçimlə konfiqurasiya yüklənmir: ${e.message} Heç nə dəyişmədi.")),
                )
            }

            else -> {
                PanelRequestException(listOf(FieldProblem(PROVIDER_FIELD, "Seçim saxlanmadı: ${e::class.simpleName}. Heç nə dəyişmədi.")))
            }
        }

    private class OverriddenException : RuntimeException()

    /** [text] as the page may show it: redacted and clipped; the log keeps all of it, redacted. */
    private fun failure(text: String): String {
        val redacted = redactor.redact(text)
        logger.warn { "The setup screen's AI check failed (${config.llmProvider}): $redacted" }
        return if (redacted.length <= MAX_DETAIL) redacted else redacted.take(MAX_DETAIL) + "…"
    }

    private companion object {
        const val AUTO = "auto"
        const val OPENAI_COMPAT = "openai-compat"
        const val ANTHROPIC_API = "anthropic-api"

        /** What the page offers, in its order; the generic `cli` needs an argument template, so it stays in the file. */
        val CHOICES: List<Pair<String, String>> =
            listOf(
                AUTO to "Avtomatik: kompüterdəki AI-ı Pətək özü tapır",
                "codex-cli" to "codex komanda sətri aləti (öz girişi ilə)",
                "gemini-cli" to "gemini komanda sətri aləti (öz girişi ilə)",
                "opencode-cli" to "opencode komanda sətri aləti (öz girişi ilə)",
                OPENAI_COMPAT to "OpenAI-uyğun endpoint (Ollama, OpenRouter, LM Studio, ...)",
                ANTHROPIC_API to "Anthropic API (açarla)",
                "none" to "AI-sız: yalnız kod yoxlamaları",
            )

        val NEEDS: Map<String, List<String>> =
            mapOf(
                "codex-cli" to listOf("model"),
                "gemini-cli" to listOf("model"),
                "opencode-cli" to listOf("model"),
                OPENAI_COMPAT to listOf("model", "endpoint", "key"),
                ANTHROPIC_API to listOf("model", "key"),
            )
        val NEEDS_MODEL = setOf(OPENAI_COMPAT, ANTHROPIC_API)

        const val PROVIDER = "PETEK_LLM_PROVIDER"
        const val MODEL = "PETEK_LLM_MODEL"
        const val BASE_URL = "PETEK_LLM_BASE_URL"
        const val API_KEY = "PETEK_LLM_API_KEY"
        const val PATH = "PATH"
        const val PROVIDER_FIELD = "provider"
        const val MODEL_FIELD = "model"
        const val ENDPOINT_FIELD = "endpoint"
        const val KEY_FIELD = "key"
        const val MAX_KEY = 512
        const val MAX_TEXT = 300
        const val NO_FILE = "Konfiqurasiya faylı yoxdur, seçim saxlanmaz; `petek init` ilə yaradın."
        val WEB_URL = Regex("^https?://\\S+$")
        const val MAX_DETAIL = 300
        const val EXEMPT = "EXEMPT"
        const val VERIFIED = "VERIFIED"
        const val UNVERIFIED = "UNVERIFIED"
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

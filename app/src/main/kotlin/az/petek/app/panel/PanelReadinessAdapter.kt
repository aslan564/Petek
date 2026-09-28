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

import az.petek.app.config.PetekConfig
import az.petek.app.di.AppContainer
import az.petek.app.diagnostics.Doctor
import az.petek.app.diagnostics.HttpProbe
import az.petek.app.diagnostics.TargetAnswer
import az.petek.dashboard.domain.AiCheckView
import az.petek.dashboard.domain.AiView
import az.petek.dashboard.domain.OwnershipView
import az.petek.dashboard.domain.PanelReadiness
import az.petek.dashboard.domain.ReadinessView
import az.petek.dashboard.domain.SiteCheckView
import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmProviderKey
import az.petek.ownership.domain.OwnershipStatus
import az.petek.scenarios.domain.SecretRedactor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
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
) : PanelReadiness {
    private val config: PetekConfig get() = container.config
    private val redactor =
        SecretRedactor(listOfNotNull(container.config.testToken, container.config.llmApiKey, container.config.identitySecret))

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

    /** [text] as the page may show it: redacted and clipped; the log keeps all of it, redacted. */
    private fun failure(text: String): String {
        val redacted = redactor.redact(text)
        logger.warn { "The setup screen's AI check failed (${config.llmProvider}): $redacted" }
        return if (redacted.length <= MAX_DETAIL) redacted else redacted.take(MAX_DETAIL) + "…"
    }

    private companion object {
        const val MAX_DETAIL = 300
        const val EXEMPT = "EXEMPT"
        const val VERIFIED = "VERIFIED"
        const val UNVERIFIED = "UNVERIFIED"
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

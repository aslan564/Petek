package az.petek.llm.infrastructure.api

import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmException
import az.petek.llm.domain.LlmProviderId
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import az.petek.llm.domain.LlmRole
import az.petek.llm.domain.TokenUsage
import az.petek.llm.infrastructure.StructuredJson
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonObject
import kotlin.jvm.optionals.getOrNull
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}

/**
 * [LlmClient] over the Anthropic Messages API (official Java SDK, non-blocking async client).
 *
 * The answer is constrained with structured output (`output_config.format` = the request's JSON schema) and read
 * from the first text block. No thinking or sampling parameters are sent: their accepted values differ per model,
 * and the model's defaults are fine for short decisions. The API reports no price, so `costUsd` is `null`.
 *
 * Failures map to [LlmException] (see [AnthropicErrors]); a refusal or an answer cut off at `max_tokens` is
 * `InvalidOutput`. Close it to release the HTTP connection pool.
 */
class AnthropicApiLlmClient(
    private val config: AnthropicApiConfig,
) : LlmClient,
    AutoCloseable {
    override val provider: LlmProviderId = LlmProviderId.ANTHROPIC_API
    override val model: String = config.model

    private val client: AnthropicClient = buildClient(config)
    private val errors = AnthropicErrors(config.model, config.timeout)

    override suspend fun complete(request: LlmRequest): LlmResponse {
        require(request.messages.isNotEmpty()) { "LLM request ${request.label} has no messages" }
        val params = params(request)
        val response =
            try {
                toResponse(
                    client
                        .async()
                        .messages()
                        .create(params)
                        .await(),
                    request,
                )
            } catch (e: AnthropicException) {
                throw errors.map(e, request.label)
            }
        logger.debug {
            "Anthropic API answered ${request.label} (model=${response.model}, " +
                "in=${response.usage.inputTokens}, out=${response.usage.outputTokens})"
        }
        return response
    }

    override fun close() = client.close()

    private fun params(request: LlmRequest): MessageCreateParams {
        val builder =
            MessageCreateParams
                .builder()
                .model(config.model)
                .maxTokens(request.maxOutputTokens.toLong())
                .outputConfig(outputConfig(request.responseSchema))
        if (request.system.isNotBlank()) builder.system(request.system)
        request.messages.forEach { message ->
            when (message.role) {
                LlmRole.USER -> builder.addUserMessage(message.content)
                LlmRole.ASSISTANT -> builder.addAssistantMessage(message.content)
            }
        }
        return builder.build()
    }

    private fun outputConfig(schema: JsonObject): OutputConfig {
        val sdkSchema = JsonOutputFormat.Schema.builder()
        schema.forEach { (key, value) -> sdkSchema.putAdditionalProperty(key, SdkJson.toSdk(value)) }
        return OutputConfig
            .builder()
            .format(JsonOutputFormat.builder().schema(sdkSchema.build()).build())
            .build()
    }

    private fun toResponse(
        message: Message,
        request: LlmRequest,
    ): LlmResponse {
        val text = message.content().firstNotNullOfOrNull { block -> block.text().getOrNull()?.text() }
        rejectIncomplete(message.stopReason().getOrNull(), text.orEmpty(), request)
        if (text == null) throw LlmException.InvalidOutput("Claude's answer for ${request.label} has no text", raw = "")
        val output =
            StructuredJson.parseObject(text)
                ?: throw LlmException.InvalidOutput("Claude's answer for ${request.label} is not a JSON object", text)
        val usage = message.usage()
        return LlmResponse(
            output = output,
            usage =
                TokenUsage(
                    inputTokens = usage.inputTokens(),
                    outputTokens = usage.outputTokens(),
                    cacheReadTokens = usage.cacheReadInputTokens().getOrNull() ?: 0,
                    cacheCreationTokens = usage.cacheCreationInputTokens().getOrNull() ?: 0,
                ),
            model = message.model().asString(),
            costUsd = null,
        )
    }

    private fun rejectIncomplete(
        stopReason: StopReason?,
        text: String,
        request: LlmRequest,
    ) {
        val problem =
            when (stopReason) {
                StopReason.REFUSAL -> {
                    "Claude refused to answer ${request.label}"
                }

                StopReason.MAX_TOKENS -> {
                    "Claude's answer for ${request.label} was cut off at max_tokens=${request.maxOutputTokens}"
                }

                StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> {
                    "The prompt for ${request.label} does not fit the context window of ${config.model}"
                }

                else -> {
                    return
                }
            }
        throw LlmException.InvalidOutput(problem, raw = text)
    }

    private companion object {
        fun buildClient(config: AnthropicApiConfig): AnthropicClient {
            val builder =
                AnthropicOkHttpClient
                    .builder()
                    .apiKey(config.apiKey.reveal())
                    .timeout(config.timeout.toJavaDuration())
                    .maxRetries(config.maxRetries)
            config.baseUrl?.let { builder.baseUrl(it) }
            return builder.build()
        }
    }
}

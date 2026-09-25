package az.petek.llm.testing

import az.petek.llm.domain.LlmClient
import az.petek.llm.domain.LlmProviderId
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmResponse
import az.petek.llm.domain.TokenUsage
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic LLM for tests: [responder] maps each request to a JSON answer (it can read the prompt,
 * e.g. to find an element ref in the rendered snapshot). Every request is kept in [requests].
 */
class ScriptedLlmClient(
    override val model: String = "scripted",
    private val usagePerCall: TokenUsage = TokenUsage(inputTokens = 100, outputTokens = 20),
    private val responder: suspend (LlmRequest) -> JsonObject,
) : LlmClient {
    override val provider: LlmProviderId = LlmProviderId.CLAUDE_CLI
    val requests = CopyOnWriteArrayList<LlmRequest>()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        return LlmResponse(responder(request), usagePerCall, model, costUsd = 0.0)
    }
}

package az.petek.app.testing

import az.petek.llm.domain.LlmRequest
import az.petek.llm.testing.ScriptedLlmClient
import kotlinx.serialization.json.JsonObject

/** A [ScriptedLlmClient] answering every request with [answer]. */
fun scriptedLlm(answer: suspend (LlmRequest) -> JsonObject): ScriptedLlmClient = ScriptedLlmClient(responder = answer)

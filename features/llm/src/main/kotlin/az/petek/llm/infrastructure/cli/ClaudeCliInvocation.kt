package az.petek.llm.infrastructure.cli

import az.petek.llm.domain.LlmMessage
import az.petek.llm.domain.LlmRequest
import az.petek.llm.domain.LlmRole

/**
 * The exact way Pətək calls `claude -p`: an isolated, tool-less, stateless session whose only job is to answer with
 * JSON matching the request schema (docs/ARCHITECTURE.md, Security: no tools, no MCP servers, no settings, no
 * session persistence).
 */
internal object ClaudeCliInvocation {
    /** Kept although it starts with `CLAUDE_CODE_`: it is the user's headless login (`claude setup-token`), not a marker. */
    const val OAUTH_TOKEN_VARIABLE = "CLAUDE_CODE_OAUTH_TOKEN"
    const val API_KEY_VARIABLE = "ANTHROPIC_API_KEY"

    fun command(
        config: ClaudeCliConfig,
        request: LlmRequest,
    ): List<String> =
        buildList {
            add(config.executable)
            add("-p")
            addAll(listOf("--output-format", "json"))
            addAll(listOf("--json-schema", request.responseSchema.toString()))
            addAll(listOf("--model", config.model))
            addAll(listOf("--system-prompt", request.system))
            addAll(listOf("--tools", ""))
            add("--strict-mcp-config")
            addAll(listOf("--setting-sources", ""))
            add("--no-session-persistence")
            addAll(listOf("--permission-prompts", "none"))
            config.effort?.let { addAll(listOf("--effort", it)) }
        }

    /**
     * The inherited environment without the markers of an enclosing Claude Code session (`CLAUDECODE`,
     * `CLAUDE_CODE_*`): with them the child believes it is nested inside another session and misbehaves.
     */
    fun environment(inherited: Map<String, String>): Map<String, String> = inherited.filterKeys { !isSessionMarker(it) }

    private fun isSessionMarker(name: String): Boolean =
        name == "CLAUDECODE" || (name.startsWith("CLAUDE_CODE_") && name != OAUTH_TOKEN_VARIABLE)

    /**
     * STDIN text: a lone user message as-is; a conversation as a plain transcript the model can continue
     * (`USER:\n...\n\nASSISTANT:\n...`).
     */
    fun transcript(messages: List<LlmMessage>): String {
        val single = messages.singleOrNull()
        if (single != null && single.role == LlmRole.USER) return single.content
        return messages.joinToString(separator = "\n\n") { "${it.role.name}:\n${it.content}" }
    }
}

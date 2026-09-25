package az.petek.agent.application

import az.petek.agent.domain.AgentRuntime

/** What a password is replaced with in any text that leaves the harness (prompts, evidence, summaries). */
internal const val PASSWORD_PLACEHOLDER = "{self.password}"

/**
 * Removes this agent's password from [text] (CLAUDE.md rule 10). Applied to everything that may echo page
 * content or error messages back to the LLM or into evidence: a password field's value in a snapshot, an adapter
 * error that quotes the typed text, a `read_text` result.
 */
internal fun AgentRuntime.redact(text: String): String {
    val password = identity.password.reveal()
    return if (password.isBlank()) text else text.replace(password, PASSWORD_PLACEHOLDER)
}

/** Shortens [text] for prompts and evidence details without cutting it silently. */
internal fun String.clip(maxChars: Int): String = if (length <= maxChars) this else take(maxChars) + "… (${length - maxChars} more chars)"

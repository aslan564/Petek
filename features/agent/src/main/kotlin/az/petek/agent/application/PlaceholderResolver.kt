package az.petek.agent.application

import az.petek.agent.domain.AgentAction
import az.petek.agent.domain.AgentRuntime
import az.petek.agent.domain.AgentVariableKeys
import az.petek.agent.domain.SharedRunState

/**
 * Substitutes harness placeholders in text the model asked to type, so credentials and codes never pass through
 * the LLM (CLAUDE.md rule 10): `{self.password}`, `{self.email}`, `{self.name}`, `{self.phone}`, `{self.agent_id}`,
 * `{self.department}`, `{self.role}`, `{vars.<key>}`, `{shared.company_code}`, `{shared.company_id}`.
 *
 * Anything shaped like `{identifier}` or `{a.b}` is treated as a placeholder, so a typo such as `{email_code}` is
 * reported instead of being typed literally into a form. Problems are returned as [Resolution.Unresolved] with a
 * message for the model, never thrown: a wrong placeholder is a decision error the model can correct.
 */
class PlaceholderResolver {
    sealed interface Resolution {
        /** [text] may contain the password: never log it, never put it in a prompt or in evidence. */
        data class Resolved(
            val text: String,
        ) : Resolution {
            override fun toString(): String = "Resolved(text=***)"
        }

        data class Unresolved(
            val message: String,
        ) : Resolution
    }

    fun resolve(
        text: String,
        runtime: AgentRuntime,
    ): Resolution {
        val problems = linkedSetOf<String>()
        val resolved =
            PLACEHOLDER.replace(text) { match ->
                when (val value = lookup(match.groupValues[1], runtime)) {
                    is Lookup.Found -> {
                        value.text
                    }

                    is Lookup.Missing -> {
                        problems += value.problem
                        match.value
                    }
                }
            }
        if (problems.isEmpty()) return Resolution.Resolved(resolved)
        val available = available(runtime).joinToString(", ")
        return Resolution.Unresolved("${problems.joinToString(" ")} Placeholders you can use now: $available.")
    }

    /** Placeholders that resolve right now, in a stable order (for the prompt and for error messages). */
    fun available(runtime: AgentRuntime): List<String> =
        buildList {
            addAll(SELF_KEYS.filterKeys { it != DEPARTMENT || runtime.identity.department != null }.keys.map { "{self.$it}" })
            runtime.variables
                .snapshot()
                .keys
                .sorted()
                .forEach { add("{vars.$it}") }
            SHARED_KEYS.filter { runtime.shared.get(it.value) != null }.forEach { add("{shared.${it.key}}") }
        }

    private fun lookup(
        name: String,
        runtime: AgentRuntime,
    ): Lookup {
        val namespace = name.substringBefore('.', missingDelimiterValue = "")
        val key = name.substringAfter('.', missingDelimiterValue = name)
        return when (namespace) {
            "self" -> self(key, runtime)
            "vars" -> variable(key, runtime)
            "shared" -> shared(key, runtime)
            else -> Lookup.Missing("Unknown placeholder {$name}.")
        }
    }

    private fun self(
        key: String,
        runtime: AgentRuntime,
    ): Lookup {
        val read = SELF_KEYS[key] ?: return Lookup.Missing("Unknown placeholder {self.$key}.")
        return read(runtime)?.let { Lookup.Found(it) } ?: Lookup.Missing("{self.$key} has no value for you.")
    }

    private fun variable(
        key: String,
        runtime: AgentRuntime,
    ): Lookup {
        runtime.variables[key]?.let { return Lookup.Found(it) }
        val hint = FETCHED_BY[key]?.let { " Call $it first." }.orEmpty()
        return Lookup.Missing("{vars.$key} is not set.$hint")
    }

    private fun shared(
        key: String,
        runtime: AgentRuntime,
    ): Lookup {
        val sharedKey = SHARED_KEYS[key] ?: return Lookup.Missing("Unknown placeholder {shared.$key}.")
        return runtime.shared.get(sharedKey)?.let { Lookup.Found(it) }
            ?: Lookup.Missing("{shared.$key} is not known yet.")
    }

    private sealed interface Lookup {
        class Found(
            val text: String,
        ) : Lookup

        class Missing(
            val problem: String,
        ) : Lookup
    }

    private companion object {
        val PLACEHOLDER = Regex("""\{([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z0-9_\-]+)*)\}""")

        const val DEPARTMENT = "department"

        /** Variables the model fills itself with a tool, named in the hint when it types one too early. */
        val FETCHED_BY: Map<String, String> =
            mapOf(
                AgentVariableKeys.EMAIL_CODE to AgentAction.GetEmailCode.toolName,
                AgentVariableKeys.PHONE_CODE to AgentAction.GetPhoneCode.toolName,
            )

        val SELF_KEYS: Map<String, (AgentRuntime) -> String?> =
            linkedMapOf(
                "email" to { it.identity.email },
                "password" to { it.identity.password.reveal() },
                "name" to { it.identity.displayName },
                "phone" to { it.identity.phone },
                "agent_id" to { it.identity.agentId.value },
                "role" to { it.identity.role.key },
                DEPARTMENT to { it.identity.department },
            )

        val SHARED_KEYS: Map<String, String> =
            linkedMapOf(
                "company_code" to SharedRunState.COMPANY_CODE,
                "company_id" to SharedRunState.COMPANY_ID,
            )
    }
}

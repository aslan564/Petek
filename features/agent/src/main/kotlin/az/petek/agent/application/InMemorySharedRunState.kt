package az.petek.agent.application

import az.petek.agent.domain.SharedRunState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * [SharedRunState] for one run inside one process. Writers replace the whole map atomically, and waiters suspend on
 * the state flow until their key appears (no polling), so thirty agents waiting for `company_code` cost nothing
 * until the admin publishes it. A later [put] of the same key overwrites the value.
 */
class InMemorySharedRunState : SharedRunState {
    private val values = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun get(key: String): String? = values.value[key]

    override fun put(
        key: String,
        value: String,
    ) {
        values.update { it + (key to value) }
    }

    override suspend fun await(
        key: String,
        timeout: Duration,
    ): String? = get(key) ?: withTimeoutOrNull(timeout) { values.mapNotNull { it[key] }.first() }

    /** Everything published so far (for diagnostics and reports). */
    fun snapshot(): Map<String, String> = values.value
}

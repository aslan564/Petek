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

package az.petek.capacity.application

import az.petek.capacity.domain.CapacityAdvisor
import az.petek.capacity.domain.CapacityRecommendation
import az.petek.capacity.domain.HostResourceProbe
import az.petek.capacity.domain.SessionCost
import az.petek.capacity.domain.SessionCostProbe
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * How many testers this machine can run, as advice for `petek capacity`, `plan` and the warning before `run`.
 *
 * Without a [Measurement] it is fast: the host is probed and the documented [SessionCost.ESTIMATE] is used. With one,
 * [costProbe] first opens real sessions to measure the cost per session and per browser; the host is probed before
 * that, while no measuring browser is running. A measurement that fails (no browser, no `/proc`, ...) does not fail
 * the advice: the estimate is used and a note says why.
 */
class RecommendCapacityUseCase(
    private val hostProbe: HostResourceProbe,
    /** Needed only for measurements; null when the caller never measures. */
    private val costProbe: SessionCostProbe? = null,
    private val advisor: CapacityAdvisor = CapacityAdvisor(),
) {
    /** Measure [sessions] real sessions (at least 1) on [url], or on a blank page when [url] is null. */
    data class Measurement(
        val sessions: Int = DEFAULT_SESSIONS,
        val url: URI? = null,
    ) {
        init {
            require(sessions >= 1) { "at least 1 session must be measured, was $sessions" }
        }

        companion object {
            const val DEFAULT_SESSIONS = 5
        }
    }

    /**
     * [contextsPerBrowser] is the engine setting the run will use (1 when every session gets its own browser).
     * Throws [IllegalStateException] when a [measurement] is asked for but no [costProbe] was given.
     */
    suspend fun execute(
        contextsPerBrowser: Int,
        measurement: Measurement? = null,
    ): CapacityRecommendation {
        val host = hostProbe.probe()
        if (measurement == null) return advisor.recommend(host, SessionCost.ESTIMATE, contextsPerBrowser)
        val probe = checkNotNull(costProbe) { "measuring the session cost needs a SessionCostProbe" }
        val cost =
            try {
                probe.measure(measurement.sessions, measurement.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn { "measuring the session cost failed: ${e.message}" }
                val advice = advisor.recommend(host, SessionCost.ESTIMATE, contextsPerBrowser)
                return advice.copy(
                    notes =
                        listOf("Measuring failed (${e.message ?: e::class.simpleName}); the estimates are used.") + advice.notes,
                )
            }
        return advisor.recommend(host, cost, contextsPerBrowser)
    }
}

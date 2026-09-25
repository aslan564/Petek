/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.dashboard.infrastructure

import az.petek.dashboard.application.LiveDashboard
import az.petek.dashboard.domain.ExplorationView
import az.petek.dashboard.domain.PanelBackend
import io.ktor.server.routing.Route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `GET /api/stream?topics=a,b` — one Server-Sent Events connection per page carrying the topics its screen needs:
 * - `board`: the full live board (event `snapshot`), the default when no topic is named;
 * - `run`: only the run header and counters (event `run`), for screens that do not show the agents;
 * - `orchestrator`: plan, task matrix and events (event `orchestrator`);
 * - `exploration`: the current exploration (event `exploration`, `null` when there is none);
 * - `jobs`: the small exploration status the sidebar shows (event `jobs`).
 * Each topic sends its current state at once and then at most four updates per second, always the latest. A heartbeat
 * comment every 15 s keeps proxies from closing the stream; EventSource reconnects by itself (retry 2 s).
 */
internal fun Route.streamRoutes(
    dashboard: LiveDashboard,
    backend: PanelBackend,
    reportReady: suspend () -> Boolean,
    refreshInterval: Duration,
) {
    sse("/api/stream") {
        heartbeat { period = HEARTBEAT }
        send(ServerSentEvent(comments = "connected", retry = RETRY_MILLIS))
        val topics = Topic.parse(call.request.queryParameters["topics"])
        val explorations = { explorations(backend).throttledLatest(refreshInterval) }
        val streams =
            topics.map { topic ->
                when (topic) {
                    Topic.BOARD -> {
                        dashboard.updates.map {
                            ServerSentEvent(DashboardJson.snapshot(it, reportReady()), event = "snapshot", id = "${it.version}")
                        }
                    }

                    Topic.RUN -> {
                        dashboard.updates.map { ServerSentEvent(DashboardJson.header(it, reportReady()), event = "run") }
                    }

                    Topic.ORCHESTRATOR -> {
                        dashboard.orchestratorUpdates.map { ServerSentEvent(PanelJson.orchestrator(it), event = "orchestrator") }
                    }

                    Topic.EXPLORATION -> {
                        explorations().map { ServerSentEvent(PanelJson.encode(PanelJson.exploration(it)), event = "exploration") }
                    }

                    Topic.JOBS -> {
                        explorations()
                            .map { PanelJson.encode(PanelJson.explorationBrief(it)) }
                            .distinctUntilChanged()
                            .map { ServerSentEvent("""{"exploration":$it}""", event = "jobs") }
                    }
                }
            }
        merge(*streams.toTypedArray()).collect { send(it) }
    }
}

internal enum class Topic {
    BOARD,
    RUN,
    ORCHESTRATOR,
    EXPLORATION,
    JOBS,
    ;

    companion object {
        fun parse(query: String?): Set<Topic> {
            val named =
                query
                    .orEmpty()
                    .split(',')
                    .mapNotNull { name -> entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } }
                    .toSet()
            return named.ifEmpty { setOf(BOARD) }
        }
    }
}

/** The current exploration (null when none), then every change the backend reports, without repeats. */
private fun explorations(backend: PanelBackend): Flow<ExplorationView?> =
    flow<ExplorationView?> {
        emit(backend.exploration())
        emitAll(backend.explorationUpdates)
    }.distinctUntilChanged()

/** The first value at once, then at most one per [period], always the latest. */
internal fun <T> Flow<T>.throttledLatest(period: Duration): Flow<T> =
    conflate().transform {
        emit(it)
        delay(period)
    }

private val HEARTBEAT = 15.seconds
private const val RETRY_MILLIS = 2_000L

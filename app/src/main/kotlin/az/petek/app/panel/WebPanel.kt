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
import az.petek.app.di.AppOverrides
import az.petek.app.panel.explorer.RoleSessionSource
import az.petek.app.panel.explorer.SetupRuns
import az.petek.app.panel.runs.DerivedTaskStates
import az.petek.app.panel.runs.PanelRunWatch
import az.petek.capacity.application.RecommendCapacityUseCase
import az.petek.core.time.SystemHarnessClock
import az.petek.dashboard.application.DashboardEvidenceRecorder
import az.petek.dashboard.application.DashboardIdentityRepository
import az.petek.dashboard.application.DashboardRunRepository
import az.petek.dashboard.application.LiveDashboard
import az.petek.dashboard.infrastructure.DashboardServer
import az.petek.orchestration.infrastructure.CompositeMonitorView
import az.petek.orchestration.infrastructure.LoggingMonitorView
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Path

/**
 * The panel without its HTTP server: the live board ([LiveDashboard]), the container every panel action uses (its
 * evidence recorder, run and identity repositories and monitor decorated so the board, the orchestrator matrix and the
 * run watch see everything) and the [AppPanelBackend] over it. [WebPanel] serves it over HTTP; `petek mcp` speaks
 * MCP over it. [close] cancels the backend's work (a run still tears down and writes its report) and closes the
 * container, in that order.
 */
internal class PanelCore private constructor(
    val dashboard: LiveDashboard,
    val container: AppContainer,
    val backend: AppPanelBackend,
) : AutoCloseable {
    override fun close() {
        try {
            backend.close()
        } finally {
            container.close()
        }
    }

    companion object {
        /**
         * Builds the panel's object graph for [config]. [containers] builds a container with the given overrides
         * (production: `AppContainer(config, overrides)`); [roleSessions] replaces the explorer's test-company sessions (tests).
         */
        fun start(
            config: PetekConfig,
            containers: (PetekConfig, AppOverrides) -> AppContainer,
            workingDirectory: Path,
            capacityAdvice: RecommendCapacityUseCase,
            roleSessions: ((SetupRuns) -> RoleSessionSource)? = null,
        ): PanelCore {
            val dashboard = LiveDashboard(SystemHarnessClock())
            val tasks = DerivedTaskStates(dashboard)
            val watch =
                PanelRunWatch { plan ->
                    dashboard.planReady(plan)
                    tasks.planned(plan)
                }
            val overrides =
                AppOverrides(
                    monitor = CompositeMonitorView(listOf(dashboard, tasks, LoggingMonitorView())),
                    recorderDecorator = { DashboardEvidenceRecorder(tasks.recorder(it), dashboard) },
                    runsDecorator = { watch.wrap(DashboardRunRepository(it, dashboard)) },
                    identitiesDecorator = { watch.wrap(DashboardIdentityRepository(it, dashboard)) },
                )
            val container = containers(config, overrides)
            try {
                val backend =
                    AppPanelBackend.create(
                        container = container,
                        workingDirectory = workingDirectory,
                        capacityAdvice = capacityAdvice,
                        watch = watch,
                        board = dashboard,
                        derive = { other -> containers(other, overrides.copy(database = container.database)) },
                        roleSessions = roleSessions,
                    )
                return PanelCore(dashboard, container, backend)
            } catch (e: Exception) {
                container.close()
                throw e
            }
        }
    }
}

/**
 * One running web panel: a [PanelCore] served by the [DashboardServer] on 127.0.0.1. `petek panel` starts one and
 * waits; tests start one on a free port and close it. [close] stops the server first, then the core.
 */
internal class WebPanel private constructor(
    private val core: PanelCore,
    private val server: DashboardServer,
    /** Where the page is served, e.g. `http://127.0.0.1:7070/`. */
    val url: URI,
) : AutoCloseable {
    val dashboard: LiveDashboard get() = core.dashboard
    val container: AppContainer get() = core.container
    val backend: AppPanelBackend get() = core.backend

    override fun close() {
        try {
            server.close()
        } finally {
            core.close()
        }
    }

    companion object {
        /**
         * Starts the panel for [config] (see [PanelCore.start]); [port] 0 takes any free port, another port the next free
         * one from it ([PORT_ATTEMPTS] tries, then any).
         */
        fun start(
            config: PetekConfig,
            containers: (PetekConfig, AppOverrides) -> AppContainer,
            workingDirectory: Path,
            capacityAdvice: RecommendCapacityUseCase,
            port: Int,
            roleSessions: ((SetupRuns) -> RoleSessionSource)? = null,
        ): WebPanel {
            val core = PanelCore.start(config, containers, workingDirectory, capacityAdvice, roleSessions)
            try {
                val (server, url) = bind(core.dashboard, core.container, core.backend, port)
                return WebPanel(core, server, url)
            } catch (e: Exception) {
                core.close()
                throw e
            }
        }

        /** Binds [port], or the next free one when it is taken (another panel, another program). */
        private fun bind(
            dashboard: LiveDashboard,
            container: AppContainer,
            backend: AppPanelBackend,
            port: Int,
        ): Pair<DashboardServer, URI> {
            var last: Exception? = null
            for (candidate in portCandidates(port)) {
                val server =
                    DashboardServer(
                        dashboard,
                        container.artifacts,
                        backend = backend,
                        port = candidate,
                        defaultTarget = container.config.target.toString(),
                    )
                try {
                    return server to server.start()
                } catch (e: Exception) {
                    runCatching { server.close() }
                    last = e
                }
            }
            throw IllegalStateException("The panel could not start on any port", last)
        }

        /**
         * The ports to try for [port]: itself and the next ones that are free now, then any (0). Probed first, since Ktor
         * reports a taken port as an uncaught exception on a worker thread (noise in the console).
         */
        fun portCandidates(port: Int): List<Int> = if (port == 0) listOf(0) else (port until port + PORT_ATTEMPTS).filter(::isFree) + 0

        private fun isFree(port: Int): Boolean =
            runCatching { ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use { true } }.getOrDefault(false)

        const val PORT_ATTEMPTS = 10
    }
}

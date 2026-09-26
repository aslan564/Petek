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

package az.petek.browser.infrastructure

import az.petek.browser.domain.BrowserActionException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * The shared browser servers of one engine run (SHARED_SERVER), sharded by load: each server hosts at most
 * [contextsPerBrowser] open sessions. [reserve] gives a new session a slot on the least-loaded running server that
 * still has room, and starts one more server only when every running one is full, so a run with N agents uses
 * `ceil(N / contextsPerBrowser)` browsers at its peak and a small run never pays for a second one. Closing a
 * session frees its slot ([Lease.release]) for the next session. A server that died is skipped for new sessions.
 *
 * Reservations are serialized by a mutex, also while a server starts, so concurrent sessions never start more
 * servers than they need. [stopAll] stops every server (in parallel) and refuses later reservations; a server that
 * finishes starting after that is stopped at once.
 */
internal class BrowserServerPool(
    private val contextsPerBrowser: Int,
    private val launch: suspend () -> LaunchedServer,
) {
    init {
        require(contextsPerBrowser >= 1) { "contextsPerBrowser must be at least 1, was $contextsPerBrowser" }
    }

    private val reserving = Mutex()
    private val lock = Any()
    private val servers = ArrayList<Server>()
    private var stopped = false

    /** Starts the first server now, so an engine that cannot launch Chromium fails in `start` rather than later. */
    suspend fun startFirst() {
        reserving.withLock {
            requireRunning()
            add(launch())
        }
    }

    /** A slot for one new session; the caller must [Lease.release] it when that session closes or fails to open. */
    suspend fun reserve(): Lease =
        reserving.withLock {
            val server =
                synchronized(lock) {
                    requireRunning()
                    leastLoadedWithRoom()?.also { it.sessions++ }
                } ?: startAnother()
            Lease(server.connector) { synchronized(lock) { server.sessions-- } }
        }

    /** Every server's process tree (host, Chromium and helpers), in start order. */
    fun processTrees(): List<List<ProcessHandle>> = snapshot().map { it.process.processTree() }

    /** The servers in start order. */
    fun processes(): List<BrowserServerProcess> = snapshot().map { it.process }

    /** Open sessions per server, in start order. */
    fun sessionsPerServer(): List<Int> = synchronized(lock) { servers.map { it.sessions } }

    /** The shutdown hook of the first server; the other servers have their own. */
    fun firstShutdownHook(): Thread? = snapshot().firstOrNull()?.shutdownHook

    /** Stops every server and its shutdown hook; idempotent. Blocking work runs on [Dispatchers.IO]. */
    suspend fun stopAll() {
        val all =
            synchronized(lock) {
                stopped = true
                servers.toList()
            }
        withContext(NonCancellable + Dispatchers.IO) {
            coroutineScope { all.map { server -> async { stop(server.launched) } }.awaitAll() }
        }
    }

    private suspend fun startAnother(): Server {
        val server = add(launch())
        val count =
            synchronized(lock) {
                server.sessions++
                servers.size
            }
        logger.info { "started browser server #$count: the others host $contextsPerBrowser sessions each or are gone" }
        return server
    }

    /** Registers a freshly launched server, or stops it again when the pool was stopped meanwhile. */
    private suspend fun add(launched: LaunchedServer): Server {
        val server = Server(launched)
        val accepted =
            synchronized(lock) {
                if (!stopped) servers += server
                !stopped
            }
        if (!accepted) {
            withContext(NonCancellable + Dispatchers.IO) { stop(launched) }
            throw BrowserActionException("the browser engine was stopped while a browser server was starting")
        }
        return server
    }

    private fun requireRunning() {
        if (synchronized(lock) { stopped }) throw BrowserActionException("the browser servers are stopped")
    }

    /** Must hold [lock]. */
    private fun leastLoadedWithRoom(): Server? =
        servers
            .filter { it.sessions < contextsPerBrowser && it.process.isAlive }
            .minByOrNull { it.sessions }

    private fun snapshot(): List<Server> = synchronized(lock) { servers.toList() }

    private fun stop(launched: LaunchedServer) {
        launched.process.stop()
        runCatching { Runtime.getRuntime().removeShutdownHook(launched.shutdownHook) }
    }

    /** A running server and its load. [sessions] is guarded by the pool's lock. */
    private class Server(
        val launched: LaunchedServer,
    ) {
        var sessions = 0
        val process: BrowserServerProcess get() = launched.process
        val connector: BrowserConnector get() = launched.connector
        val shutdownHook: Thread get() = launched.shutdownHook
    }

    /** One session's slot on a server: [connector] reaches the server, [release] frees the slot. */
    class Lease(
        val connector: BrowserConnector,
        private val free: () -> Unit,
    ) {
        private val released = AtomicBoolean(false)

        /** Idempotent, so closing a session twice never frees two slots. */
        fun release() {
            if (released.compareAndSet(false, true)) free()
        }
    }
}

/** A started browser server, the connector sessions use to reach it, and the JVM shutdown hook that kills it. */
internal class LaunchedServer(
    val process: BrowserServerProcess,
    val connector: BrowserConnector,
    val shutdownHook: Thread,
)

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * The child process hosting the shared Chromium ([BundledScripts.browserServer]), and the rules for ending it.
 *
 * Ending it must never leave a Chromium behind. Playwright starts Chromium in its own process group, so killing only
 * the Node.js host would orphan the browser. [stop] therefore closes the host's stdin (the host then closes the
 * browser gracefully and exits), escalates to SIGTERM and SIGKILL when that takes longer than the grace period, and
 * finally kills every process that was in the tree when stopping began. When the JVM dies abruptly, the operating
 * system closes the same stdin pipe, so the host still shuts the browser down.
 */
internal class BrowserServerProcess private constructor(
    private val process: Process,
    /** `ws://127.0.0.1:<port>/<random id>`. The random id is the only credential, so the endpoint is never logged. */
    val wsEndpoint: String,
) {
    private val stopped = AtomicBoolean(false)

    val isAlive: Boolean get() = process.isAlive

    /** The host and all of its descendants (Chromium and its helper processes) at this moment. */
    fun processTree(): List<ProcessHandle> = process.tree()

    /** Simulates the parent going away: the host sees EOF on stdin, exactly as when the JVM is killed. */
    internal fun closeInput() {
        runCatching { process.outputStream.close() }
    }

    /** Blocking and idempotent; returns once the whole tree is gone or [gracePeriod] plus escalation has passed. */
    fun stop(gracePeriod: Duration = DEFAULT_GRACE_PERIOD) {
        if (!stopped.compareAndSet(false, true)) return
        val tree = processTree()
        closeInput()
        if (!process.awaitExit(gracePeriod)) {
            logger.warn { "browser server did not stop within $gracePeriod; terminating it" }
            process.destroy()
            if (!process.awaitExit(ESCALATION_WAIT)) process.destroyForcibly()
        }
        killAll(tree)
    }

    companion object {
        /** The only line of the host's stdout that matters; everything else is kept for error messages. */
        const val ENDPOINT_PREFIX = "PETEK_WS_ENDPOINT="

        private val DEFAULT_GRACE_PERIOD = 5.seconds
        private val ESCALATION_WAIT = 2.seconds

        /**
         * Starts [command] and waits until it prints its endpoint. Fails with a [BrowserActionException] that carries
         * the process output when it exits first or stays silent past [startupTimeout]; the process tree is killed
         * in every failure case, including cancellation of the caller (even once the server was already ready).
         */
        suspend fun start(
            command: ProcessBuilder,
            startupTimeout: Duration,
        ): BrowserServerProcess {
            var ready: BrowserServerProcess? = null
            try {
                return withContext(Dispatchers.IO) { startAndAwaitEndpoint(command, startupTimeout).also { ready = it } }
            } catch (e: CancellationException) {
                // Cancelled while the ready server was being handed back: nobody else will ever stop it.
                ready?.let { server -> withContext(NonCancellable + Dispatchers.IO) { server.stop() } }
                throw e
            }
        }

        private suspend fun startAndAwaitEndpoint(
            command: ProcessBuilder,
            startupTimeout: Duration,
        ): BrowserServerProcess {
            val process =
                try {
                    command.start()
                } catch (e: IOException) {
                    throw BrowserActionException("could not start the browser server process: ${e.message}", e)
                }
            val output = OutputTail()
            val endpoint = CompletableDeferred<String?>()
            val stderrReader = drain("browser-server-stderr", process.errorStream) { output.add(it) }
            drain("browser-server-stdout", process.inputStream, onEnd = { endpoint.complete(null) }) { line ->
                if (line.startsWith(ENDPOINT_PREFIX)) {
                    endpoint.complete(line.removePrefix(ENDPOINT_PREFIX).trim())
                } else {
                    output.add(line)
                }
            }
            try {
                val ws = withTimeout(startupTimeout) { endpoint.await() }
                if (ws != null) return BrowserServerProcess(process, ws)
                process.awaitExit(ESCALATION_WAIT)
                stderrReader.join(ESCALATION_WAIT.inWholeMilliseconds)
                val status = if (process.isAlive) "closed its output" else "exited with code ${process.exitValue()}"
                throw BrowserActionException("the browser server $status before it was ready:\n$output")
            } catch (e: TimeoutCancellationException) {
                killAll(process.tree())
                throw BrowserActionException("the browser server did not become ready within $startupTimeout:\n$output", e)
            } catch (e: CancellationException) {
                killAll(process.tree())
                throw e
            } catch (e: BrowserActionException) {
                killAll(process.tree())
                throw e
            }
        }

        private fun drain(
            name: String,
            stream: InputStream,
            onEnd: () -> Unit = {},
            onLine: (String) -> Unit,
        ): Thread =
            thread(name = name, isDaemon = true) {
                try {
                    stream.bufferedReader().forEachLine { line ->
                        if (!line.startsWith(ENDPOINT_PREFIX)) logger.debug { "$name: $line" }
                        onLine(line)
                    }
                } catch (e: IOException) {
                    logger.debug { "$name closed: ${e.message}" }
                } finally {
                    onEnd()
                }
            }

        private fun killAll(tree: List<ProcessHandle>) {
            tree.filter { it.isAlive }.forEach { it.destroyForcibly() }
            tree.forEach { handle ->
                runCatching { handle.onExit().get(ESCALATION_WAIT.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
            }
        }

        private fun Process.awaitExit(timeout: Duration): Boolean = waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

        private fun Process.tree(): List<ProcessHandle> = listOf(toHandle()) + descendants().toList()
    }
}

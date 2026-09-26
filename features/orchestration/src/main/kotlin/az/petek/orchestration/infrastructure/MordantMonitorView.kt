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

package az.petek.orchestration.infrastructure

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.RunSummary
import com.github.ajalt.mordant.animation.Animation
import com.github.ajalt.mordant.animation.animation
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.Borders
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Text
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

/**
 * Live console board (docs/PLAN.md Faza 3 "vəziyyət lövhəsi"): a headline with the number of agents in each state,
 * one row per agent (id, name, role, state, scenario step, last action) and the latest harness messages, redrawn in
 * place with a Mordant animation.
 *
 * The board fits the terminal whatever the number of agents: it shows at most as many rows as the terminal height
 * leaves after the headline, table frame, messages and summary. When there are more agents, the ones that need
 * attention are shown first (WORKING, BLOCKED, FAILED, WAITING, then IDLE and DONE, each by agent id), listed in
 * agent-id order, and one line counts the rest per state (`… 470 more: 400 idle, 70 done`). Every line is at most
 * as wide as the terminal (the table truncates its cells, the headline and messages are cut, the summary is wrapped
 * into lines of its own): a line the terminal wraps by itself would make the frame taller than the animation
 * believes, and every redraw would then leave a stale line behind.
 *
 * Callers never block and never draw: every [MonitorView] call only updates an in-memory snapshot and marks the board
 * dirty through a conflated channel. A render coroutine owned by this view (on [dispatcher]) draws at most once per
 * [refreshInterval] (250 ms = 4 frames per second by default), so hundreds of agents updating at once cost one frame.
 * [runFinished] draws the final frame and ends the animation, so a following run (`--repeat`) starts a new board
 * below it. [close] stops the render coroutine and synchronously draws whatever has not been drawn yet (never the
 * same frame twice); call it before printing anything else to the terminal.
 *
 * @param maxRows fixed number of agent rows instead of the terminal-height budget (null: follow the terminal).
 */
class MordantMonitorView(
    private val terminal: Terminal,
    private val refreshInterval: Duration = 250.milliseconds,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxMessages: Int = 6,
    private val maxRows: Int? = null,
) : MonitorView,
    AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("petek-monitor"))
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private val agents = ConcurrentHashMap<AgentId, AgentStatus>()
    private val messages = ArrayDeque<String>()
    private val drawLock = Any()
    private val frames = AtomicInteger()
    private val animation: Animation<Board> = terminal.animation { board -> widget(board) }

    @Volatile private var runId: RunId? = null

    @Volatile private var currentStep: String? = null

    @Volatile private var summary: RunSummary? = null

    @Volatile private var closed = false

    /** Last frame on screen; only touched under [drawLock]. */
    private var lastFrame: Board? = null

    /** Number of frames drawn so far (the throttle is observable in tests). */
    internal val framesDrawn: Int get() = frames.get()

    init {
        scope.launch {
            for (change in dirty) {
                draw()
                delay(refreshInterval)
            }
        }
    }

    override fun runStarted(
        runId: RunId,
        agents: List<AgentStatus>,
    ) {
        this.runId = runId
        currentStep = null
        summary = null
        this.agents.clear()
        agents.forEach { this.agents[it.agentId] = it }
        invalidate()
    }

    override fun agentUpdated(status: AgentStatus) {
        agents[status.agentId] = status
        invalidate()
    }

    override fun stepStarted(scenarioStep: String) {
        currentStep = scenarioStep
        invalidate()
    }

    override fun message(text: String) {
        synchronized(messages) {
            messages.addLast(text)
            while (messages.size > maxMessages) messages.removeFirst()
        }
        invalidate()
    }

    override fun runFinished(summary: RunSummary) {
        this.summary = summary
        invalidate()
    }

    override fun close() {
        scope.cancel()
        synchronized(drawLock) {
            if (closed) return
            closed = true
            render(snapshot())
            animation.stop()
        }
    }

    private fun invalidate() {
        if (!closed) dirty.trySend(Unit)
    }

    private fun draw() {
        synchronized(drawLock) {
            if (closed) return
            val board = snapshot()
            render(board)
            if (board.summary != null) animation.stop()
        }
    }

    /** Draws [board] unless it is exactly what is on screen already (e.g. the final frame again on [close]). */
    private fun render(board: Board) {
        if (board == lastFrame) return
        try {
            animation.update(board)
            lastFrame = board
            frames.incrementAndGet()
        } catch (e: Exception) {
            logger.warn(e) { "live board could not be drawn" }
        }
    }

    private fun snapshot(): Board {
        val (width, height) = terminalSize()
        val all = agents.values.toList()
        val shownMessages = synchronized(messages) { messages.toList() }
        val summaryLines = summary?.let { wrap(summaryLine(it), width) }
        val shown = rowsToShow(all, rowBudget(height, shownMessages.size + summaryLines.orEmpty().size))
        val shownIds = shown.mapTo(HashSet()) { it.agentId }
        return Board(
            runId = runId,
            step = currentStep,
            total = all.size,
            counts = countByState(all),
            rows = shown,
            hidden = countByState(all.filter { it.agentId !in shownIds }),
            messages = shownMessages,
            summary = summaryLines,
            width = width,
        )
    }

    /** The rows that fit: everyone when possible, else the agents that need attention first; in agent-id order. */
    private fun rowsToShow(
        all: List<AgentStatus>,
        budget: Int,
    ): List<AgentStatus> {
        if (all.size <= budget) return all.sortedBy { it.agentId }
        return all
            .sortedWith(compareBy<AgentStatus> { ATTENTION.indexOf(it.state) }.thenBy { it.agentId })
            .take(budget)
            .sortedBy { it.agentId }
    }

    /** Agent rows the terminal has room for next to the headline, table frame, "more" line, messages and summary. */
    private fun rowBudget(
        height: Int,
        extraLines: Int,
    ): Int {
        maxRows?.let { return it.coerceAtLeast(MIN_ROWS) }
        return (height - FIXED_LINES - extraLines).coerceAtLeast(MIN_ROWS)
    }

    /** The current width and height; re-read every frame so a resized window gets a fitting board. */
    private fun terminalSize(): Pair<Int, Int> {
        val size = runCatching { terminal.updateSize() }.getOrElse { terminal.size }
        val width = if (size.width > 0) size.width else FALLBACK_WIDTH
        val height = if (size.height > 0) size.height else FALLBACK_HEIGHT
        return width.coerceAtLeast(MIN_WIDTH) to height
    }

    private fun countByState(statuses: List<AgentStatus>): Map<AgentState, Int> = statuses.groupingBy { it.state }.eachCount()

    private fun widget(board: Board): Widget =
        verticalLayout {
            cell(Text(clip(headline(board), board.width)))
            cell(
                table {
                    header { row("Agent", "Name", "Role", "State", "Step", "Last action") }
                    body {
                        // No rule between agent rows: every row costs exactly one line of the height budget.
                        cellBorders = Borders.LEFT_RIGHT
                        board.rows.forEachIndexed { index, status ->
                            row {
                                if (index == board.rows.lastIndex) cellBorders = Borders.LEFT_RIGHT_BOTTOM
                                cells(status.agentId.value, clip(status.displayName, MAX_NAME_CHARS), status.role)
                                cell(status.state.name.lowercase()) { style = styleOf(status.state) }
                                cells(status.scenarioStep ?: "-", clip(status.lastAction.orEmpty(), MAX_ACTION_CHARS))
                            }
                        }
                    }
                },
            )
            moreLine(board)?.let { cell(Text(clip(it, board.width))) }
            board.messages.forEach { cell(Text(clip("• ${it.replace(LINE_BREAKS, " ")}", board.width))) }
            board.summary?.forEach { cell(Text(it)) }
        }

    private fun headline(board: Board): String =
        "Pətək run ${board.runId ?: "-"} · step ${board.step ?: "-"} · ${board.total} agents · ${describeCounts(board.counts, "  ")}"

    /** `… 470 more: 400 idle, 70 done`, or null when every agent has a row. */
    private fun moreLine(board: Board): String? {
        val hidden = board.hidden.values.sum()
        if (hidden == 0) return null
        val perState = AgentState.entries.filter { it in board.hidden }.joinToString(", ") { "${board.hidden[it]} ${it.name.lowercase()}" }
        return "… $hidden more: $perState"
    }

    private fun describeCounts(
        counts: Map<AgentState, Int>,
        separator: String,
    ): String = AgentState.entries.filter { it in counts }.joinToString(separator) { "${it.name.lowercase()} ${counts[it]}" }

    private fun clip(
        text: String,
        maxChars: Int,
    ): String = if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"

    /** [text] broken at spaces into lines of at most [width] characters; a longer word is split. */
    private fun wrap(
        text: String,
        width: Int,
    ): List<String> {
        val lines = ArrayList<String>()
        var line = StringBuilder()
        for (word in text.split(' ').filter { it.isNotEmpty() }.flatMap { it.chunked(width) }) {
            if (line.isNotEmpty() && line.length + 1 + word.length > width) {
                lines += line.toString()
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty() || lines.isEmpty()) lines += line.toString()
        return lines
    }

    private fun summaryLine(summary: RunSummary): String =
        "Run ${summary.runId}: ${summary.outcome} · steps passed ${summary.stepsPassed}, failed ${summary.stepsFailed}" +
            " · assertions failed ${summary.assertionsFailed} · failed agents ${summary.failedAgents}" +
            " · ${summary.durationMs / MILLIS_PER_SECOND} s"

    private fun styleOf(state: AgentState): TextStyle =
        when (state) {
            AgentState.IDLE -> TextColors.gray
            AgentState.WORKING -> TextColors.cyan
            AgentState.WAITING -> TextColors.yellow
            AgentState.BLOCKED -> TextColors.magenta
            AgentState.FAILED -> TextColors.red
            AgentState.DONE -> TextColors.green
        }

    /**
     * One frame: [counts] covers every agent, [rows] the ones shown, [hidden] the others per state; [summary] is the
     * final summary already wrapped to the terminal [width] (null while the run is going).
     */
    private data class Board(
        val runId: RunId?,
        val step: String?,
        val total: Int,
        val counts: Map<AgentState, Int>,
        val rows: List<AgentStatus>,
        val hidden: Map<AgentState, Int>,
        val messages: List<String>,
        val summary: List<String>?,
        val width: Int,
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1000

        /** Which agents get a row first when not all fit: the ones someone should look at. */
        val ATTENTION =
            listOf(AgentState.WORKING, AgentState.BLOCKED, AgentState.FAILED, AgentState.WAITING, AgentState.IDLE, AgentState.DONE)

        /** Headline, table top border, header, header rule, bottom border, the "more" line and the cursor line. */
        const val FIXED_LINES = 7

        /** Rows shown even on a tiny terminal. */
        const val MIN_ROWS = 3

        /** Size assumed when the terminal does not report one (output redirected). */
        const val FALLBACK_HEIGHT = 40
        const val FALLBACK_WIDTH = 80

        /** Narrower terminals still get lines this wide rather than nothing. */
        const val MIN_WIDTH = 20

        private val LINE_BREAKS = Regex("[\r\n]+")

        const val MAX_NAME_CHARS = 32
        const val MAX_ACTION_CHARS = 60
    }
}

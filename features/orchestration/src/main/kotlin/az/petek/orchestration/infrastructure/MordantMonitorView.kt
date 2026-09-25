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
 * Live console board (docs/PLAN.md Faza 3 "vəziyyət lövhəsi"): one row per agent (id, name, role, state, scenario
 * step, last action) plus the latest harness messages, redrawn in place with a Mordant animation.
 *
 * Callers never block and never draw: every [MonitorView] call only updates an in-memory snapshot and marks the board
 * dirty through a conflated channel. A render coroutine owned by this view (on [dispatcher]) draws at most once per
 * [refreshInterval] (250 ms = 4 frames per second by default), so 30 agents updating at once cost one frame.
 * [runFinished] draws the final frame and ends the animation, so a following run (`--repeat`) starts a new board
 * below it. [close] stops the render coroutine and synchronously draws whatever has not been drawn yet (never the
 * same frame twice); call it before printing anything else to the terminal.
 */
class MordantMonitorView(
    private val terminal: Terminal,
    private val refreshInterval: Duration = 250.milliseconds,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxMessages: Int = 6,
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

    private fun snapshot(): Board =
        Board(
            runId = runId,
            step = currentStep,
            agents = agents.values.sortedBy { it.agentId.index },
            messages = synchronized(messages) { messages.toList() },
            summary = summary,
        )

    private fun widget(board: Board): Widget =
        verticalLayout {
            cell(Text(headline(board)))
            cell(
                table {
                    header { row("Agent", "Name", "Role", "State", "Step", "Last action") }
                    body {
                        // No rule between agent rows: 30 agents must fit on one screen.
                        cellBorders = Borders.LEFT_RIGHT
                        board.agents.forEachIndexed { index, status ->
                            row {
                                if (index == board.agents.lastIndex) cellBorders = Borders.LEFT_RIGHT_BOTTOM
                                cells(status.agentId.value, status.displayName, status.role)
                                cell(status.state.name.lowercase()) { style = styleOf(status.state) }
                                cells(status.scenarioStep ?: "-", status.lastAction.orEmpty())
                            }
                        }
                    }
                },
            )
            board.messages.forEach { cell(Text("• $it")) }
            board.summary?.let { cell(Text(summaryLine(it))) }
        }

    private fun headline(board: Board): String {
        val counts = board.agents.groupingBy { it.state }.eachCount()
        val states = AgentState.entries.filter { it in counts }.joinToString("  ") { "${it.name.lowercase()} ${counts[it]}" }
        return "Pətək run ${board.runId ?: "-"} · step ${board.step ?: "-"} · ${board.agents.size} agents · $states"
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

    private data class Board(
        val runId: RunId?,
        val step: String?,
        val agents: List<AgentStatus>,
        val messages: List<String>,
        val summary: RunSummary?,
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1000
    }
}

package az.petek.orchestration.infrastructure

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import az.petek.core.time.HarnessTimestamp
import az.petek.orchestration.domain.AgentState
import az.petek.orchestration.domain.AgentStatus
import az.petek.orchestration.domain.MonitorView
import az.petek.orchestration.domain.RunOutcome
import az.petek.orchestration.domain.RunSummary
import az.petek.orchestration.testing.RecordingMonitor
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KLoggingEventBuilder
import io.github.oshai.kotlinlogging.Level
import io.github.oshai.kotlinlogging.Marker
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorViewsTest {
    private val runId = RunId("run_1")
    private val at = HarnessTimestamp(Instant.parse("2026-01-01T10:00:00Z"), 0)

    private fun status(
        index: Int,
        state: AgentState = AgentState.IDLE,
        step: String? = null,
        action: String? = null,
    ) = AgentStatus(AgentId.of(index), "Tester $index", if (index == 1) "admin" else "employee", state, step, action, at)

    private val summary = RunSummary(runId, RunOutcome.PASSED, 12, 0, 0, 0, "evidence/run_1/report", 65_000)

    private fun TestScope.board(recorder: TerminalRecorder) =
        MordantMonitorView(Terminal(terminalInterface = recorder), 250.milliseconds, StandardTestDispatcher(testScheduler))

    private fun recorder() = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 160, height = 60, outputInteractive = true)

    @Test
    fun `the board shows one row per agent with state, step and last action`() =
        runTest {
            val recorder = recorder()
            val view = board(recorder)

            view.runStarted(runId, listOf(status(1), status(2)))
            view.stepStarted("announce")
            view.agentUpdated(status(1, AgentState.WORKING, "announce", "do: Elan yarat"))
            view.message("a02 failed setup step 'join' (mail_timeout)")
            runCurrent()

            val output = recorder.output()
            output shouldContain "run_1"
            output shouldContain "step announce"
            output shouldContain "a01"
            output shouldContain "Tester 2"
            output shouldContain "working"
            output shouldContain "do: Elan yarat"
            output shouldContain "mail_timeout"
            view.close()
        }

    @Test
    fun `a board with more agents than the terminal fits shows the ones that need attention and counts the rest`() =
        runTest {
            val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 160, height = 20, outputInteractive = true)
            val view = board(recorder)
            val states =
                (1..100).map { i ->
                    when (i) {
                        in 40..42 -> AgentState.WORKING
                        55 -> AgentState.BLOCKED
                        77 -> AgentState.FAILED
                        in 90..91 -> AgentState.WAITING
                        in 1..33 -> AgentState.DONE
                        else -> AgentState.IDLE
                    }
                }

            view.runStarted(runId, states.mapIndexed { i, state -> status(i + 1, state, "announce", "action ${i + 1}") })
            runCurrent()

            val frame = recorder.output()
            frame.trimEnd().lines().size shouldBeLessThanOrEqual 20
            frame shouldContain "100 agents · idle 60  working 3  waiting 2  blocked 1  failed 1  done 33"
            listOf("a40", "a41", "a42", "a55", "a77", "a90", "a91").forEach { frame shouldContain "$it " }
            // 20 lines - 7 fixed = 13 rows: the 7 agents above plus the 6 idle ones with the lowest ids.
            listOf("a34", "a35", "a36", "a37", "a38", "a39").forEach { frame shouldContain "$it " }
            frame shouldNotContain "a43 "
            frame shouldNotContain "a01 "
            frame shouldContain "… 87 more: 54 idle, 33 done"
            val shownIds = Regex("\\ba(\\d+) ").findAll(frame).map { it.groupValues[1].toInt() }.toList()
            shownIds shouldBe shownIds.sorted()
            view.close()
        }

    @Test
    fun `the row budget leaves room for messages and follows a fixed limit when one is given`() =
        runTest {
            val recorder = recorder()
            val view =
                MordantMonitorView(
                    Terminal(terminalInterface = recorder),
                    250.milliseconds,
                    StandardTestDispatcher(testScheduler),
                    maxRows = 5,
                )

            view.runStarted(runId, (1..500).map { status(it, if (it % 100 == 0) AgentState.WORKING else AgentState.IDLE) })
            view.message("a07 failed setup step 'join' (mail_timeout)")
            runCurrent()

            val frame = recorder.output()
            listOf("a100", "a200", "a300", "a400", "a500").forEach { frame shouldContain "$it " }
            frame shouldContain "… 495 more: 495 idle"
            frame shouldContain "mail_timeout"
            view.close()
        }

    @Test
    fun `a board that fits shows every agent and no summary of hidden ones`() =
        runTest {
            val recorder = recorder()
            val view = board(recorder)

            view.runStarted(runId, (1..30).map { status(it) })
            runCurrent()

            val frame = recorder.output()
            (1..30).forEach { frame shouldContain "${AgentId.of(it)} " }
            frame shouldNotContain "more:"
            view.close()
        }

    @Test
    fun `long names and actions are shortened so every agent stays on one line`() =
        runTest {
            val recorder = recorder()
            val view = board(recorder)
            val long = AgentStatus(AgentId.of(1), "Ə".repeat(80), "admin", AgentState.WORKING, "s", "x".repeat(200), at)

            view.runStarted(runId, listOf(long))
            runCurrent()

            val frame = recorder.output()
            frame shouldContain "Ə".repeat(31) + "…"
            frame shouldNotContain "Ə".repeat(32)
            frame shouldContain "x".repeat(59) + "…"
            frame shouldNotContain "x".repeat(60)
            view.close()
        }

    @Test
    fun `on a narrow terminal no line is wider than the terminal, so the frame is exactly as tall as it looks`() =
        runTest {
            val recorder = TerminalRecorder(ansiLevel = AnsiLevel.NONE, width = 80, height = 24, outputInteractive = true)
            val view = board(recorder)
            val longRun = RunId("0199aa11-2b3c-7d4e-8f50-6172839405ab")
            val name = "Günel Vüqar qızı Məmmədova"
            val action = "do: Elan yarat və bütün işçilərə göndər, sonra oxunmanı yoxla"

            view.runStarted(longRun, (1..200).map { AgentStatus(AgentId.of(it), name, "employee", AgentState.WORKING, "s", action, at) })
            view.stepStarted("announce_and_read_receipts")
            view.message("a07 failed setup step 'join' (mail_timeout): no verification e-mail arrived within 90 s\nfor the address")
            runCurrent()

            val frame = recorder.output().trimEnd().lines()
            frame.forEach { line -> line.length shouldBeLessThanOrEqual 80 }
            frame.size shouldBeLessThanOrEqual 24
            frame.first() shouldBe "Pətək run 0199aa11-2b3c-7d4e-8f50-6172839405ab · step announce_and_read_receipt…"
            frame.last() shouldStartWith "• a07 failed setup step 'join' (mail_timeout)"

            recorder.clearOutput()
            view.runFinished(summary.copy(runId = longRun))
            advanceTimeBy(300.milliseconds)
            runCurrent()

            val final = recorder.output().trimEnd().lines()
            final.forEach { line -> line.length shouldBeLessThanOrEqual 80 }
            final.size shouldBeLessThanOrEqual 24
            // The summary is wrapped, not cut: every number is still there.
            final.takeLast(2).joinToString(" ") shouldBe
                "Run 0199aa11-2b3c-7d4e-8f50-6172839405ab: PASSED · steps passed 12, failed 0 · assertions failed 0 · " +
                "failed agents 0 · 65 s"
            view.close()
        }

    @Test
    fun `updates never draw on the caller's thread and many updates cost few frames`() =
        runTest {
            val recorder = recorder()
            val view = board(recorder)
            view.runStarted(runId, (1..30).map { status(it) })

            repeat(400) { i ->
                view.agentUpdated(status(i % 30 + 1, AgentState.WORKING, "step-$i", "action $i"))
                if (i % 40 == 39) advanceTimeBy(100.milliseconds)
            }
            view.framesDrawn shouldBeLessThanOrEqual 5
            runCurrent()

            // 1 s of virtual time at 4 fps: at most 4 frames plus the first one.
            view.framesDrawn shouldBeGreaterThan 0
            view.framesDrawn shouldBeLessThanOrEqual 5
            advanceTimeBy(1_000.milliseconds)
            recorder.output() shouldContain "action 399"
            view.close()
        }

    @Test
    fun `nothing is drawn until the render coroutine runs`() =
        runTest {
            val view = board(recorder())

            view.runStarted(runId, listOf(status(1)))
            view.agentUpdated(status(1, AgentState.WORKING))

            view.framesDrawn shouldBe 0
            view.close()
        }

    @Test
    fun `callers on many threads are never blocked`() =
        runTest {
            val view = board(recorder())
            view.runStarted(runId, (1..30).map { status(it) })

            val threads =
                (1..30).map { i ->
                    thread { repeat(200) { view.agentUpdated(status(i, AgentState.WORKING, "s", "a$it")) } }
                }
            threads.forEach { it.join(5_000) }

            threads.none { it.isAlive } shouldBe true
            view.close()
        }

    @Test
    fun `the finished run shows its summary and close does not print it a second time`() =
        runTest {
            val recorder = recorder()
            val view = board(recorder)
            view.runStarted(runId, listOf(status(1)))
            runCurrent()
            view.runFinished(summary)
            advanceTimeBy(300.milliseconds)
            runCurrent()

            recorder.output() shouldContain "Run run_1: PASSED"
            recorder.output() shouldContain "65 s"
            val frames = view.framesDrawn
            view.close()
            view.framesDrawn shouldBe frames
            recorder.output().windowed("Run run_1: PASSED".length).count { it == "Run run_1: PASSED" } shouldBe 1
        }

    @Test
    fun `close synchronously draws changes the render coroutine has not drawn yet`() =
        runTest {
            val recorder = recorder()
            val view = board(recorder)
            view.runStarted(runId, listOf(status(1)))
            view.agentUpdated(status(1, AgentState.DONE, "announce", "final words"))

            view.close()

            view.framesDrawn shouldBe 1
            recorder.output() shouldContain "final words"
        }

    @Test
    fun `an unchanged board is not redrawn`() =
        runTest {
            val view = board(recorder())
            view.runStarted(runId, listOf(status(1)))
            runCurrent()
            val frames = view.framesDrawn

            repeat(5) {
                view.agentUpdated(status(1))
                advanceTimeBy(300.milliseconds)
            }

            view.framesDrawn shouldBe frames
            view.close()
        }

    @Test
    fun `after close updates are ignored`() =
        runTest {
            val recorder = recorder()
            val view = board(recorder)
            view.runStarted(runId, listOf(status(1)))
            view.close()
            val frames = view.framesDrawn

            view.agentUpdated(status(1, AgentState.FAILED, "x", "late update"))
            advanceTimeBy(1_000.milliseconds)
            view.close()

            view.framesDrawn shouldBe frames
            recorder.output() shouldNotContain "late update"
        }

    @Test
    fun `the composite forwards every call to every view in order`() {
        val first = RecordingMonitor()
        val second = RecordingMonitor()
        val composite = CompositeMonitorView(listOf(first, second))

        composite.runStarted(runId, listOf(status(1)))
        composite.stepStarted("announce")
        composite.agentUpdated(status(1, AgentState.DONE))
        composite.message("hello")
        composite.runFinished(summary)

        first.events shouldContainExactly listOf("runStarted 1", "step announce", "message hello", "runFinished PASSED")
        second.events shouldBe first.events
        second.statuses.single().state shouldBe AgentState.DONE
    }

    @Test
    fun `a failing view does not stop the composite from notifying the others`() {
        val broken =
            object : MonitorView by RecordingMonitor() {
                override fun message(text: String): Unit = error("terminal closed")
            }
        val healthy = RecordingMonitor()

        CompositeMonitorView(listOf(broken, healthy)).message("still delivered")

        healthy.events shouldContainExactly listOf("message still delivered")
    }

    @Test
    fun `the no-op view accepts every call`() {
        NoOpMonitorView.runStarted(runId, listOf(status(1)))
        NoOpMonitorView.agentUpdated(status(1))
        NoOpMonitorView.stepStarted("s")
        NoOpMonitorView.message("m")
        NoOpMonitorView.runFinished(summary)
    }

    @Test
    fun `the logging view logs boundaries at info and trouble at warn`() {
        val logger = CapturingLogger()
        val view = LoggingMonitorView(logger)

        view.runStarted(runId, listOf(status(1), status(2)))
        view.stepStarted("announce")
        view.agentUpdated(status(2, AgentState.WORKING, "announce", "do: read"))
        view.agentUpdated(status(2, AgentState.BLOCKED, "announce", "agent blocked"))
        view.agentUpdated(status(3, AgentState.FAILED, "join", "failed setup: mail_timeout"))
        view.message("teardown failed: company:c1")
        view.runFinished(summary)

        logger.lines.map { it.first } shouldContainExactly
            listOf(Level.INFO, Level.INFO, Level.DEBUG, Level.WARN, Level.WARN, Level.INFO, Level.INFO)
        logger.lines[0].second shouldBe "run run_1 started with 2 agents"
        logger.lines[3].second shouldBe "a02 (employee) blocked in 'announce': agent blocked"
        logger.lines.last().second shouldContain "PASSED"
        logger.lines.last().second shouldContain "evidence/run_1/report"
    }

    private class CapturingLogger : KLogger {
        val lines = CopyOnWriteArrayList<Pair<Level, String>>()

        override val name: String = "capturing"

        override fun at(
            level: Level,
            marker: Marker?,
            block: KLoggingEventBuilder.() -> Unit,
        ) {
            lines += level to KLoggingEventBuilder().apply(block).message.orEmpty()
        }

        override fun isLoggingEnabledFor(
            level: Level,
            marker: Marker?,
        ): Boolean = true
    }
}

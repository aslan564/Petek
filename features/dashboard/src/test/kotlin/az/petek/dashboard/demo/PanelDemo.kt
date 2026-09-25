package az.petek.dashboard.demo

import az.petek.core.ids.UuidV7IdGenerator
import az.petek.core.time.SystemHarnessClock
import az.petek.dashboard.application.LiveDashboard
import az.petek.dashboard.domain.PanelConflictException
import az.petek.dashboard.domain.RunRequest
import az.petek.dashboard.infrastructure.DashboardServer
import az.petek.dashboard.testing.TempDirArtifactStore
import az.petek.evidence.domain.RunResult
import az.petek.evidence.domain.Verdict
import az.petek.orchestration.domain.RunOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files

/**
 * Serves the whole Pətək panel on http://127.0.0.1:7070 with [DemoPanelBackend]: an exploration walks a KadroHR-like
 * site, a simulated 30-tester run plays on the live board and the orchestrator screen, and scenarios, triage and the
 * run history are filled with realistic data. Every button works against the demo backend.
 *
 * `./gradlew :features:dashboard:panelDemo` (arguments: `--port P`, `--agents N`, `--no-run` to start idle).
 * Press Enter to stop.
 */
fun main(args: Array<String>) =
    runBlocking {
        val port = option(args, "--port") ?: DEFAULT_PORT
        val agents = option(args, "--agents") ?: DemoPanelBackend.DEFAULT_TESTERS
        val clock = SystemHarnessClock()
        val ids = UuidV7IdGenerator()
        val dashboard = LiveDashboard(clock)
        val root = Files.createTempDirectory("petek-panel-demo")
        val artifacts = TempDirArtifactStore(root)
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var backend: DemoPanelBackend
        backend =
            DemoPanelBackend(clock, ids, artifacts, jobs, { delay(it) }, root) { request, runId ->
                val run = DemoRun(request.testers ?: agents, dashboard, artifacts, clock, ids, { delay(it * PACE) })
                run.play(runId)
                val failed = run.evidence.assertionList.any { it.verdict == Verdict.FAILED }
                val outcome = if (failed) RunOutcome.FAILED else RunOutcome.PASSED
                val report =
                    backend.writeReport(
                        runId,
                        DemoRun.CAMPAIGN,
                        if (failed) RunResult.FAILED else RunResult.PASSED,
                        run.agents.size,
                    )
                run.finish(outcome, report.toString())
                outcome
            }
        backend.seed(liveExploration = true)
        DashboardServer(dashboard, artifacts, port = port, backend = backend).use { server ->
            val uri = server.start()
            println()
            println("  Pətək panel: $uri")
            println("  Demo backend: kəşfiyyat, $agents testerlik run, ssenarilər və tarixçə simulyasiya olunur.")
            println("  Dayandırmaq üçün Enter basın.")
            println()
            backend.startExploration(DemoPanelBackend.defaultInstructions())
            if ("--no-run" !in args) jobs.launch { keepRunning(backend, agents) }
            withContext(Dispatchers.IO) { readlnOrNull() }
            jobs.cancel()
        }
        root.toFile().deleteRecursively()
        Unit
    }

private const val DEFAULT_PORT = 7070

/** How much slower than the test script the demo plays, so the board can be followed by eye. */
private const val PACE = 3L

/** Starts a run whenever none is going (the owner may start one from the panel too), with a pause between runs. */
private suspend fun keepRunning(
    backend: DemoPanelBackend,
    agents: Int,
) {
    while (true) {
        try {
            backend.startRun(RunRequest(scenarioId = "scn_core_v2", testers = agents))
        } catch (_: PanelConflictException) {
            // the owner started one; wait for it like for our own
        }
        do delay(2_000) while (backend.runs().any { it.result == RunResult.RUNNING })
        delay(20_000)
    }
}

private fun option(
    args: Array<String>,
    name: String,
): Int? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() }

package az.petek.app.cli

import az.petek.app.testing.CliHarness
import az.petek.app.testing.CliHarness.Companion.tinyCampaign
import az.petek.core.ids.RunId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ReportCommandTest {
    @TempDir
    lateinit var dir: Path

    private suspend fun finishedRun(cli: CliHarness): String {
        cli.write("tiny.yaml", tinyCampaign())
        cli.run("run", "tiny.yaml").statusCode shouldBe 0
        return cli.evidence {
            it.evidence
                .latest()!!
                .runId.value
        }
    }

    @Test
    fun `the report of a run is rewritten from its evidence`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            val runId = finishedRun(cli)
            val report = cli.evidenceDir.resolve(runId).resolve("report")
            Files.delete(report.resolve("index.html"))
            val findings = cli.evidence { it.evidence.findings(RunId(runId)) }

            val result = cli.run("report", runId)

            result.statusCode shouldBe 0
            result.stdout shouldContain "Report of $runId: ${report.resolve("index.html")}"
            result.stdout shouldContain "Markdown: ${report.resolve("report.md")}"
            Files.isRegularFile(report.resolve("index.html")) shouldBe true
            cli.evidence { it.evidence.findings(RunId(runId)) } shouldBe findings
        }

    @Test
    fun `latest picks the most recent run`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            val runId = finishedRun(cli)

            val result = cli.run("report", "latest")

            result.statusCode shouldBe 0
            result.stdout shouldContain "Report of $runId"
        }

    @Test
    fun `an unknown run exits with 1 and says so`() =
        runBlocking<Unit> {
            val result = CliHarness(dir).run("report", "run_does_not_exist")

            result.statusCode shouldBe 1
            result.stderr shouldContain "Run 'run_does_not_exist' not found"
        }

    @Test
    fun `latest without any run exits with 1`() =
        runBlocking<Unit> {
            val result = CliHarness(dir).run("report", "latest")

            result.statusCode shouldBe 1
            result.stderr shouldContain "No run is recorded"
        }
}

package az.petek.app.cli

import az.petek.app.testing.CliHarness
import az.petek.app.testing.CliHarness.Companion.tinyCampaign
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PetekCommandTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `help lists every command`() =
        runBlocking<Unit> {
            val result = CliHarness(dir).run("--help")

            result.statusCode shouldBe 0
            listOf("plan", "run", "report", "teardown", "smoke", "doctor", "probe", "--env-file", "--verbose").forEach {
                result.stdout shouldContain it
            }
        }

    @Test
    fun `the env file of the working directory is read`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.env.remove("PETEK_TARGET")
            cli.write(".env", "PETEK_TARGET=https://from-dotenv.test\n")
            cli.write("tiny.yaml", tinyCampaign())

            val result = cli.run("plan", "tiny.yaml")

            result.statusCode shouldBe 0
            result.stdout shouldContain "against https://from-dotenv.test"
        }

    @Test
    fun `another env file can be chosen`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.env.remove("PETEK_TARGET")
            cli.write("staging.env", "PETEK_TARGET=https://from-option.test\n")
            cli.write("tiny.yaml", tinyCampaign())

            val result = cli.run("--env-file", "staging.env", "plan", "tiny.yaml")

            result.statusCode shouldBe 0
            result.stdout shouldContain "against https://from-option.test"
        }

    @Test
    fun `a missing env file given on the command line is a configuration error`() =
        runBlocking<Unit> {
            val result = CliHarness(dir).run("--env-file", "absent.env", "plan", "tiny.yaml")

            result.statusCode shouldBe 2
            result.stderr shouldContain "--env-file"
            result.stderr shouldContain "does not exist"
        }

    @Test
    fun `errors are one line without a stack trace`() =
        runBlocking<Unit> {
            val result = CliHarness(dir).run("report", "run_unknown")

            result.stderr.trim().lines() shouldHaveSize 1
            result.stderr shouldNotContain "RunNotFoundException"
        }

    @Test
    fun `verbose output adds the stack trace`() =
        runBlocking<Unit> {
            val result = CliHarness(dir).run("--verbose", "report", "run_unknown")

            result.stderr shouldContain "RunNotFoundException"
            result.stderr shouldContain "at az.petek.reporting.application.FinalizeRunUseCase"
        }

    @Test
    fun `logging is pointed at the evidence directory with the verbosity asked for`() =
        runBlocking<Unit> {
            val cli = CliHarness(dir)
            cli.write("tiny.yaml", tinyCampaign())

            cli.run("-v", "plan", "tiny.yaml")

            val settings = cli.loggingRequests.single()
            settings.directory shouldBe dir.resolve("evidence/logs")
            settings.verbose shouldBe true
        }

    @Test
    fun `an unknown command is a usage error`() =
        runBlocking<Unit> {
            val harness = CliHarness(dir)
            val result = harness.run("deploy")

            result.stderr.lowercase() shouldContain "no such subcommand"
            PetekCli(harness.runtime).execute(listOf("deploy")) shouldBe ExitCodes.CONFIG_OR_ABORTED
        }
}

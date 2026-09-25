package az.petek.app.cli

import az.petek.app.testing.CliHarness
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PetekCliTest {
    @TempDir
    lateinit var dir: Path

    private val cli: PetekCli get() = PetekCli(CliHarness(dir).runtime)

    @Test
    fun `a successful command line returns 0 instead of exiting`() =
        runBlocking<Unit> {
            cli.execute(listOf("--help")) shouldBe ExitCodes.OK
        }

    @Test
    fun `a wrong command line exits with 2, never with the 1 of a failed run`() =
        runBlocking<Unit> {
            cli.execute(listOf("--verbose")) shouldBe ExitCodes.CONFIG_OR_ABORTED
            cli.execute(listOf("no-such-command")) shouldBe ExitCodes.CONFIG_OR_ABORTED
            cli.execute(listOf("run")) shouldBe ExitCodes.CONFIG_OR_ABORTED
            cli.execute(listOf("run", "tiny.yaml", "--agents", "0")) shouldBe ExitCodes.CONFIG_OR_ABORTED
            cli.execute(listOf("plan", "tiny.yaml", "--no-such-option")) shouldBe ExitCodes.CONFIG_OR_ABORTED
        }

    @Test
    fun `no command at all prints the help with a getting-started guide and exits with 0`() =
        runBlocking<Unit> {
            cli.execute(emptyList()) shouldBe ExitCodes.OK
        }

    @Test
    fun `help of a command exits with 0`() =
        runBlocking<Unit> {
            cli.execute(listOf("run", "--help")) shouldBe ExitCodes.OK
        }

    @Test
    fun `a failing command returns its exit code`() =
        runBlocking<Unit> {
            cli.execute(listOf("report", "run_unknown")) shouldBe ExitCodes.FAILURE
            cli.execute(listOf("--env-file", "absent.env", "doctor")) shouldBe ExitCodes.CONFIG_OR_ABORTED
        }
}

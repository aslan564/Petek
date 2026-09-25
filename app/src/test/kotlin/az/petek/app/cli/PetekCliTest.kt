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
    fun `a usage error returns Clikt's status`() =
        runBlocking<Unit> {
            cli.execute(listOf("no-such-command")) shouldBe 1
        }

    @Test
    fun `a failing command returns its exit code`() =
        runBlocking<Unit> {
            cli.execute(listOf("report", "run_unknown")) shouldBe ExitCodes.FAILURE
            cli.execute(listOf("--env-file", "absent.env", "doctor")) shouldBe ExitCodes.CONFIG_OR_ABORTED
        }
}

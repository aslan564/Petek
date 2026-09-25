package az.petek.app.cli

import az.petek.app.testing.CliHarness
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CliSessionTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a configuration is named by the env-file option or by a dot-env file, and nothing else`() {
        val cli = CliHarness(dir)

        CliSession(cli.runtime, envFile = null, verbose = false).hasConfigurationFile shouldBe false
        CliSession(cli.runtime, envFile = Path.of("staging.env"), verbose = false).hasConfigurationFile shouldBe true
        cli.write(".env", "PETEK_TARGET=https://from-dotenv.test\n")
        CliSession(cli.runtime, envFile = null, verbose = false).hasConfigurationFile shouldBe true
    }
}

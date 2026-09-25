package az.petek.app.cli

import az.petek.app.testing.CliHarness
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class StartMenuTest {
    @TempDir
    lateinit var dir: Path

    private val printed = CopyOnWriteArrayList<String>()
    private val opened = CopyOnWriteArrayList<String>()

    private fun cli(vararg typed: String): PetekCli {
        val lines = ArrayDeque(typed.toList())
        val base = CliHarness(dir).runtime
        val runtime =
            CliRuntime(
                environment = base.environment,
                workingDirectory = base.workingDirectory,
                identitySecrets = base.identitySecrets,
                containers = base.containers,
                configureLogging = base.configureLogging,
                observationWindow = base.observationWindow,
                input = { lines.removeFirstOrNull() },
                output = { printed += it },
                openInBrowser = {
                    opened += it
                    true
                },
            )
        return PetekCli(runtime)
    }

    private val output: String get() = printed.joinToString("\n")

    @Test
    fun `without input the menu is shown once and the program ends successfully`() =
        runBlocking<Unit> {
            cli().execute(emptyList()) shouldBe ExitCodes.OK
            output shouldContain "Pətək — nə etmək istəyirsiniz?"
        }

    @Test
    fun `zero ends the menu`() =
        runBlocking<Unit> {
            cli("0").execute(emptyList()) shouldBe ExitCodes.OK
        }

    @Test
    fun `an unknown choice is explained and the menu is shown again`() =
        runBlocking<Unit> {
            cli("9", "0").execute(emptyList()) shouldBe ExitCodes.OK
            output shouldContain "Belə seçim yoxdur: '9'"
            printed.count { it.contains("nə etmək istəyirsiniz") } shouldBe 2
        }

    @Test
    fun `opening the latest report without any run says so`() =
        runBlocking<Unit> {
            cli("5", "0").execute(emptyList()) shouldBe ExitCodes.OK
            output shouldContain "Hələ hesabat yoxdur"
            opened shouldBe emptyList()
        }

    @Test
    fun `the newest report is opened in the browser`() =
        runBlocking<Unit> {
            val older = dir.resolve("evidence/run_1/report/index.html").also { Files.createDirectories(it.parent) }
            Files.writeString(older, "<html>1</html>")
            Thread.sleep(20)
            val newer = dir.resolve("evidence/run_2/report/index.html").also { Files.createDirectories(it.parent) }
            Files.writeString(newer, "<html>2</html>")
            cli("5", "0").execute(emptyList()) shouldBe ExitCodes.OK
            opened shouldContain newer.toString()
        }

    @Test
    fun `the panel choice explains that the panel is coming`() =
        runBlocking<Unit> {
            cli("6", "0").execute(emptyList()) shouldBe ExitCodes.OK
            output shouldContain "Veb panel"
        }

    @Test
    fun `demo choices need the campaign file of the project`() =
        runBlocking<Unit> {
            cli("3", "0").execute(emptyList()) shouldBe ExitCodes.OK
            output shouldContain "scenarios/kadrohr.yaml tapılmadı"
        }

    @Test
    fun `the identity plan runs against a fake target the menu starts itself`() =
        runBlocking<Unit> {
            val campaign = Path.of("..", "scenarios", "kadrohr.yaml").toAbsolutePath().normalize()
            Files.createDirectories(dir.resolve("scenarios"))
            Files.copy(campaign, dir.resolve("scenarios/kadrohr.yaml"))
            cli("3", "0").execute(emptyList()) shouldBe ExitCodes.OK
            output shouldContain "Lokal test saytı hazırdır"
            output shouldContain "✓ Hazırdır."
            Files.readString(dir.resolve("evidence/demo/demo.env")) shouldContain "PETEK_TARGET=http://127.0.0.1:"
        }
}

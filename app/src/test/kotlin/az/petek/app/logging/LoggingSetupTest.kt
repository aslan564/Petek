package az.petek.app.logging

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunId
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

class LoggingSetupTest {
    @TempDir
    lateinit var dir: Path

    private val properties = listOf(LoggingSetup.DIRECTORY_PROPERTY, LoggingSetup.LEVEL_PROPERTY, LoggingSetup.CONSOLE_LEVEL_PROPERTY)

    /** Puts the test logging configuration back, so later tests do not write into this test's directory. */
    @AfterEach
    fun restoreTestLogging() {
        properties.forEach(System::clearProperty)
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        context.reset()
        JoranConfigurator().apply { this.context = context }.doConfigure(javaClass.getResource("/logback-test.xml"))
    }

    @Test
    fun `log lines go to the configured directory with run and agent ids`() {
        LoggingSetup.apply(LoggingSettings(dir.resolve("logs"), verbose = false, interactive = true))
        val logger = KotlinLogging.logger("az.petek.test")

        runBlocking<Unit> {
            withContext(MdcDiagnosticContext.of(RunId("run_7"), AgentId("a03"))) { logger.info { "agent line" } }
        }
        logger.debug { "debug line" }
        KotlinLogging.logger("Exposed").info { "SELECT with a password" }

        val log = Files.readString(dir.resolve("logs/petek.log"))
        log shouldContain "run_id=run_7 agent_id=a03"
        log shouldContain "agent line"
        log shouldNotContain "debug line"
        log shouldNotContain "SELECT with a password"
    }

    @Test
    fun `verbose output logs debug lines`() {
        LoggingSetup.apply(LoggingSettings(dir, verbose = true, interactive = true))

        KotlinLogging.logger("az.petek.test").debug { "debug line" }

        Files.readString(dir.resolve("petek.log")) shouldContain "debug line"
    }

    @Test
    fun `the board's log copy goes to the file only`() {
        LoggingSetup.apply(LoggingSettings(dir, verbose = false, interactive = true))

        KotlinLogging.logger("az.petek.board").info { "board line" }

        Files.readString(dir.resolve("petek.log")) shouldContain "board line"
    }

    @Test
    fun `the console shows progress only when no live board is drawn`() {
        LoggingSetup.consoleLevel(LoggingSettings(dir, verbose = false, interactive = true)) shouldBe "WARN"
        LoggingSetup.consoleLevel(LoggingSettings(dir, verbose = false, interactive = false)) shouldBe "INFO"
        LoggingSetup.consoleLevel(LoggingSettings(dir, verbose = true, interactive = true)) shouldBe "DEBUG"
    }
}

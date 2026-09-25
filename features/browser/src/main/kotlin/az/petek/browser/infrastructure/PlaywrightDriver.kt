package az.petek.browser.infrastructure

import az.petek.browser.domain.BrowserActionException
import com.microsoft.playwright.impl.driver.Driver
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * The Node.js driver bundled in the Playwright jar: the same `node` binary and playwright-core package that every
 * `Playwright.create()` talks to, so the browser server and its clients always speak the same protocol version and
 * no separate Node.js installation is needed.
 *
 * Browsers are installed here, Chromium only. Left to itself, the Java driver installs every default browser
 * (Firefox and WebKit too) on the first `Playwright.create()`; extracting the driver without that step first means
 * later `Playwright.create()` calls in this JVM reuse the extracted driver and download nothing. Setting
 * `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` (as Playwright itself honours it) skips the installation, e.g. on machines
 * without internet where browsers are provisioned separately.
 */
internal class PlaywrightDriver(
    private val installTimeout: Duration = 10.minutes,
    private val environment: Map<String, String> = System.getenv(),
) {
    private val driver: Driver by lazy {
        try {
            Driver.ensureDriverInstalled(emptyMap(), false)
        } catch (e: RuntimeException) {
            throw BrowserActionException("could not extract the Playwright driver: ${e.message}", e)
        }
    }

    /** Downloads Chromium and its headless shell when missing; only a quick check when they are installed. Blocking. */
    fun installChromium() {
        val skip = environment[SKIP_DOWNLOAD_VARIABLE]
        if (!skip.isNullOrBlank() && skip != "0" && !skip.equals("false", ignoreCase = true)) {
            logger.info { "$SKIP_DOWNLOAD_VARIABLE is set; not installing Chromium" }
            return
        }
        val builder = driver.createProcessBuilder().redirectErrorStream(true)
        builder.command().addAll(listOf("install", "chromium"))
        val output = OutputTail()
        val process =
            try {
                builder.start()
            } catch (e: java.io.IOException) {
                throw BrowserActionException("could not run the Playwright installer: ${e.message}", e)
            }
        val reader =
            thread(name = "playwright-install-output", isDaemon = true) {
                process.inputStream.bufferedReader().forEachLine { line ->
                    output.add(line)
                    logger.debug { "playwright install: $line" }
                }
            }
        if (!process.waitFor(installTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw BrowserActionException("installing Chromium did not finish within $installTimeout:\n$output")
        }
        reader.join(OUTPUT_DRAIN_MILLIS)
        val exitCode = process.exitValue()
        if (exitCode != 0) throw BrowserActionException("installing Chromium failed with exit code $exitCode:\n$output")
    }

    /**
     * Command that runs [BundledScripts.browserServer] on the bundled Node.js with the given `launchServer`
     * options (a JSON object). The child inherits the JVM environment, e.g. `PLAYWRIGHT_BROWSERS_PATH`.
     */
    fun browserServerCommand(launchOptionsJson: String): ProcessBuilder {
        val builder = driver.createProcessBuilder()
        val node = builder.command().first()
        val playwrightCore =
            driver
                .driverDir()
                .resolve("package")
                .toAbsolutePath()
                .toString()
        return builder.command(node, "-e", BundledScripts.browserServer, playwrightCore, launchOptionsJson)
    }

    private companion object {
        const val OUTPUT_DRAIN_MILLIS = 1_000L
        const val SKIP_DOWNLOAD_VARIABLE = "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"
    }
}

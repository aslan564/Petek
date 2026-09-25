package az.petek.app.demo

import az.petek.faketarget.FakeTargetConfig
import az.petek.faketarget.FakeTargetServer
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The local fake KadroHR (testing/fake-target) for demos started from the menu. It reuses a fake target that is
 * already listening on the demo ports (e.g. started from IntelliJ's "1. Fake KadroHR"), otherwise it starts one in
 * this process, and writes a matching configuration file for the CLI commands.
 */
internal class DemoTarget(
    private val workingDirectory: Path,
) : AutoCloseable {
    private var server: FakeTargetServer? = null

    /** Makes sure a fake target is running and returns the path of the configuration file pointing at it. */
    fun start(): Path {
        val (target, mailpit) =
            if (isHealthy(URI("http://127.0.0.1:$PORT"))) {
                URI("http://127.0.0.1:$PORT") to URI("http://127.0.0.1:$MAIL_PORT")
            } else {
                val started = startServer()
                server = started
                started.baseUrl to started.mailpitUrl
            }
        val file = workingDirectory.resolve("evidence").resolve("demo").resolve("demo.env")
        Files.createDirectories(file.parent)
        Files.writeString(file, environment(target, mailpit))
        return file
    }

    private fun startServer(): FakeTargetServer {
        val config = FakeTargetConfig(raceWindow = 15.seconds)
        return runCatching { FakeTargetServer(config).start(PORT, MAIL_PORT) }
            // The demo ports are taken by something else: any free ports will do, the configuration follows them.
            .getOrElse { FakeTargetServer(config).start(0, 0) }
    }

    private fun isHealthy(base: URI): Boolean =
        runCatching {
            val connection = base.resolve("/healthz").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 500
            connection.readTimeout = 1000
            try {
                connection.responseCode == 200
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)

    override fun close() {
        server?.close()
        server = null
    }

    companion object {
        const val PORT = 18080
        const val MAIL_PORT = 18025

        /** Same values as `.env.fake-target`; the fake target's token only unlocks the local fake site. */
        fun environment(
            target: URI,
            mailpit: URI,
        ): String =
            """
            # Written by the Pətək start menu for the local demo; regenerated on every start.
            PETEK_TARGET=$target
            PETEK_PRODUCTION_HOSTS=kadrohr.com,www.kadrohr.com
            PETEK_ALLOW_PRODUCTION=true
            PETEK_TEST_TOKEN=dev-token
            PETEK_MAILPIT_URL=$mailpit
            PETEK_MAIL_DOMAIN=test.kadrohr.com
            PETEK_IDENTITY_SECRET=local-demo-secret
            PETEK_LLM_PROVIDER=claude-cli
            PETEK_LLM_MODEL=claude-sonnet-5
            PETEK_LLM_CONCURRENCY=6
            PETEK_BROWSER_HEADLESS=true
            PETEK_EVIDENCE_DIR=evidence
            PETEK_DB=evidence/petek.db
            """.trimIndent() + "\n"
    }
}

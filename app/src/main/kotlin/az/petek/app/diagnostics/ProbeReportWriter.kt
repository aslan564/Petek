package az.petek.app.diagnostics

import az.petek.app.config.PetekConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Renders a [ProbeReport] as Markdown (`evidence/probe/report.md`), readable on its own and diff-friendly. */
object ProbeReportWriter {
    fun markdown(
        report: ProbeReport,
        probedAt: Instant,
    ): String =
        buildString {
            appendLine("# Target readiness: ${PetekConfig.masked(report.target)}")
            appendLine()
            appendLine("Probed at $probedAt, anonymously (no form submitted). Contract: docs/TARGET_CONTRACT.md.")
            appendLine()
            appendLine("**Verdict: ${if (report.ready) "ready" else "not ready"}**")
            appendLine()
            appendLine("## Public pages")
            appendLine()
            appendLine("| Page | Path | HTTP | Opened as | Present | Missing |")
            appendLine("|---|---|---|---|---|---|")
            report.pages.forEach { page ->
                appendLine(
                    "| ${page.key} | `${page.path}` | ${cell(page.http.toString())} | ${cell(page.finalUrl ?: "-")} | " +
                        "${ids(page.present)} | ${if (page.error != null) cell("error: ${page.error}") else missing(page)} |",
                )
            }
            report.pages.filter { it.note != null && it.missing.isNotEmpty() }.forEach { appendLine("\n> ${it.key}: ${it.note}") }
            appendLine()
            appendLine("## Test API (`/test/...`)")
            appendLine()
            appendLine("- Without `X-Test-Token`: ${cell(report.testApi.anonymous.toString())} — ${anonymousMeaning(report.testApi)}")
            appendLine("- With `X-Test-Token`: ${tokenMeaning(report.testApi.withToken)}")
            appendLine()
            appendLine("## Real-time transport (home page)")
            appendLine()
            val realtime = report.realtime
            when {
                realtime.error != null -> appendLine("Not observed: ${cell(realtime.error)}")
                realtime.transports.isEmpty() -> appendLine("None detected (an anonymous visit may be redirected to the login page).")
                else -> appendLine(realtime.transports.joinToString(", ") { it.name.lowercase() })
            }
            realtime.details.forEach { appendLine("- ${cell(it)}") }
        }

    /** Writes the report atomically to `<directory>/report.md` and returns the file. */
    fun write(
        report: ProbeReport,
        probedAt: Instant,
        directory: Path,
    ): Path {
        Files.createDirectories(directory)
        val target = directory.resolve(FILE_NAME)
        val temporary = Files.createTempFile(directory, ".$FILE_NAME.", ".tmp")
        try {
            Files.writeString(temporary, markdown(report, probedAt))
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return target
    }

    const val FILE_NAME = "report.md"

    private fun anonymousMeaning(api: TestApiProbe): String =
        when ((api.anonymous as? HttpCheck.Answered)?.status) {
            UNAUTHORIZED -> "the test API exists and requires the token (as it should)"
            NOT_FOUND -> "not found: the target has no test API or does not run in test mode"
            OK -> "answers WITHOUT a token: the test API must refuse requests without X-Test-Token (401)"
            null -> "no answer"
            else -> "unexpected; the contract expects 401 without a token"
        }

    private fun tokenMeaning(check: TestApiProbe.TokenCheck): String =
        when (check) {
            is TestApiProbe.TokenCheck.Answered -> {
                "HTTP ${check.status} — " +
                    when {
                        check.acceptable -> "the token is accepted"
                        check.status == UNAUTHORIZED -> "the token is rejected; check PETEK_TEST_TOKEN"
                        else -> "unexpected; the contract expects 200 or 404"
                    }
            }

            is TestApiProbe.TokenCheck.Failed -> {
                "failed: ${cell(check.error)}"
            }

            is TestApiProbe.TokenCheck.NotSent -> {
                "not sent: ${check.reason}"
            }
        }

    private fun missing(page: PageProbe): String =
        ids(page.missing) + if (!page.elementsRequired && page.missing.isNotEmpty()) " (not required)" else ""

    private fun ids(selectors: List<String>): String =
        if (selectors.isEmpty()) "-" else selectors.joinToString(", ") { "`${testIdOf(it)}`" }

    /** `[data-testid="login-email"]` -> `login-email`; other selectors are shown as they are. */
    fun testIdOf(selector: String): String = TEST_ID.matchEntire(selector)?.groupValues?.get(1) ?: selector

    /** Table cells must not break the table. */
    private fun cell(text: String): String = text.replace("|", "\\|").replace(Regex("\\s+"), " ")

    private const val OK = 200
    private const val UNAUTHORIZED = 401
    private const val NOT_FOUND = 404
    private val TEST_ID = Regex("""\[data-testid="([^"]+)"]""")
}

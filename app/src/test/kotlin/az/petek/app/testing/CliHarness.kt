/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.app.testing

import az.petek.app.cli.CliRuntime
import az.petek.app.cli.PetekCommand
import az.petek.app.config.IdentitySecretSource
import az.petek.app.di.AppContainer
import az.petek.app.di.AppOverrides
import az.petek.app.diagnostics.TargetReachability
import az.petek.app.logging.LoggingSettings
import az.petek.browser.domain.BrowserEngine
import az.petek.browser.domain.HtmlPdfPrinter
import az.petek.capacity.domain.HostResourceProbe
import az.petek.capacity.domain.HostResources
import az.petek.core.sqlite.SqliteDatabase
import az.petek.core.testing.FakeHarnessClock
import az.petek.evidence.infrastructure.SqliteEvidenceStore
import az.petek.identity.infrastructure.SqliteIdentityRepository
import az.petek.llm.domain.LlmClient
import az.petek.orchestration.infrastructure.NoOpMonitorView
import az.petek.ownership.application.SiteOwnership
import az.petek.ownership.testing.OwnershipTestKit
import com.github.ajalt.clikt.command.test
import com.github.ajalt.clikt.testing.CliktCommandTestResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/**
 * Runs `petek` command lines in-process against a temporary working directory, with production wiring except for
 * the LLM (scripted), the browser (fake) and the monitor (silent). Logging is not reconfigured; the requested
 * settings are recorded instead. The identity secret comes from the environment, never from the real `~/.petek`, and
 * Pətək's home (the owner's workspace) is [home], inside the temporary directory, never the real one either.
 */
class CliHarness(
    val workingDirectory: Path,
    environment: Map<String, String> = emptyMap(),
    var llm: LlmClient = scriptedLlm { done() },
    var browser: FakeBrowserEngine = FakeBrowserEngine(),
    /** The fake browser plays the site, so the target is never contacted unless a test says otherwise. */
    var reachability: TargetReachability = TargetReachability.ALWAYS,
    /** Every site counts as proved to be the tester's own unless a test says otherwise (ADR-0012). */
    var ownership: SiteOwnership = OwnershipTestKit.owned(FakeHarnessClock()),
    /** The explorer's browser of the panel-backed commands (`petek test`); null: [browser] plays it too. */
    var explorerBrowser: BrowserEngine? = null,
    /** A roomy machine unless a test says otherwise: capacity advice never warns by accident. */
    var hostResources: HostResourceProbe = HostResourceProbe { HostResources(64L shl 30, 32L shl 30, 32) },
    /** Prints a report "as a PDF" without Chromium: the file holds a PDF header and the page it was printed from. */
    var pdfPrinter: HtmlPdfPrinter = HtmlPdfPrinter { html, pdf -> Files.writeString(pdf, "%PDF-fake " + html.fileName) },
) {
    val env: MutableMap<String, String> = (defaultEnvironment() + environment).toMutableMap()
    val loggingRequests = CopyOnWriteArrayList<LoggingSettings>()

    /** What the commands opened in the owner's browser (the panel, the setup page, a report); nothing is opened for real. */
    val opened = CopyOnWriteArrayList<String>()

    /** What `petek mcp` reads and writes its protocol on; empty input ends the server at once. */
    var standardInput: InputStream = ByteArrayInputStream(ByteArray(0))
    var standardOutput: OutputStream = ByteArrayOutputStream()

    /** Pətək's home for this harness: the owner's workspace lives in its `workspace` directory. */
    val home: Path get() = workingDirectory.resolve("petek-home")
    val workspace: Path get() = home.resolve(CliRuntime.WORKSPACE)

    val evidenceDir: Path get() = workingDirectory.resolve("evidence")
    val dbPath: Path get() = evidenceDir.resolve("petek.db")

    val runtime: CliRuntime
        get() =
            CliRuntime(
                environment = { env.toMap() },
                workingDirectory = workingDirectory,
                identitySecrets = IdentitySecretSource { error("tests must set PETEK_IDENTITY_SECRET instead of using ~/.petek") },
                containers = { config ->
                    AppContainer(
                        config,
                        AppOverrides(
                            llm = llm,
                            monitor = NoOpMonitorView,
                            browser = browser,
                            reachability = reachability,
                            ownership = ownership,
                            pdfPrinter = pdfPrinter,
                            hostResources = hostResources,
                        ),
                    )
                },
                configureLogging = { loggingRequests += it },
                observationWindow = Duration.ZERO,
                panelContainers = { config, overrides ->
                    AppContainer(
                        config,
                        overrides.copy(
                            llm = llm,
                            browser = browser,
                            explorerBrowser = explorerBrowser,
                            reachability = reachability,
                            ownership = ownership,
                            pdfPrinter = pdfPrinter,
                            hostResources = hostResources,
                        ),
                    )
                },
                openInBrowser = {
                    opened += it
                    true
                },
                siteReachability = reachability,
                standardInput = standardInput,
                standardOutput = standardOutput,
                home = home,
                hostResources = hostResources,
            )

    suspend fun run(vararg args: String): CliktCommandTestResult = PetekCommand(runtime).test(args.toList(), width = WIDE)

    fun write(
        name: String,
        content: String,
    ): Path = workingDirectory.resolve(name).also { Files.writeString(it, content.trimIndent()) }

    /** Opens the evidence database the commands wrote, for assertions, and closes it afterwards. */
    suspend fun <T> evidence(block: suspend (Stores) -> T): T =
        SqliteDatabase.open(dbPath).use { db -> block(Stores(SqliteEvidenceStore(db), SqliteIdentityRepository(db))) }

    class Stores(
        val evidence: SqliteEvidenceStore,
        val identities: SqliteIdentityRepository,
    )

    private fun defaultEnvironment(): Map<String, String> =
        mapOf(
            "PETEK_TARGET" to UNUSED_TARGET,
            "PETEK_MAILPIT_URL" to "http://127.0.0.1:9",
            "PETEK_IDENTITY_SECRET" to "app-test-identity-secret-0123456789",
            "PETEK_EVIDENCE_DIR" to "evidence",
            // Pətək knows no production host by default; the CLI tests refuse the portal's.
            "PETEK_PRODUCTION_HOSTS" to "portal.example,www.portal.example",
            "PETEK_MAIL_DOMAIN" to "test.portal.example",
        )

    companion object {
        /** Discard port: nothing answers, and tests that use this target never contact it. */
        const val UNUSED_TARGET = "http://127.0.0.1:9"
        private const val WIDE = 250

        /** A campaign of [testers] (1 admin, the rest employees in IT) whose steps are plain `do` tasks. */
        fun tinyCampaign(
            testers: Int = 2,
            setup: String = "Sign up and create the company",
            step: String = "Look at the home page",
        ): String =
            """
            campaign:
              name: tiny
              testers: $testers
              seed: 7
              roles: {admin: 1, manager: 0, employee: ${testers - 1}}
              departments: [IT]
              budget: {max_steps_per_agent: 5, max_minutes: 2}
            setup:
              - id: signup
                actor: admin
                do: "$setup"
            steps:
              - id: look
                actor: employee[*]
                do: "$step"
            """.trimIndent()

        fun done(
            success: Boolean = true,
            summary: String = "finished",
        ): JsonObject =
            buildJsonObject {
                put("reason", "the task is complete")
                put("tool", "done")
                put("summary", summary)
                put("success", success)
            }
    }
}

/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.testing

import az.petek.app.cli.CliRuntime
import az.petek.app.cli.PetekCommand
import az.petek.app.config.IdentitySecretSource
import az.petek.app.di.AppContainer
import az.petek.app.di.AppOverrides
import az.petek.app.diagnostics.TargetReachability
import az.petek.app.logging.LoggingSettings
import az.petek.core.sqlite.SqliteDatabase
import az.petek.evidence.infrastructure.SqliteEvidenceStore
import az.petek.identity.infrastructure.SqliteIdentityRepository
import az.petek.llm.domain.LlmClient
import az.petek.orchestration.infrastructure.NoOpMonitorView
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
 * settings are recorded instead. The identity secret comes from the environment, never from the real `~/.petek`.
 */
class CliHarness(
    val workingDirectory: Path,
    environment: Map<String, String> = emptyMap(),
    var llm: LlmClient = scriptedLlm { done() },
    var browser: FakeBrowserEngine = FakeBrowserEngine(),
    /** The fake browser plays the site, so the target is never contacted unless a test says otherwise. */
    var reachability: TargetReachability = TargetReachability.ALWAYS,
) {
    val env: MutableMap<String, String> = (defaultEnvironment() + environment).toMutableMap()
    val loggingRequests = CopyOnWriteArrayList<LoggingSettings>()

    /** What `petek mcp` reads and writes its protocol on; empty input ends the server at once. */
    var standardInput: InputStream = ByteArrayInputStream(ByteArray(0))
    var standardOutput: OutputStream = ByteArrayOutputStream()

    val evidenceDir: Path get() = workingDirectory.resolve("evidence")
    val dbPath: Path get() = evidenceDir.resolve("petek.db")

    val runtime: CliRuntime
        get() =
            CliRuntime(
                environment = { env.toMap() },
                workingDirectory = workingDirectory,
                identitySecrets = IdentitySecretSource { error("tests must set PETEK_IDENTITY_SECRET instead of using ~/.petek") },
                containers = { config ->
                    AppContainer(config, AppOverrides(llm = llm, monitor = NoOpMonitorView, browser = browser, reachability = reachability))
                },
                configureLogging = { loggingRequests += it },
                observationWindow = Duration.ZERO,
                standardInput = standardInput,
                standardOutput = standardOutput,
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

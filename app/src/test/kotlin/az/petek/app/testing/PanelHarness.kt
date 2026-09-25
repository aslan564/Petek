/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.testing

import az.petek.app.config.PetekConfig
import az.petek.app.di.AppContainer
import az.petek.app.di.AppOverrides
import az.petek.app.panel.WebPanel
import az.petek.app.panel.explorer.RoleSessionSource
import az.petek.app.panel.explorer.SetupRuns
import az.petek.browser.domain.BrowserEngine
import az.petek.capacity.application.RecommendCapacityUseCase
import az.petek.capacity.domain.HostResources
import az.petek.core.security.Secret
import az.petek.dashboard.domain.PanelBudget
import az.petek.dashboard.domain.PanelInstructions
import az.petek.dashboard.domain.RegistrationSplit
import az.petek.dashboard.domain.RoleSplit
import az.petek.llm.domain.LlmRequest
import az.petek.llm.testing.ScriptedLlmClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * A [WebPanel] on a free port with production wiring except for the LLM ([llm]), the runs' browser ([runs], no
 * Chromium) and the explorer's browser ([site], a scripted site). Scenario files given in [scenarios] are written to
 * `scenarios/` before the panel starts (it imports them at start); [decorate] may change the rest of the wiring. Close it
 * after the test.
 */
internal class PanelHarness(
    val dir: Path,
    val site: FakeSiteEngine = FakeSiteEngine(URI("http://127.0.0.1:9")),
    val runs: BrowserEngine = FakeBrowserEngine(),
    val llm: PanelLlm = PanelLlm(),
    scenarios: Map<String, String> = emptyMap(),
    allowProduction: Boolean = false,
    testToken: String? = "dev-token",
    roleSessions: ((SetupRuns) -> RoleSessionSource)? = null,
    /** Changes the panel's overrides further, e.g. to hold its repositories at a gate. */
    decorate: (AppOverrides) -> AppOverrides = { it },
) : AutoCloseable {
    val config =
        PetekConfig(
            target = site.base,
            productionHosts = setOf("kadrohr.com"),
            allowProduction = allowProduction,
            testToken = testToken?.let(::Secret),
            mailpitUrl = URI("http://127.0.0.1:9"),
            identitySecret = Secret("panel-test-identity-secret-0123456789"),
            llmConcurrency = 4,
            evidenceDir = dir.resolve("evidence"),
        )

    init {
        if (scenarios.isNotEmpty()) Files.createDirectories(dir.resolve("scenarios"))
        scenarios.forEach { (name, text) -> Files.writeString(dir.resolve("scenarios").resolve(name), text) }
    }

    val panel: WebPanel =
        WebPanel.start(
            config = config,
            containers = {
                config,
                overrides,
                ->
                AppContainer(config, decorate(overrides.copy(llm = llm.client, browser = runs, explorerBrowser = site)))
            },
            workingDirectory = dir,
            capacityAdvice = RecommendCapacityUseCase({ HostResources(16L shl 30, 8L shl 30, 8) }),
            port = 0,
            roleSessions = roleSessions,
        )

    val backend get() = panel.backend

    override fun close() = panel.close()

    companion object {
        /** A valid instruction form for [target]. */
        fun instructions(
            target: String,
            text: String = "Giriş və qoşulma axınlarını yoxla",
            allowWrites: Boolean = false,
            maxPages: Int = 10,
        ) = PanelInstructions(
            target = target,
            instructions = text,
            testers = 6,
            roles = RoleSplit(1, 2, 3),
            departments = listOf("IT", "HR"),
            registration = RegistrationSplit(3, 2),
            budget = PanelBudget(maxMinutes = 5, maxStepsPerAgent = 20, maxPages = maxPages),
            allowWrites = allowWrites,
        )

        /** A campaign of [testers] whose steps are plain `do` tasks (1 admin, the rest employees in IT). */
        fun tinyCampaign(
            name: String = "tiny",
            testers: Int = 2,
            step: String = "Look at the home page",
        ): String =
            """
            campaign:
              name: $name
              testers: $testers
              seed: 7
              roles: {admin: 1, manager: 0, employee: ${testers - 1}}
              departments: [IT]
              budget: {max_steps_per_agent: 5, max_minutes: 2}
            setup:
              - id: signup
                actor: admin
                do: "Sign up and create the company"
            steps:
              - id: look
                actor: employee[*]
                do: "$step"
            """.trimIndent() + "\n"
    }
}

/**
 * The panel's scripted LLM: the explorer's page analyses name every submit button of the page as an action (and ask
 * [question] on `/join`), agents answer `done` (failing in [failingSteps]), triage answers [verdict]. Each kind of
 * request can be held at a gate, so a test can look at (or stop) work in the middle.
 */
internal class PanelLlm(
    var question: String? = "Şirkət kodu haradan alınır?",
    var failingSteps: Set<String> = emptySet(),
    var verdict: String = "SCENARIO_BUG",
) {
    /** Holds every page analysis after the first [explorerPass] ones until it completes. */
    @Volatile
    var explorerGate: CompletableDeferred<Unit>? = null

    @Volatile
    var explorerPass: Int = 0

    private val explorerCalls =
        java.util.concurrent.atomic
            .AtomicInteger()

    @Volatile
    var agentGate: CompletableDeferred<Unit>? = null

    private val button = Regex("""\[(\d+)] button "([^"]*)" \(testid=([a-z-]+)\)""")

    val client: ScriptedLlmClient = ScriptedLlmClient { request -> answer(request) }

    val explorerPrompts: List<String> get() =
        client.requests
            .filter {
                it.label.startsWith(
                    "explorer/",
                )
            }.map { it.messages.single().content }

    private suspend fun answer(request: LlmRequest): JsonObject =
        when {
            request.label.startsWith("explorer/") -> {
                if (explorerCalls.incrementAndGet() > explorerPass) explorerGate?.await()
                page(request.messages.single().content)
            }

            request.label.startsWith("triage/") -> {
                triage()
            }

            else -> {
                agentGate?.await()
                CliHarness.done(success = request.label.substringAfter('/') !in failingSteps)
            }
        }

    private fun page(prompt: String): JsonObject {
        val url =
            Regex("URL: (\\S+)")
                .find(prompt)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        return buildJsonObject {
            put("purpose", "Səhifə $url")
            putJsonArray("actions") {
                button.findAll(prompt).forEach { match ->
                    addJsonObject {
                        put("ref", match.groupValues[1].toInt())
                        put("name", match.groupValues[2])
                        put("kind", kindOf(match.groupValues[3]))
                    }
                }
            }
            putJsonArray("unknowns") {
                val asked = question
                if (asked != null && url.endsWith("/join")) {
                    addJsonObject {
                        put("question", asked)
                        put("context", "Qoşulma formu şirkət kodu istəyir")
                    }
                }
            }
        }
    }

    private fun kindOf(testId: String): String =
        when {
            testId.startsWith("login") -> "LOGIN"
            testId.startsWith("join") || testId.startsWith("register") -> "REGISTER"
            else -> "CREATE"
        }

    private fun triage(): JsonObject =
        buildJsonObject {
            put("category", verdict)
            put("rationale", "Ssenari addımı səhv yazılıb")
            put("confidence", 0.8)
            putJsonArray("evidence_refs") {}
            if (verdict != "SYSTEM_BUG") {
                putJsonObject("proposed_change") {
                    put("summary", "Addımın mətnini dəqiqləşdir")
                    putJsonArray("edits") {
                        addJsonObject {
                            put("find", "Look at the home page")
                            put("replace", "Open the home page and read the title")
                        }
                    }
                }
            }
        }
}

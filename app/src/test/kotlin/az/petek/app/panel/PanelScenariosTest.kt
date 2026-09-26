/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.app.panel

import az.petek.app.testing.PanelHarness
import az.petek.app.testing.PanelHarness.Companion.tinyCampaign
import az.petek.app.testing.PanelWaits
import az.petek.app.testing.PanelWaits.exploration
import az.petek.app.testing.PanelWaits.explored
import az.petek.dashboard.domain.DiffLineKind
import az.petek.dashboard.domain.PanelConflictException
import az.petek.dashboard.domain.PanelNotFoundException
import az.petek.dashboard.domain.ScenarioSource
import az.petek.dashboard.domain.ScenarioStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The "Ssenarilər" screen's backend over the real catalog, the owner's files and the explorer's drafts. */
class PanelScenariosTest {
    @TempDir
    lateinit var dir: Path

    private val open = mutableListOf<PanelHarness>()

    @AfterEach
    fun close() = open.forEach { it.close() }

    private fun harness(scenarios: Map<String, String> = emptyMap()): PanelHarness =
        PanelHarness(dir, site = PanelWaits.site(), scenarios = scenarios).also { open += it }

    private fun restart(scenarios: Map<String, String> = emptyMap()): PanelHarness {
        open.removeAt(open.lastIndex).close()
        return harness(scenarios)
    }

    @Test
    fun `the owner's files are imported once, a new scenario approved and a changed file drafted for review`() =
        runBlocking<Unit> {
            val first = harness(mapOf("tiny.yaml" to tinyCampaign(), "broken.yaml" to "campaign: [not valid"))

            val imported = first.backend.scenarios().single()
            imported.name shouldBe "tiny"
            imported.version shouldBe 1
            imported.status shouldBe ScenarioStatus.APPROVED
            imported.source shouldBe ScenarioSource.USER
            imported.note shouldBe "scenarios/tiny.yaml faylından"
            imported.runnable shouldBe true

            restart()
                .backend
                .scenarios()
                .single()
                .id shouldBe imported.id

            val changed = restart(mapOf("tiny.yaml" to tinyCampaign(step = "Open the home page")))
            val versions = changed.backend.scenarios()
            versions.map { it.version to it.status } shouldContainExactly listOf(2 to ScenarioStatus.DRAFT, 1 to ScenarioStatus.APPROVED)
            versions.first().parentId shouldBe imported.id
        }

    @Test
    fun `a version shows its text, its diff with the parent, its plan, and moves from draft to approved to frozen`() =
        runBlocking<Unit> {
            harness(mapOf("tiny.yaml" to tinyCampaign())).backend.scenarios()
            val panel = restart(mapOf("tiny.yaml" to tinyCampaign(step = "Open the home page")))
            val versions = panel.backend.scenarios()
            // Chosen by status, and the whole catalog named on a mismatch: CI once saw the first version still a draft.
            withClue("catalog after the restart: ${versions.map { "${it.name} v${it.version} ${it.status}" }}") {
                versions.map { it.version to it.status } shouldContainExactly
                    listOf(2 to ScenarioStatus.DRAFT, 1 to ScenarioStatus.APPROVED)
            }
            val draft = versions.single { it.status == ScenarioStatus.DRAFT }
            val approved = versions.single { it.status == ScenarioStatus.APPROVED }

            panel.backend
                .scenario(draft.id)
                .shouldNotBeNull()
                .yaml shouldContain "Open the home page"
            val diff = panel.backend.diff(approved.id, draft.id)
            diff.lines.first().kind shouldBe DiffLineKind.HUNK
            diff.lines.single { it.kind == DiffLineKind.REMOVED }.text shouldContain "Look at the home page"
            diff.lines.single { it.kind == DiffLineKind.ADDED }.text shouldContain "Open the home page"
            diff.added shouldBe 1

            val plan = panel.backend.runPlan(approved.id).shouldNotBeNull()
            plan.runId.shouldBeNull()
            plan.steps.map { it.id to it.agentIds.map { agent -> agent.value } } shouldContainExactly
                listOf("signup" to listOf("a01"), "look" to listOf("a02"))

            shouldThrow<PanelConflictException> { panel.backend.freeze(draft.id) }.message shouldContain "qaralamadır"
            panel.backend.approve(draft.id).status shouldBe ScenarioStatus.APPROVED
            panel.backend
                .scenarios()
                .single { it.id == approved.id }
                .status shouldBe ScenarioStatus.SUPERSEDED
            shouldThrow<PanelConflictException> { panel.backend.approve(approved.id) }.message shouldContain "köhnəlib"
            panel.backend.freeze(draft.id).status shouldBe ScenarioStatus.FROZEN
            panel.backend
                .freeze(draft.id)
                .frozenAt
                .shouldNotBeNull()
            panel.backend.approve(draft.id).status shouldBe ScenarioStatus.FROZEN
        }

    @Test
    fun `unknown versions are not found`() =
        runBlocking<Unit> {
            val panel = harness(mapOf("tiny.yaml" to tinyCampaign()))
            val known =
                panel.backend
                    .scenarios()
                    .single()
                    .id

            panel.backend.scenario("scn_unknown").shouldBeNull()
            panel.backend.runPlan("scn_unknown").shouldBeNull()
            shouldThrow<PanelNotFoundException> { panel.backend.diff("scn_unknown", known) }
            shouldThrow<PanelNotFoundException> { panel.backend.approve("scn_unknown") }
            shouldThrow<PanelNotFoundException> { panel.backend.freeze("scn_unknown") }
        }

    @Test
    fun `a draft is generated from the latest exploration as an explorer version, once per text`() =
        runBlocking<Unit> {
            val panel = harness()
            shouldThrow<PanelConflictException> { panel.backend.generateScenario() }.message shouldStartWith "Əvvəlcə saytı kəşf edin"
            val explored = panel.explored(PanelHarness.instructions(panel.site.base.toString()).copy(departments = listOf("Satış", "IT")))

            val generated = panel.backend.generateScenario()

            generated.yaml shouldBe explored.draftYaml
            generated.yaml shouldContain "departments: [\"Satış\", \"IT\"]"

            generated.version.status shouldBe ScenarioStatus.DRAFT
            generated.version.source shouldBe ScenarioSource.EXPLORER
            generated.version.name shouldStartWith "explorer-"
            generated.version.note shouldContain "Kəşfiyyatçı: http://127.0.0.1:9"
            generated.yaml shouldContain "register_owner"
            val shown = panel.exploration { it.activity.any { line -> line.kind == "DRAFT_READY" } }
            shown.id shouldBe explored.id
            shown.draftYaml shouldBe generated.yaml
            panel.backend
                .generateScenario()
                .version.id shouldBe generated.version.id
            panel.backend.approve(generated.version.id).runnable shouldBe true
            panel.backend
                .scenarios()
                .single()
                .status shouldBe ScenarioStatus.APPROVED
        }

    @Test
    fun `a draft is not generated while the exploration is still going`() =
        runBlocking<Unit> {
            val panel = harness()
            panel.llm.explorerGate = kotlinx.coroutines.CompletableDeferred()
            panel.backend.startExploration(PanelHarness.instructions(panel.site.base.toString()))

            shouldThrow<PanelConflictException> { panel.backend.generateScenario() }.message shouldContain "hələ gedir"
            panel.backend.cancelExploration() shouldBe true
        }

    @Test
    fun `the project's own KadroHR campaign is imported, approved and planned for its 30 testers`() =
        runBlocking<Unit> {
            val campaign = Files.readString(Path.of("..", "scenarios", "kadrohr.yaml"))
            val panel = harness(mapOf("kadrohr.yaml" to campaign))

            val version = panel.backend.scenarios().single()

            version.name shouldBe "kadrohr-real"
            version.status shouldBe ScenarioStatus.APPROVED
            panel.backend
                .scenario(version.id)
                .shouldNotBeNull()
                .yaml shouldBe campaign
            val plan = panel.backend.runPlan(version.id).shouldNotBeNull()
            plan.steps
                .single { it.id == "read_announce" }
                .agentIds.size shouldBe 24
            plan.steps.single { it.id == "leave_race" }.parallel shouldBe true
        }

    @Test
    fun `an owner's file that fails validation is skipped and the rest is imported`() =
        runBlocking<Unit> {
            Files.createDirectories(dir.resolve("scenarios"))
            val panel = harness(mapOf("a.yaml" to "not: a campaign", "b.yml" to tinyCampaign(name = "bee")))

            panel.backend.scenarios().map { it.name } shouldContainExactly listOf("bee")
        }
}

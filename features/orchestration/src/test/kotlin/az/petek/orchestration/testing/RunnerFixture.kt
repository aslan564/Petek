/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.orchestration.testing

import az.petek.browser.domain.BrowserEngineConfig
import az.petek.core.ids.AgentId
import az.petek.core.testing.SequentialIdGenerator
import az.petek.core.time.HarnessClock
import az.petek.evidence.domain.StepKind
import az.petek.evidence.domain.StepRecord
import az.petek.evidence.testing.InMemoryArtifactStore
import az.petek.evidence.testing.InMemoryEvidence
import az.petek.identity.testing.InMemoryIdentityRepository
import az.petek.oracle.testing.FakeTargetOracle
import az.petek.orchestration.application.DefaultCampaignRunner
import az.petek.orchestration.application.InProcessEventBus
import az.petek.orchestration.application.InactivityWatchdog
import az.petek.orchestration.application.ProgressTrackingRecorder
import az.petek.orchestration.application.RunnerSettings
import az.petek.orchestration.domain.DefaultActorResolver
import az.petek.orchestration.domain.EventBus
import az.petek.orchestration.domain.MonitorView
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** Every port of [DefaultCampaignRunner] backed by a fake, plus helpers to read what the run recorded. */
class RunnerFixture(
    val clock: HarnessClock,
    val oracle: FakeTargetOracle = FakeTargetOracle(),
) {
    val ids = SequentialIdGenerator()
    val evidence = InMemoryEvidence()
    val artifacts = InMemoryArtifactStore()
    val identities = InMemoryIdentityRepository()
    val generator = SimpleIdentityGenerator()
    val browser = FakeBrowserEngine(clock)
    val agents = ScriptedAgentFactory()
    val renderer = SimpleTemplateRenderer()
    val verify = FakeVerify(evidence, clock, renderer)
    val monitor = RecordingMonitor()
    val finalizer = CountingFinalizer()
    val watchdog = InactivityWatchdog()

    /** What agents should record through, so their evidence counts as progress for the watchdog. */
    val agentRecorder = ProgressTrackingRecorder(evidence, watchdog::progress)
    val sharedStates = CopyOnWriteArrayList<TestSharedRunState>()
    val buses = CopyOnWriteArrayList<EventBus>()
    var busFactory: () -> EventBus = { InProcessEventBus(clock, ids) }

    fun runner(
        finalizer: CountingFinalizer = this.finalizer,
        monitor: MonitorView = this.monitor,
    ): DefaultCampaignRunner =
        DefaultCampaignRunner(
            identityGenerator = generator,
            identities = identities,
            runs = evidence,
            recorder = evidence,
            artifacts = artifacts,
            browser = browser,
            browserConfig = BrowserEngineConfig(),
            agents = agents,
            verify = verify,
            oracle = oracle,
            fields = DottedFieldSelector(),
            renderer = renderer,
            actors = DefaultActorResolver(),
            monitor = monitor,
            finalizer = finalizer,
            clock = clock,
            ids = ids,
            settings = RunnerSettings(mailDomain = "test.example.test", storageRoot = Path.of("build", "storage")),
            sharedStateFactory = { TestSharedRunState().also(sharedStates::add) },
            watchdog = watchdog,
            busFactory = { busFactory().also(buses::add) },
        )

    fun steps(scenarioStep: String): List<StepRecord> = evidence.stepList.filter { it.scenarioStep == scenarioStep }

    fun steps(
        scenarioStep: String,
        kind: StepKind,
    ): List<StepRecord> = steps(scenarioStep).filter { it.kind == kind }

    fun step(
        scenarioStep: String,
        kind: StepKind,
        agent: String,
    ): StepRecord = steps(scenarioStep, kind).single { it.agentId == AgentId(agent) }

    fun system(action: String): List<StepRecord> = evidence.stepList.filter { it.kind == StepKind.SYSTEM && it.action == action }
}

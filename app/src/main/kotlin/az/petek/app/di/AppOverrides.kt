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

package az.petek.app.di

import az.petek.app.diagnostics.TargetReachability
import az.petek.browser.domain.BrowserEngine
import az.petek.core.sqlite.SqliteDatabase
import az.petek.core.time.HarnessClock
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.RunRepository
import az.petek.identity.domain.IdentityRepository
import az.petek.llm.domain.LlmClient
import az.petek.orchestration.domain.MonitorView
import az.petek.ownership.application.SiteOwnership

/**
 * Replacements for the parts of [AppContainer] that tests cannot use for real: a scripted LLM instead of the
 * provider, a quiet monitor, a controllable clock, a browser engine that does not launch Chromium. Everything
 * else stays production wiring, so a test with overrides still exercises the real object graph.
 *
 * An overriding [llm] replaces only the provider: it is still wrapped in the metering, retry and concurrency
 * decorators. Objects passed here belong to the caller; the container never closes them.
 */
data class AppOverrides(
    val llm: LlmClient? = null,
    val monitor: MonitorView? = null,
    val clock: HarnessClock? = null,
    val browser: BrowserEngine? = null,
    /** The explorer's own browser engine; by default [browser] when given, else a separate Playwright engine. */
    val explorerBrowser: BrowserEngine? = null,
    /** The look at the target before a run or an exploration; tests with a fake browser use [TargetReachability.ALWAYS]. */
    val reachability: TargetReachability? = null,
    /** Whether Pətək may write to a site; tests use the fakes of `ownership`'s test fixtures instead of the network. */
    val ownership: SiteOwnership? = null,
    /**
     * A database opened by the caller, shared instead of opening `PETEK_DB` again: the web panel's containers for runs
     * against another site use the panel's database, so every run, exploration and scenario stays in one place.
     */
    val database: SqliteDatabase? = null,
    /** Wraps the evidence recorder (outermost), e.g. so the web panel sees every step as it is recorded. */
    val recorderDecorator: ((EvidenceRecorder) -> EvidenceRecorder)? = null,
    /** Wraps the run repository, e.g. so the web panel learns when a run starts and ends. */
    val runsDecorator: ((RunRepository) -> RunRepository)? = null,
    /** Wraps the identity repository, e.g. so the web panel knows the testers of a run. */
    val identitiesDecorator: ((IdentityRepository) -> IdentityRepository)? = null,
)

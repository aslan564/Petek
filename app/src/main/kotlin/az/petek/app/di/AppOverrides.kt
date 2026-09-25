package az.petek.app.di

import az.petek.browser.domain.BrowserEngine
import az.petek.core.sqlite.SqliteDatabase
import az.petek.core.time.HarnessClock
import az.petek.evidence.domain.EvidenceRecorder
import az.petek.evidence.domain.RunRepository
import az.petek.identity.domain.IdentityRepository
import az.petek.llm.domain.LlmClient
import az.petek.orchestration.domain.MonitorView

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

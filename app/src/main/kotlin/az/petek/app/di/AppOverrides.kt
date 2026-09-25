package az.petek.app.di

import az.petek.browser.domain.BrowserEngine
import az.petek.core.time.HarnessClock
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
)

package az.petek.browser.domain

import az.petek.browser.testing.FakeBrowserSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration

class BrowserEngineConfigTest {
    @Test
    fun `twenty contexts share one browser by default`() {
        BrowserEngineConfig().contextsPerBrowser shouldBe 20
        BrowserEngineConfig().topology shouldBe BrowserTopology.SHARED_SERVER
    }

    @Test
    fun `every browser must host at least one context`() {
        BrowserEngineConfig(contextsPerBrowser = 1).contextsPerBrowser shouldBe 1
        shouldThrow<IllegalArgumentException> { BrowserEngineConfig(contextsPerBrowser = 0) }.message shouldContain
            "contextsPerBrowser must be at least 1, was 0"
    }

    @Test
    fun `a session that cannot see dialogs reports none`() =
        runBlocking<Unit> {
            val minimal: BrowserSession = MinimalSession(FakeBrowserSession())

            minimal.drainDialogs().shouldBeEmpty()
        }

    /** Implements only the members without a default, like an adapter written before dialogs existed. */
    private class MinimalSession(
        private val delegate: BrowserSession,
    ) : BrowserSession {
        override val label: String get() = delegate.label

        override suspend fun navigate(pathOrUrl: String) = delegate.navigate(pathOrUrl)

        override suspend fun snapshot(): PageSnapshot = delegate.snapshot()

        override suspend fun click(ref: Int) = delegate.click(ref)

        override suspend fun fill(
            ref: Int,
            text: String,
            submit: Boolean,
        ) = delegate.fill(ref, text, submit)

        override suspend fun select(
            ref: Int,
            option: String,
        ) = delegate.select(ref, option)

        override suspend fun clickSelector(selector: String) = delegate.clickSelector(selector)

        override suspend fun fillSelector(
            selector: String,
            text: String,
        ) = delegate.fillSelector(selector, text)

        override suspend fun selectSelector(
            selector: String,
            option: String,
        ) = delegate.selectSelector(selector, option)

        override suspend fun readText(selector: String): String? = delegate.readText(selector)

        override suspend fun readAttribute(
            selector: String,
            attribute: String,
        ): String? = delegate.readAttribute(selector, attribute)

        override suspend fun waitForText(
            text: String,
            timeout: Duration,
        ): WaitOutcome = delegate.waitForText(text, timeout)

        override suspend fun waitForSelector(
            selector: String,
            timeout: Duration,
        ): WaitOutcome = delegate.waitForSelector(selector, timeout)

        override suspend fun isTextVisible(text: String): Boolean = delegate.isTextVisible(text)

        override suspend fun isSelectorVisible(selector: String): Boolean = delegate.isSelectorVisible(selector)

        override suspend fun count(selector: String): Int = delegate.count(selector)

        override suspend fun currentUrl(): String = delegate.currentUrl()

        override suspend fun screenshot(): ByteArray = delegate.screenshot()

        override suspend fun accessibilitySnapshot(): String = delegate.accessibilitySnapshot()

        override suspend fun domSnapshot(): String = delegate.domSnapshot()

        override suspend fun saveStorageState(path: Path) = delegate.saveStorageState(path)

        override suspend fun request(
            method: String,
            path: String,
            body: String?,
        ): HttpProbeResult = delegate.request(method, path, body)

        override suspend fun networkObservation(): NetworkObservation = delegate.networkObservation()

        override suspend fun close() = delegate.close()
    }
}

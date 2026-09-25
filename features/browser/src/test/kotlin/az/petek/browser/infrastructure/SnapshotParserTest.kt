package az.petek.browser.infrastructure

import az.petek.browser.domain.BrowserActionException
import az.petek.browser.domain.PageElement
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SnapshotParserTest {
    @Test
    fun `the indexer result becomes a page snapshot`() {
        val raw =
            mapOf(
                "url" to "http://t/form",
                "title" to "Forma",
                "visibleText" to "Qeydiyyat",
                "elements" to
                    listOf(
                        mapOf(
                            "ref" to 1,
                            "role" to "textbox",
                            "name" to "Ad",
                            "tag" to "input",
                            "testId" to "name-input",
                            "value" to "Aysel",
                            "enabled" to true,
                        ),
                        mapOf("ref" to 2.0, "role" to "button", "name" to "Göndər", "tag" to "button", "enabled" to false),
                    ),
            )

        val snapshot = SnapshotParser.parse(raw)

        snapshot.url shouldBe "http://t/form"
        snapshot.title shouldBe "Forma"
        snapshot.visibleText shouldBe "Qeydiyyat"
        snapshot.elements shouldContainExactly
            listOf(
                PageElement(1, "textbox", "Ad", "input", "name-input", "Aysel", enabled = true),
                PageElement(2, "button", "Göndər", "button", testId = null, value = null, enabled = false),
            )
    }

    @Test
    fun `elements without a ref are dropped and missing fields get neutral defaults`() {
        val snapshot = SnapshotParser.parse(mapOf("elements" to listOf(mapOf("name" to "x"), mapOf("ref" to 3), "junk")))

        snapshot.url shouldBe ""
        snapshot.elements shouldContainExactly listOf(PageElement(3, "", "", "", null, null, enabled = true))
    }

    @Test
    fun `a missing result is a browser action failure`() {
        shouldThrow<BrowserActionException> { SnapshotParser.parse(null) }
    }

    @Test
    fun `the indexer receives the snapshot limits`() {
        SnapshotLimits().asScriptArgument() shouldBe
            mapOf("maxElements" to 1000, "maxNameChars" to 80, "maxValueChars" to 200, "maxTextChars" to 6000)
    }
}

package az.petek.browser.infrastructure

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OutputTailTest {
    @Test
    fun `only the last lines are kept`() {
        val tail = OutputTail(maxLines = 2)

        listOf("one", "two", "three").forEach(tail::add)

        tail.toString() shouldBe "two\nthree"
    }

    @Test
    fun `progress bar redraws keep only their final state and blank lines are skipped`() {
        val tail = OutputTail()

        tail.add("10%\r50%\r100%")
        tail.add("   ")

        tail.toString() shouldBe "100%"
    }

    @Test
    fun `an empty tail says so`() {
        OutputTail().toString() shouldBe "(no output)"
    }
}

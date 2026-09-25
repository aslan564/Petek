package az.petek.campaign.infrastructure

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ActorFlowListsTest {
    @Test
    fun `bracketed actor items are quoted in place`() {
        quoteActorFlowList("    actor: [manager[IT], manager[HR]]") shouldBe """    actor: ["manager[IT]", "manager[HR]"]"""
    }

    @Test
    fun `a list item key and a trailing comment are kept`() {
        quoteActorFlowList("  - actor: [employee[dept=IT, n=1], admin]   # race") shouldBe
            """  - actor: ["employee[dept=IT, n=1]", "admin"]   # race"""
    }

    @Test
    fun `lists without brackets are already valid and stay untouched`() {
        quoteActorFlowList("    actor: [admin, employee]") shouldBe "    actor: [admin, employee]"
    }

    @Test
    fun `lists the author already quoted stay untouched`() {
        quoteActorFlowList("""    actor: ["manager[IT]", manager[HR]]""") shouldBe """    actor: ["manager[IT]", manager[HR]]"""
    }

    @Test
    fun `other keys and unbalanced or trailing text stay untouched`() {
        quoteActorFlowList("    departments: [IT[x]]") shouldBe "    departments: [IT[x]]"
        quoteActorFlowList("    actor: [manager[IT]") shouldBe "    actor: [manager[IT]"
        quoteActorFlowList("    actor: [manager[IT]] extra") shouldBe "    actor: [manager[IT]] extra"
        quoteActorFlowList("    actor: [manager[IT], ]") shouldBe "    actor: [manager[IT], ]"
        quoteActorFlowList("    actor: manager[IT]") shouldBe "    actor: manager[IT]"
    }

    @Test
    fun `backslashes are escaped inside the quotes`() {
        quoteActorFlowList("""actor: [a\b[x]]""") shouldBe """actor: ["a\\b[x]"]"""
    }

    @Test
    fun `the number of lines never changes`() {
        val text = "steps:\r\n  - actor: [manager[IT], manager[HR]]\r\n    do: x\n"
        val quoted = quoteActorFlowLists(text)
        quoted.lines().size shouldBe text.lines().size
        quoted shouldBe "steps:\r\n  - actor: [\"manager[IT]\", \"manager[HR]\"]\r\n    do: x\n"
    }
}

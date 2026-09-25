package az.petek.scenarios.domain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class YamlEditsTest {
    private val yaml = "steps:\n  - id: a\n    do: \"Elan yarat\"\n  - id: b\n    do: \"Elanı oxu\"\n"

    private fun failure(result: YamlEdits.Result): String = result.shouldBeInstanceOf<YamlEdits.Result.Failed>().reason

    @Test
    fun `a single edit replaces its text and leaves everything else byte for byte`() {
        val result = YamlEdits.apply(yaml, listOf(YamlEdit("do: \"Elanı oxu\"", "do: \"Bildirişi aç və elanı oxu\"")))

        result shouldBe YamlEdits.Result.Applied(yaml.replace("do: \"Elanı oxu\"", "do: \"Bildirişi aç və elanı oxu\""))
    }

    @Test
    fun `edits apply in order, each to the result of the previous one`() {
        val result =
            YamlEdits.apply(
                yaml,
                listOf(YamlEdit("id: a", "id: announce"), YamlEdit("id: announce\n", "id: announce\n    parallel: false\n")),
            )

        result.shouldBeInstanceOf<YamlEdits.Result.Applied>().yaml shouldContain "id: announce\n    parallel: false\n"
    }

    @Test
    fun `a text that does not occur is refused with an excerpt`() {
        failure(YamlEdits.apply(yaml, listOf(YamlEdit("do: \"Ticket yaz\"", "x")))) shouldContain "does not occur"
    }

    @Test
    fun `a text that occurs more than once is refused as ambiguous`() {
        failure(YamlEdits.apply(yaml, listOf(YamlEdit("  - id:", "  - name:")))) shouldContain "more than once"
    }

    @Test
    fun `an empty find, a no-op edit and no edits at all are refused`() {
        failure(YamlEdits.apply(yaml, listOf(YamlEdit("", "x")))) shouldContain "empty"
        failure(YamlEdits.apply(yaml, listOf(YamlEdit("id: a", "id: a")))) shouldContain "itself"
        failure(YamlEdits.apply(yaml, emptyList())) shouldContain "no edits"
    }

    @Test
    fun `edits that together change nothing are refused`() {
        val result = YamlEdits.apply(yaml, listOf(YamlEdit("id: a", "id: z"), YamlEdit("id: z", "id: a")))

        failure(result) shouldContain "do not change"
    }

    @Test
    fun `too many edits are refused`() {
        val edits = (1..YamlEdits.MAX_EDITS + 1).map { YamlEdit("x$it", "y$it") }

        failure(YamlEdits.apply(yaml, edits)) shouldContain "at most ${YamlEdits.MAX_EDITS}"
    }

    @Test
    fun `line breaks of an edit follow a CRLF file`() {
        val crlf = yaml.replace("\n", "\r\n")

        val result = YamlEdits.apply(crlf, listOf(YamlEdit("id: a\n    do: \"Elan yarat\"", "id: a\n    do: \"Elan paylaş\"")))

        result shouldBe YamlEdits.Result.Applied(crlf.replace("Elan yarat", "Elan paylaş"))
    }

    @Test
    fun `the failing edit is named by its position`() {
        val result = YamlEdits.apply(yaml, listOf(YamlEdit("id: a", "id: z"), YamlEdit("missing", "x")))

        failure(result) shouldContain "edit 2"
    }
}

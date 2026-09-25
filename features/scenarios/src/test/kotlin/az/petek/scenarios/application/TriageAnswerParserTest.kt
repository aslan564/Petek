package az.petek.scenarios.application

import az.petek.scenarios.domain.TriageCategory
import az.petek.scenarios.domain.YamlEdit
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class TriageAnswerParserTest {
    private fun parse(json: String): ParsedAnswer = TriageAnswerParser.parse(Json.parseToJsonElement(json).jsonObject)

    private fun valid(json: String): TriageAnswer = parse(json).shouldBeInstanceOf<ParsedAnswer.Valid>().answer

    private fun invalid(json: String): String = parse(json).shouldBeInstanceOf<ParsedAnswer.Invalid>().reason

    @Test
    fun `a complete answer is read into a verdict with its proposal`() {
        val answer =
            valid(
                """
                {"category": "SCENARIO_BUG", "rationale": " Addım mətni qeyri-müəyyəndir. ", "confidence": 0.8,
                 "evidence_refs": ["stp_1", "[art_2]"],
                 "proposed_change": {"summary": "Mətni dəqiqləşdir", "edits": [{"find": "a", "replace": "b"}]}}
                """,
            )

        answer shouldBe
            TriageAnswer(
                category = TriageCategory.SCENARIO_BUG,
                rationale = "Addım mətni qeyri-müəyyəndir.",
                confidence = 0.8,
                evidenceRefs = listOf("stp_1", "[art_2]"),
                proposal = ProposalAnswer.Edits("Mətni dəqiqləşdir", listOf(YamlEdit("a", "b"))),
            )
    }

    @Test
    fun `the category is read case-insensitively and the proposal is optional`() {
        val answer = valid("""{"category": " system_bug ", "rationale": "x", "confidence": 1, "evidence_refs": []}""")

        answer.category shouldBe TriageCategory.SYSTEM_BUG
        answer.confidence shouldBe 1.0
        answer.proposal shouldBe null
        valid("""{"category": "MODEL_GAP", "rationale": "x", "confidence": 0, "evidence_refs": [], "proposed_change": null}""")
            .proposal shouldBe null
    }

    @Test
    fun `missing evidence references mean none`() {
        valid("""{"category": "MODEL_GAP", "rationale": "x", "confidence": 0.5}""").evidenceRefs shouldBe emptyList()
    }

    @Test
    fun `an unknown or missing category makes the answer invalid`() {
        invalid("""{"category": "NETWORK", "rationale": "x", "confidence": 0.5}""") shouldContain "'category' must be one of"
        invalid("""{"rationale": "x", "confidence": 0.5}""") shouldContain "missing"
        invalid("""{"category": 3, "rationale": "x", "confidence": 0.5}""") shouldContain "category"
    }

    @Test
    fun `a blank rationale makes the answer invalid`() {
        invalid("""{"category": "MODEL_GAP", "rationale": "  ", "confidence": 0.5}""") shouldContain "rationale"
        invalid("""{"category": "MODEL_GAP", "rationale": 7, "confidence": 0.5}""") shouldContain "rationale"
    }

    @Test
    fun `a confidence outside zero to one or given as text makes the answer invalid`() {
        invalid("""{"category": "MODEL_GAP", "rationale": "x", "confidence": 1.5}""") shouldContain "from 0 to 1, was 1.5"
        invalid("""{"category": "MODEL_GAP", "rationale": "x", "confidence": -0.1}""") shouldContain "confidence"
        invalid("""{"category": "MODEL_GAP", "rationale": "x", "confidence": "0.5"}""") shouldContain "confidence"
        invalid("""{"category": "MODEL_GAP", "rationale": "x"}""") shouldContain "missing"
    }

    @Test
    fun `evidence references must be strings`() {
        invalid("""{"category": "MODEL_GAP", "rationale": "x", "confidence": 0.5, "evidence_refs": [1]}""") shouldContain "strings"
        invalid("""{"category": "MODEL_GAP", "rationale": "x", "confidence": 0.5, "evidence_refs": "stp_1"}""") shouldContain "array"
    }

    @Test
    fun `a malformed proposal keeps the verdict valid and is reported on its own`() {
        fun proposal(change: String) =
            valid("""{"category": "MODEL_GAP", "rationale": "x", "confidence": 0.5, "proposed_change": $change}""").proposal

        proposal("\"edit the selector\"").shouldBeInstanceOf<ProposalAnswer.Malformed>().reason shouldContain "object"
        proposal("""{"summary": "s"}""").shouldBeInstanceOf<ProposalAnswer.Malformed>().reason shouldContain "edits"
        proposal("""{"summary": "s", "edits": ["x"]}""").shouldBeInstanceOf<ProposalAnswer.Malformed>().reason shouldContain
            "edit 1 must be an object"
        proposal("""{"summary": "s", "edits": [{"find": "a"}]}""").shouldBeInstanceOf<ProposalAnswer.Malformed>().reason shouldContain
            "'replace'"
        proposal("""{"summary": 5, "edits": []}""").shouldBeInstanceOf<ProposalAnswer.Malformed>().reason shouldContain "summary"
        proposal("""{"edits": [{"find": "a", "replace": ""}]}""") shouldBe ProposalAnswer.Edits("", listOf(YamlEdit("a", "")))
    }

    @Test
    fun `an overlong rationale is clipped`() {
        val long = "ə".repeat(5_000)

        val answer = valid("""{"category": "MODEL_GAP", "rationale": "$long", "confidence": 0.5}""")

        answer.rationale.length shouldBe TriageAnswerParser.MAX_RATIONALE
        answer.rationale.endsWith("…") shouldBe true
    }

    @Test
    fun `unknown fields are ignored`() {
        valid("""{"category": "MODEL_GAP", "rationale": "x", "confidence": 0.5, "severity": "high"}""").category shouldBe
            TriageCategory.MODEL_GAP
    }
}

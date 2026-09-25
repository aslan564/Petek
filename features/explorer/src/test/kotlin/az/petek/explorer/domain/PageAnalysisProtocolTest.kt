package az.petek.explorer.domain

import az.petek.browser.domain.PageElement
import az.petek.browser.domain.PageSnapshot
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class PageAnalysisProtocolTest {
    private val snapshot =
        PageSnapshot(
            url = "/tickets",
            title = "Müraciətlər",
            elements =
                listOf(
                    PageElement(1, "link", "Elanlar", "a", "nav-announcements", null, true),
                    PageElement(2, "textbox", "Başlıq", "input", "ticket-title", "", true),
                    PageElement(3, "button", "Göndər", "button", "ticket-submit", null, true),
                ),
            visibleText = "Müraciətlər",
        )

    private fun parse(
        json: String,
        maxActions: Int = 10,
        maxUnknowns: Int = 3,
    ) = PageAnalysisProtocol.parse(Json.parseToJsonElement(json).jsonObject, snapshot, maxActions, maxUnknowns)

    @Test
    fun `a valid answer is kept with trimmed texts`() {
        val analysis =
            parse(
                """
                {"purpose": "  Müraciət   yaratmaq və siyahı ", "actions": [{"ref": 3, "name": "Göndər", "kind": "CREATE"}],
                 "unknowns": [{"question": "Kim təsdiqləyə bilər?", "context": "Təsdiq düyməsi görünmür"}]}
                """.trimIndent(),
            )

        analysis.purpose shouldBe "Müraciət yaratmaq və siyahı"
        analysis.actions shouldContainExactly listOf(ProposedAction(3, "Göndər", ActionKind.CREATE))
        analysis.unknowns shouldContainExactly listOf(ProposedUnknown("Kim təsdiqləyə bilər?", "Təsdiq düyməsi görünmür"))
        analysis.rejected.shouldBeEmpty()
    }

    @Test
    fun `invented elements, unknown kinds, duplicates and missing names are dropped with reasons`() {
        val analysis =
            parse(
                """
                {"purpose": "x", "actions": [
                  {"ref": 99, "name": "Ghost", "kind": "CREATE"},
                  {"ref": 3, "name": "Göndər", "kind": "DESTROY_ALL"},
                  {"ref": "2", "name": "Başlıq", "kind": "submit"},
                  {"ref": 2, "name": "Again", "kind": "OTHER"},
                  {"ref": 1, "kind": "NAVIGATE"},
                  {"ref": 1.5, "name": "Half", "kind": "OTHER"},
                  "not an object"
                ], "unknowns": [{"context": "no question"}]}
                """.trimIndent(),
            )

        analysis.actions shouldContainExactly listOf(ProposedAction(2, "Başlıq", ActionKind.SUBMIT))
        analysis.rejected shouldHaveSize 7
        analysis.rejected.joinToString() shouldContain "element 99 is not in the Elements list"
        analysis.rejected.joinToString() shouldContain "kind 'DESTROY_ALL' is not one of"
        analysis.rejected.joinToString() shouldContain "element 2 was already described"
        analysis.rejected.joinToString() shouldContain "name is missing"
        analysis.rejected.joinToString() shouldContain "ref is missing or not an integer"
        analysis.rejected.joinToString() shouldContain "actions[6] is not an object"
        analysis.rejected.joinToString() shouldContain "unknowns[0]: question is missing"
    }

    @Test
    fun `limits cap actions, unknowns and text lengths`() {
        val longName = "N".repeat(500)
        val analysis =
            parse(
                """
                {"purpose": "${"p".repeat(1000)}", "actions": [
                  {"ref": 1, "name": "$longName", "kind": "NAVIGATE"}, {"ref": 2, "name": "B", "kind": "OTHER"}],
                 "unknowns": [{"question": "q1"}, {"question": "q2"}]}
                """.trimIndent(),
                maxActions = 1,
                maxUnknowns = 1,
            )

        analysis.purpose!!.length shouldBe PageAnalysisProtocol.MAX_PURPOSE_CHARS
        analysis.actions
            .single()
            .name.length shouldBe PageAnalysisProtocol.MAX_NAME_CHARS
        analysis.unknowns.map { it.question } shouldContainExactly listOf("q1")
        analysis.rejected shouldHaveSize 2
    }

    @Test
    fun `an answer without purpose or lists is still usable and says what was missing`() {
        val analysis = parse("""{"purpose": 42, "actions": {"ref": 1}}""")

        analysis.purpose.shouldBeNull()
        analysis.actions.shouldBeEmpty()
        analysis.rejected shouldContainExactly listOf("purpose is missing or not a text", "actions is not a list")
    }

    @Test
    fun `the schema allows only known kinds and closed objects`() {
        val schema = PageAnalysisProtocol.schema
        schema["additionalProperties"]?.jsonPrimitive?.content shouldBe "false"
        val kinds =
            schema["properties"]!!
                .jsonObject["actions"]!!
                .jsonObject["items"]!!
                .jsonObject["properties"]!!
                .jsonObject["kind"]!!
                .jsonObject["enum"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
        kinds shouldContainExactly ActionKind.entries.map { it.name }
    }

    @Test
    fun `the user message carries instructions, viewer, forms and the redacted snapshot but no values of secret fields`() {
        val secretSnapshot =
            PageSnapshot(
                url = "https://site.test/invite/Xk9pQ2mN7vB4tL8wR5yZabcd?token=s3cr3t",
                title = "Dəvət",
                elements = listOf(PageElement(1, "textbox", "Parol", "input", "invite-password", "******", true)),
                visibleText = "Link: https://site.test/reset?token=abcdef1234567890ghij and code Xk9pQ2mN7vB4tL8wR5yZabcd",
            )
        val form =
            FormModel(
                "register form 'Qəbul et'",
                ActionKind.REGISTER,
                listOf(FieldModel("Parol", "password", "password", true, "invite-password", "[data-testid=\"invite-password\"]")),
                "[data-testid=\"invite-submit\"]",
                "POST",
                "/invite/{id}",
                Provenance.OBSERVED,
                emptyList(),
            )

        val message =
            PageAnalysisProtocol.userMessage(
                PromptRedaction.snapshot(secretSnapshot, "/invite/{id}"),
                "an anonymous visitor",
                "Dəvət axınını yoxla",
                listOf(form),
                maxElements = 50,
                maxTextChars = 1000,
            )

        message shouldContain "Owner's instructions: Dəvət axınını yoxla"
        message shouldContain "Viewing as: an anonymous visitor"
        message shouldContain "- register form 'Qəbul et': Parol (password, required)"
        message shouldContain "URL: /invite/{id}"
        message shouldNotContain "Xk9pQ2mN7vB4tL8wR5yZabcd"
        message shouldNotContain "s3cr3t"
        message shouldNotContain "abcdef1234567890ghij"
    }
}

package az.petek.browser.infrastructure

import az.petek.browser.domain.DialogType
import az.petek.core.time.HarnessTimestamp
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant

class DialogRecorderTest {
    private fun at(nanos: Long) = HarnessTimestamp(Instant.EPOCH.plusNanos(nanos), nanos)

    @Test
    fun `every browser dialog type is recognized and anything unknown counts as an alert`() {
        val recorder = DialogRecorder()

        listOf("alert", "confirm", "prompt", "beforeunload", "CONFIRM", "something-new").forEachIndexed { i, type ->
            recorder.record(type, "message $i", at(i.toLong()))
        }

        recorder.drain().map { it.type } shouldContainExactly
            listOf(DialogType.ALERT, DialogType.CONFIRM, DialogType.PROMPT, DialogType.BEFOREUNLOAD, DialogType.CONFIRM, DialogType.ALERT)
    }

    @Test
    fun `draining returns the dialogs oldest first and forgets them`() {
        val recorder = DialogRecorder()
        recorder.record("alert", "birinci", at(1))
        recorder.record("confirm", "ikinci", at(2))

        val drained = recorder.drain()

        drained.map { it.message } shouldContainExactly listOf("birinci", "ikinci")
        drained.map { it.at } shouldContainExactly listOf(at(1), at(2))
        recorder.drain().shouldBeEmpty()
    }

    @Test
    fun `a page opening dialogs in a loop only keeps the latest ones`() {
        val recorder = DialogRecorder(capacity = 3)

        repeat(10) { recorder.record("alert", "dialog $it", at(it.toLong())) }

        recorder.drain().map { it.message } shouldContainExactly listOf("dialog 7", "dialog 8", "dialog 9")
    }

    @Test
    fun `long messages are cut`() {
        val recorder = DialogRecorder(maxMessageChars = 10)

        recorder.record("alert", "a".repeat(50), at(0))

        recorder.drain().single().message shouldBe "a".repeat(9) + "…"
    }

    @Test
    fun `a secret crossing the cut is masked before the message is cut, so none of it shows`() {
        val recorder = DialogRecorder(maxMessageChars = 20)
        val secret = "Gizli-Parol-77"
        // The secret starts at index 16: cutting to 19 characters before masking would keep "Giz".
        recorder.record("alert", "Şifrəniz budur: $secret, yadda saxlayın", at(0))

        val message = recorder.drain { it.replace(secret, SecretRedactor.MASK) }.single().message

        message shouldBe "Şifrəniz budur: ***…"
        message shouldNotContain "Giz"
    }

    @Test
    fun `a secret beyond the stored part of a very long message never reaches the report`() {
        val recorder = DialogRecorder(maxMessageChars = 10)
        val secret = "Gizli-Parol"
        // Stored up to 20 characters: the secret crosses that border and is cut in half before redaction can see it.
        recorder.record("alert", "x".repeat(15) + secret, at(0))

        val message = recorder.drain { it.replace(secret, SecretRedactor.MASK) }.single().message

        message shouldBe "x".repeat(9) + "…"
    }

    @Test
    fun `dialog type keys are the lower-case names the browser uses`() {
        DialogType.entries.map { it.key } shouldContainExactly listOf("alert", "confirm", "prompt", "beforeunload")
    }
}

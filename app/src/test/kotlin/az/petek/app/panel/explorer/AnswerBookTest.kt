package az.petek.app.panel.explorer

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class AnswerBookTest {
    @TempDir
    lateinit var dir: Path

    private val site = URI("https://kadro.test/login")
    private val at = Instant.parse("2026-01-01T10:00:00Z")

    private fun book() = AnswerBook(dir.resolve("explorer").resolve(AnswerBook.FILE_NAME))

    @Test
    fun `answers join the owner's instructions for the same site only`() {
        val book = book()
        book.record(site, "exp_1", "u1", "Şirkət kodu haradan alınır?", "Admin paneldə", at)

        val grounding = book.grounding(URI("https://kadro.test/"), "Elanları yoxla", 4_000)

        grounding shouldBe "Elanları yoxla\n\n${AnswerBook.HEADING}\n- Sual: Şirkət kodu haradan alınır?\n  Cavab: Admin paneldə"
        book.grounding(URI("https://other.test"), "Elanları yoxla", 4_000) shouldBe "Elanları yoxla"
        book.grounding(URI("http://kadro.test"), "", 4_000) shouldBe "" // another scheme is another site
    }

    @Test
    fun `answering the same question again replaces the earlier answer`() {
        val book = book()
        book.record(site, "exp_1", "u1", "Şirkət kodu haradan alınır?", "Bilmirəm", at)
        book.record(site, "exp_2", "u4", "  şirkət   kodu haradan alınır?  ", "Admin paneldə", at)

        book.answers(site) shouldHaveSize 1
        book.grounding(site, "", 4_000) shouldNotContain "Bilmirəm"
        book.answerFor(site, "exp_1", "u1", "Şirkət kodu haradan alınır?") shouldBe "Admin paneldə"
    }

    @Test
    fun `the answer of a question is found by its id first and by its wording on the same site`() {
        val book = book()
        book.record(site, "exp_1", "u1", "Kod?", "A", at)

        book.answerFor(site, "exp_1", "u1", "başqa sual") shouldBe "A"
        book.answerFor(site, "exp_9", "u3", "kod?") shouldBe "A"
        book.answerFor(site, "exp_9", "u3", "Parol?").shouldBeNull()
        book.answerFor(URI("https://other.test"), "exp_1", "u1", "Kod?").shouldBeNull()
    }

    @Test
    fun `answers survive a restart, one line each even when typed over several lines`() {
        book().record(site, "exp_1", "u1", "Kod?", "birinci sətir\nikinci sətir", at)

        val reopened = book()

        reopened.answers(site).single().answer shouldBe "birinci sətir\nikinci sətir"
        reopened.grounding(site, "", 4_000) shouldContain "Cavab: birinci sətir ikinci sətir"
    }

    @Test
    fun `the oldest answers are dropped when the text would not fit, the owner's own text never`() {
        val book = book()
        (1..5).forEach { book.record(site, "exp_1", "u$it", "Sual $it?", "Cavab $it".padEnd(100, '.'), at.plusSeconds(it.toLong())) }

        val grounding = book.grounding(site, "Təlimat", 400)

        (grounding.length <= 400) shouldBe true
        grounding shouldContain "Təlimat"
        grounding shouldContain "Sual 5?"
        grounding shouldNotContain "Sual 1?"
        book.grounding(site, "x".repeat(500), 400) shouldBe "x".repeat(500)
    }

    @Test
    fun `an unreadable file starts an empty book instead of failing`() {
        val file = dir.resolve("explorer").resolve(AnswerBook.FILE_NAME)
        Files.createDirectories(file.parent)
        Files.writeString(file, "{not json")

        val book = AnswerBook(file)

        book.answers(site).shouldBeEmpty()
        book.record(site, "exp_1", "u1", "Kod?", "A", at)
        AnswerBook(file).answers(site) shouldHaveSize 1
    }
}

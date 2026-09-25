package az.petek.explorer.domain

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HtmlScannerTest {
    @Test
    fun `forms keep their fields, labels, options and buttons in document order`() {
        val html =
            """
            <form action="/tickets" method="post" data-testid="ticket-form">
              <div class="field"><label for="ticket-title">Başlıq</label>
                <input type="text" id="ticket-title" name="title" data-testid="ticket-title" data-petek-ref="3" required></div>
              <label for="ticket-description">Təsvir</label>
              <textarea id="ticket-description" name="description" data-petek-ref="4">x &lt; y</textarea>
              <label>Departament
                <select name="department" data-petek-ref="5">
                  <option value="">Departament seçin</option><option value="IT">IT</option><option>HR &amp; Maliyyə</option>
                </select>
              </label>
              <input type="hidden" name="csrf" value="token">
              <button data-testid="ticket-submit" data-petek-ref="6">Göndər</button>
            </form>
            """.trimIndent()

        val document = HtmlScanner.scan(html)

        document.forms shouldHaveSize 1
        val form = document.forms.single()
        form.action shouldBe "/tickets"
        form.method shouldBe "POST"
        form.testId shouldBe "ticket-form"
        form.fields.map { it.name } shouldContainExactly listOf("title", "description", "department")
        form.fields.map { it.label } shouldContainExactly listOf("Başlıq", "Təsvir", "Departament")
        form.fields[0].required shouldBe true
        form.fields[0].ref shouldBe 3
        form.fields[0].testId shouldBe "ticket-title"
        form.fields[1].type shouldBe "textarea"
        form.fields[2].type shouldBe "select"
        form.fields[2].options shouldContainExactly listOf("IT", "HR & Maliyyə")
        form.buttons.single().text shouldBe "Göndər"
        form.buttons.single().type shouldBe "submit"
        form.buttons.single().ref shouldBe 6
        document.hasRefs shouldBe true
    }

    @Test
    fun `links carry their text, decoded href and ref, and script content is ignored`() {
        val html =
            """
            <nav><a href="/tickets?x=1&amp;y=2" data-petek-ref="1" data-testid="nav-tickets">Müraciətlər</a>
            <a href='/announcements'><span>Elanlar</span></a><a aria-label="Bildirişlər" href="/notifications"><img src="b.svg"></a></nav>
            <script>document.write('<a href="/hidden-by-script">x</a>')</script>
            <style>a > b { color: red }</style>
            <!-- <a href="/commented">no</a> -->
            <a name="anchor-without-href">not a link</a>
            """.trimIndent()

        val document = HtmlScanner.scan(html)

        document.links.map { it.href } shouldContainExactly listOf("/tickets?x=1&y=2", "/announcements", "/notifications")
        document.links.map { it.text } shouldContainExactly listOf("Müraciətlər", "Elanlar", "Bildirişlər")
        document.links.first().ref shouldBe 1
        document.imagesWithoutAlt shouldBe 1
        document.testIds shouldContainExactly listOf("nav-tickets")
    }

    @Test
    fun `fields and buttons outside forms are loose, and a document without refs says so`() {
        val html =
            """
            <div data-testid="search"><input type="search" placeholder="Axtar" aria-label="Axtarış"><button type="button">Tap</button></div>
            <input type="submit" value="Göndər">
            """.trimIndent()

        val document = HtmlScanner.scan(html)

        document.forms shouldHaveSize 0
        document.looseFields.single().ariaLabel shouldBe "Axtarış"
        document.looseFields.single().placeholder shouldBe "Axtar"
        document.looseButtons.map { it.text } shouldContainExactly listOf("Tap", "Göndər")
        document.looseButtons.map { it.type } shouldContainExactly listOf("button", "submit")
        document.hasRefs shouldBe false
    }

    @Test
    fun `quoted attribute values may contain angle brackets and character references are decoded`() {
        val html = """<form action="/x" data-note="a > b"><input name="q" value="&#39;&#x4B;&quot;" data-testid="q"></form>"""

        val form = HtmlScanner.scan(html).forms.single()

        form.fields.single().name shouldBe "q"
        form.method shouldBe "GET"
        HtmlScanner.decode("&#39;&#x4B;&quot;&unknown;") shouldBe "'K\"&unknown;"
    }

    @Test
    fun `an unclosed form still ends at the end of the document`() {
        val form = HtmlScanner.scan("<form><input name=a><button>Ok").forms.single()

        form.fields.single().name shouldBe "a"
        form.buttons.single().text shouldBe "Ok"
    }
}

package az.petek.reporting.infrastructure

import az.petek.reporting.domain.StepRow
import io.kotest.matchers.paths.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HtmlReportWriterTest {
    @TempDir
    lateinit var root: Path

    private val writer = HtmlReportWriter()

    /** The page's text with tags removed and whitespace collapsed, as a reader would see it. */
    private fun visibleText(html: String): String =
        html
            .substringAfter("<body>")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")

    @Test
    fun `writes index html into the report directory and creates it when missing`() {
        val directory = root.resolve("run_test").resolve("report")

        val file = writer.write(SampleReport.model(), directory)

        file shouldBe directory.resolve("index.html")
        file.shouldExist()
        Files.readString(file) shouldBe writer.render(SampleReport.model())
    }

    @Test
    fun `the page is a self-contained Azerbaijani document with light and dark themes`() {
        val html = writer.render(SampleReport.model())

        html shouldStartWith "<!DOCTYPE html>\n<html lang=\"az\">"
        html shouldContain "<meta charset=\"utf-8\">"
        html shouldContain "<title>Pətək hesabatı: kadrohr-core</title>"
        html shouldContain "@media (prefers-color-scheme: dark)"
        html shouldContain "color-scheme: light dark"
        html shouldNotContain "<script"
        html shouldNotContain "<link"
        html shouldNotContain "src=\"http"
    }

    @Test
    fun `every section has its Azerbaijani title`() {
        val headings = Regex("<h2>(.*?)</h2>").findAll(writer.render(SampleReport.model())).map { it.groupValues[1] }.toList()

        headings shouldBe
            listOf(
                "Xülasə",
                "Tapıntılar (2)",
                "Addımlar (3)",
                "Real-time gecikmə",
                "Stabillik",
                "Uğursuz agentlər (1)",
                "İstifadə: token və xərc",
            )
    }

    @Test
    fun `summary tiles carry the key numbers`() {
        val text = visibleText(writer.render(SampleReport.model()))

        text shouldContain "Keçən addımlar 57"
        text shouldContain "Keçməyən addımlar 3"
        text shouldContain "Assertlər 88 / 4 / 2 keçdi / keçmədi / ötürüldü"
        text shouldContain "Tapıntılar 2"
        text shouldContain "Agentlər 30"
        text shouldContain "Müddət 4 dəq 05 san"
        text shouldContain "Tokenlər 1 323 579 giriş 1 234 567 · çıxış 89 012"
        text shouldContain "Xərc $0.0420"
        text shouldContain "Real-time SSE, POLLING"
        text shouldContain "Nəticə: keçmədi"
        text shouldContain "Təkrar qrupu: grp_1 (#2)"
    }

    @Test
    fun `findings show A B C side by side with screenshot thumbnails`() {
        val html = writer.render(SampleReport.model())
        val text = visibleText(html)

        text shouldContain "Çatdırılma / UI xətası 1. read_announce · a17"
        text shouldContain "A · göndərən — B · alan Sabah 10:00 ümumi iclas -&gt; (none) C · oracle published -&gt; published"
        text shouldContain "Backend xətası 2. join · Vəli Həsənov (a07)"
        text shouldContain "C · oracle no e-mail sent"
        html shouldContain
            "<a href=\"../a01/0001-screenshot.png\"><img alt=\"screenshot 0001-screenshot.png\" src=\"../a01/0001-screenshot.png\""
        html shouldContain "<a href=\"../a17/0002-oracle.json\"><span class=\"file\">0002-oracle.json</span></a>"
    }

    @Test
    fun `the step latency stability failed agent and usage tables carry their numbers`() {
        val text = visibleText(writer.render(SampleReport.model()))

        text shouldContain "announce Əli Məmmədov (a01) do keçdi 4,2 san —"
        text shouldContain "join Vəli Həsənov (a07) run keçmədi 1 dəq 01 san mail_timeout: no e-mail —"
        text shouldContain "announcement_created #42 29 28 812 ms 1450 ms 2210 ms a17"
        text shouldContain "a02 640 ms a17 çatmadı"
        text shouldContain "announce 3 3 100% sabit"
        text shouldContain "read_announce 3 2 67% flaky"
        text shouldContain "a07 Vəli Həsənov join mail_timeout"
        text shouldContain "a01 1 000 000 80 000 1 500 12 $0.0400"
        text shouldContain "Cəmi 1 234 567 89 012 1 600 16 $0.0420"
    }

    @Test
    fun `the stability section is left out for a single run`() {
        writer.render(SampleReport.model(stability = null)) shouldNotContain "Stabillik"
    }

    @Test
    fun `links with a scheme are never written`() {
        writer.render(SampleReport.model()) shouldNotContain "javascript"
    }

    @Test
    fun `absolute network and backslash links are never written`() {
        val model = SampleReport.model()
        val odd =
            model.copy(
                artifactLinks =
                    model.artifactLinks +
                        mapOf("art_1" to "\\\\attacker\\share\\x.png", "art_2" to "//attacker/x.json", "art_evil" to "/etc/passwd"),
            )

        val html = writer.render(odd)

        html shouldNotContain "attacker"
        html shouldNotContain "/etc/passwd"
    }

    @Test
    fun `a refusal the forbidden step expected is shown as refused not as stuck`() {
        val model = SampleReport.model()
        val refused = StepRow("forbidden", "a12", null, "DO", "BLOCKED", 3_000, "permission_denied: no approve button", null)

        val html = writer.render(model.copy(steps = model.steps + refused))

        html shouldContain "<span class=\"pill muted\">icazə verilmədi</span>"
        html shouldContain "<span class=\"pill bad\">ilişdi</span>"
    }

    @Test
    fun `an actor that lost a race gets a muted pill of its own`() {
        val model = SampleReport.model()
        val lost = StepRow("race", "a03", null, "DO", "FAILED", 1_000, "problem_reported: already decided", null, lostRace = true)

        val html = writer.render(model.copy(steps = listOf(lost)))

        html shouldContain "<td><span class=\"pill muted\">yarışı uduzdu</span></td>"
    }

    @Test
    fun `rewriting the page replaces it and leaves no temporary file behind`() {
        val directory = root.resolve("report")
        writer.write(SampleReport.model(campaignName = "first"), directory)

        writer.write(SampleReport.model(campaignName = "second"), directory)

        Files.readString(directory.resolve("index.html")) shouldContain "Pətək hesabatı: second"
        Files.list(directory).use { files -> files.map { it.fileName.toString() }.toList() } shouldBe listOf("index.html")
    }

    @Test
    fun `script tags in any data are escaped`() {
        val script = SampleReport.SCRIPT
        val html =
            writer.render(
                SampleReport.model(
                    stepDetail = script,
                    note = "note $script",
                    agentName = script,
                    campaignName = script,
                ),
            )

        html shouldNotContain "<script"
        html shouldContain "&lt;script&gt;alert('x')&lt;/script&gt;"
        // Step detail, finding note, agent name, and the campaign name in <title> and <h1>.
        Regex("&lt;script&gt;").findAll(html).count() shouldBe 5
    }

    @Test
    fun `attribute values are escaped too`() {
        val model = SampleReport.model()
        val quoted = model.copy(artifactLinks = model.artifactLinks + ("art_1" to "../a01/x\" onerror=\"alert(1).png"))

        val html = writer.render(quoted)

        html shouldNotContain "\" onerror=\""
        html shouldContain "x&quot; onerror=&quot;alert(1).png"
    }

    @Test
    fun `empty sections say so instead of printing empty tables`() {
        val empty =
            SampleReport.model().copy(
                steps = emptyList(),
                latency = emptyList(),
                findings = emptyList(),
                failedAgents = emptyList(),
                stability = emptyList(),
            )

        val text = visibleText(writer.render(empty))

        listOf(
            "Tapıntı yoxdur: bütün mənbələr uyğun gəlir.",
            "Addım qeydə alınmayıb.",
            "Real-time hadisəsi olmayıb.",
            "Təkrar qrupunda addım yoxdur.",
            "Bütün agentlər addımlarını tamamladı.",
        ).forEach { text shouldContain it }
    }
}

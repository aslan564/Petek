/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.reporting.infrastructure

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class CiReportWritersTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `JUnit XML has one case per step row, failures and skips, and is well-formed`() {
        val xml = JUnitReportWriter().render(SampleReport.model())

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        val suite = document.getElementsByTagName("testsuite").item(0)
        suite.attributes.getNamedItem("tests").nodeValue shouldBe "3"
        suite.attributes.getNamedItem("failures").nodeValue shouldBe "1"
        suite.attributes.getNamedItem("skipped").nodeValue shouldBe "1"
        document.getElementsByTagName("failure").item(0).textContent shouldBe "mail_timeout: no e-mail"
        xml shouldContain "name=\"join · Vəli Həsənov (a07)\""
        xml shouldContain "<property name=\"workspace_id\" value=\"local\"/>"
    }

    @Test
    fun `SARIF has one result per finding with its class, tier, sources and a safe evidence location`() {
        val sarif = Json.parseToJsonElement(SarifReportWriter().render(SampleReport.model())).jsonObject

        sarif["version"]!!.jsonPrimitive.content shouldBe "2.1.0"
        val run = sarif["runs"]!!.jsonArray.single().jsonObject
        val results = run["results"] as JsonArray
        results.size shouldBe 2
        val first = results[0].jsonObject
        first["ruleId"]!!.jsonPrimitive.content shouldBe "DELIVERY_UI"
        first["level"]!!.jsonPrimitive.content shouldBe "error"
        first["properties"]!!.jsonObject["evidenceTier"]!!.jsonPrimitive.content shouldBe "UI_NETWORK"
        first["properties"]!!.jsonObject["b"]!!.jsonPrimitive.content shouldBe "Sabah 10:00 ümumi iclas -> (none)"
        val location = first["locations"]!!.jsonArray.single().jsonObject
        location["physicalLocation"]!!
            .jsonObject["artifactLocation"]!!
            .jsonObject["uri"]!!
            .jsonPrimitive.content shouldBe
            "../a01/0001-screenshot.png"
        SarifReportWriter().render(SampleReport.model()) shouldNotContain "javascript:"
    }

    @Test
    fun `both files are written next to the other report formats`() {
        val directory = root.resolve("report")

        JUnitReportWriter().write(SampleReport.model(), directory) shouldBe directory.resolve("junit.xml")
        SarifReportWriter().write(SampleReport.model(), directory) shouldBe directory.resolve("findings.sarif")
    }

    @Test
    fun `the shareable page embeds the run's screenshots and names the AI and the evidence tiers`() {
        val directory = root.resolve("run_test").resolve("report")
        val screenshot = root.resolve("run_test/a01/0001-screenshot.png")
        java.nio.file.Files
            .createDirectories(screenshot.parent)
        java.nio.file.Files
            .write(screenshot, byteArrayOf(-119, 80, 78, 71))

        val html = ShareableHtmlReportWriter("claude-cli", "claude-sonnet-5").render(SampleReport.model(), directory)

        html shouldContain "src=\"data:image/png;base64,iVBORw==\""
        html shouldNotContain "src=\"../a01/0001-screenshot.png\""
        html shouldContain "<li>AI: claude-cli · claude-sonnet-5</li>"
        html shouldContain "Sübut səviyyələri: Ekran / şəbəkə sübutu: 2"
    }

    @Test
    fun `the customer page says in short sentences what was found, on the three shelves, in the owner's language`() {
        val az = CustomerSummaryWriter().render(SampleReport.model())
        val en = CustomerSummaryWriter(english = true).render(SampleReport.model())

        az shouldContain "Pətək https://staging.kadrohr.test saytını 30 testerlə 4 dəq 05 san ərzində yoxladı."
        az shouldContain "2 problem tapıldı"
        az shouldContain "Saytda düzəldilməli (2)"
        az shouldContain "„read_announce“ addımında dəyişiklik saxlanıldı, amma istifadəçilər onu ekranda görmədi."
        az shouldContain "<a href=\"index.html\">"
        en shouldContain "To fix on the site (2)"
        en shouldContain "In step &quot;join&quot; the site did not store the change correctly"
        az shouldNotContain "<script"
    }
}

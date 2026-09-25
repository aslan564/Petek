package az.petek.explorer.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI

class UrlPatternsTest {
    @Test
    fun `numbers, uuids, hex strings, prefixed counters and random tokens become the id placeholder`() {
        UrlPatterns.of("/tickets/42") shouldBe "/tickets/{id}"
        UrlPatterns.of("/tickets/t17") shouldBe "/tickets/{id}"
        UrlPatterns.of("/announcements/a3/receipts") shouldBe "/announcements/{id}/receipts"
        UrlPatterns.of("/users/0199a3f2-7c1e-7b8a-9d3e-2f1c0b9a8e7d") shouldBe "/users/{id}"
        UrlPatterns.of("/files/0123456789abcdef0123") shouldBe "/files/{id}"
        UrlPatterns.of("/invite/Xk9pQ2mN7vB4tL8wR5yZ") shouldBe "/invite/{id}"
        UrlPatterns.of("/runs/run_0199a3f27c1e7b8a") shouldBe "/runs/{id}"
    }

    @Test
    fun `plain words, api versions and short slugs stay as they are`() {
        UrlPatterns.of("/api/v2/tickets") shouldBe "/api/v2/tickets"
        UrlPatterns.of("/company/departments") shouldBe "/company/departments"
        UrlPatterns.of("/verify/phone") shouldBe "/verify/phone"
        UrlPatterns.of("/müraciətlər") shouldBe "/müraciətlər"
        UrlPatterns.of("/a-very-long-readable-slug-without-digits") shouldBe "/a-very-long-readable-slug-without-digits"
    }

    @Test
    fun `query strings, fragments, trailing slashes and hosts do not change the pattern`() {
        UrlPatterns.of("https://site.test/login?next=%2Ftickets#top") shouldBe "/login"
        UrlPatterns.of("/tickets/") shouldBe "/tickets"
        UrlPatterns.of("https://site.test") shouldBe "/"
        UrlPatterns.of("/") shouldBe "/"
    }

    @Test
    fun `page ids are readable slugs of the pattern`() {
        UrlPatterns.pageId("/") shouldBe "home"
        UrlPatterns.pageId("/tickets/{id}") shouldBe "tickets-id"
        UrlPatterns.pageId("/müraciətlər") shouldBe "muracietler"
    }

    @Test
    fun `helpers find the resource and the page before the first id`() {
        UrlPatterns.resource("/tickets/{id}/approve") shouldBe "tickets"
        UrlPatterns.resource("/").shouldBeNull()
        UrlPatterns.beforeFirstId("/tickets/{id}/approve") shouldBe "/tickets"
        UrlPatterns.beforeFirstId("/{id}") shouldBe "/"
        UrlPatterns.hasId("/tickets/{id}") shouldBe true
        UrlPatterns.hasId("/tickets") shouldBe false
    }

    @Test
    fun `links resolve against the page and lose fragments and credentials`() {
        val page = URI("https://site.test/tickets/t1")
        UrlPatterns.resolve(page, "../announcements#list") shouldBe URI("https://site.test/announcements")
        UrlPatterns.resolve(page, "/join?code=PTK-1") shouldBe URI("https://site.test/join?code=PTK-1")
        UrlPatterns.resolve(page, "https://user:secret@site.test/x") shouldBe URI("https://site.test/x")
        UrlPatterns.resolve(page, "https://other.test") shouldBe URI("https://other.test/")
        UrlPatterns.resolve(page, "mailto:a@b.c").shouldBeNull()
        UrlPatterns.resolve(page, "javascript:void(0)").shouldBeNull()
        UrlPatterns.resolve(page, "#top").shouldBeNull()
        UrlPatterns.resolve(page, "").shouldBeNull()
    }

    @Test
    fun `display drops query strings that may carry tokens`() {
        UrlPatterns.display(URI("https://site.test:8443/reset?token=abc123")) shouldBe "https://site.test:8443/reset"
        UrlPatterns.display(URI("http://site.test")) shouldBe "http://site.test/"
    }

    @Test
    fun `an origin contains only the same scheme, host and port`() {
        val origin = SiteOrigin.of(URI("https://Site.test/app"))
        origin.contains(URI("https://site.test:443/x")) shouldBe true
        origin.contains(URI("http://site.test/x")) shouldBe false
        origin.contains(URI("https://site.test:8443/x")) shouldBe false
        origin.contains(URI("https://evil.site.test/x")) shouldBe false
        origin.contains(URI("/relative")) shouldBe false
        origin.toString() shouldBe "https://site.test"
    }

    @Test
    fun `target keys ignore case of the host, default ports and trailing slashes`() {
        TargetKey.of(URI("https://Site.test/")) shouldBe TargetKey.of(URI("https://site.test:443"))
        TargetKey.of(URI("https://site.test/app/")) shouldBe "https://site.test/app"
    }

    @Test
    fun `slugs transliterate azerbaijani letters and identifiers start with a letter`() {
        Slugs.of("Şirkət Qeydiyyatı — Yeni!") shouldBe "sirket-qeydiyyati-yeni"
        Slugs.identifier("müraciətlər", "item") shouldBe "muracietler"
        Slugs.identifier("2024-report", "item") shouldBe "item_2024_report"
        Slugs.identifier("***", "item") shouldBe "item"
    }
}

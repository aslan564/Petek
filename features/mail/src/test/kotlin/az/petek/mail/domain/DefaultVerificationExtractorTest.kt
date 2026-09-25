/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.mail.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.time.Instant

class DefaultVerificationExtractorTest {
    private val extractor = DefaultVerificationExtractor()

    @ParameterizedTest(name = "{0}")
    @MethodSource("codeCases")
    fun `extracts the verification code`(case: Case) {
        extractor.extract(case.message, MailPurpose.CODE)?.code shouldBe case.expected
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("linkCases")
    fun `extracts the confirmation or invitation link`(case: Case) {
        extractor.extract(case.message, MailPurpose.LINK)?.link?.toString() shouldBe case.expected
    }

    @Test
    fun `CODE is not satisfied by a mail that only has a link`() {
        extractor.extract(mail(text = "Dəvət: https://x.az/invite/abc"), MailPurpose.CODE).shouldBeNull()
    }

    @Test
    fun `LINK is not satisfied by a mail that only has a code`() {
        extractor.extract(mail(text = "Təsdiq kodu: 482913"), MailPurpose.LINK).shouldBeNull()
    }

    @Test
    fun `ANY is satisfied by a code alone or by a link alone`() {
        extractor.extract(mail(text = "Kod: 482913"), MailPurpose.ANY) shouldBe VerificationCode("482913", null, "m1")
        extractor.extract(mail(text = "https://x.az/invite/abc"), MailPurpose.ANY)?.link?.toString() shouldBe "https://x.az/invite/abc"
    }

    @Test
    fun `everything found is returned together with the message id`() {
        val message = mail(id = "msg-42", text = "Təsdiq kodu: 482913\nvə ya keçid: https://x.az/verify?t=abc")

        val result = extractor.extract(message, MailPurpose.CODE)

        result?.code shouldBe "482913"
        result?.link?.toString() shouldBe "https://x.az/verify?t=abc"
        result?.messageId shouldBe "msg-42"
    }

    @Test
    fun `an empty mail satisfies no purpose`() {
        MailPurpose.entries.forEach { purpose -> extractor.extract(mail(subject = "", text = ""), purpose).shouldBeNull() }
    }

    @Test
    fun `empty hrefs are skipped instead of failing the whole extraction`() {
        val html = "<a href=\"\">Bax</a><a href=''>Ləğv et</a><a href=\"https://x.az/invite/t1\">Qəbul et</a>"

        extractor.extract(mail(text = "Kod: 482913", html = html), MailPurpose.CODE)?.code shouldBe "482913"
        extractor.extract(mail(html = html), MailPurpose.LINK)?.link?.toString() shouldBe "https://x.az/invite/t1"
        extractor.extract(mail(html = "<a href=\"\">x</a>"), MailPurpose.ANY).shouldBeNull()
    }

    @Test
    fun `a very long run without spaces is scanned in linear time`() {
        val blob = "a".repeat(200_000)
        val message = mail(text = "Təsdiq kodu: 482913\n$blob\n$blob@", html = "<p>$blob</p>")

        val result = assertTimeoutPreemptively(Duration.ofSeconds(10), ThrowingSupplier { extractor.extract(message, MailPurpose.ANY) })

        result?.code shouldBe "482913"
    }

    /** A named example; the name is what the test report shows. */
    class Case(
        private val name: String,
        val message: MailMessage,
        val expected: String?,
    ) {
        override fun toString(): String = name
    }

    companion object {
        private val FILLER = "Bu məktub avtomatik göndərilib, xahiş edirik ona cavab verməyin."

        fun mail(
            text: String = "",
            html: String? = null,
            subject: String = "Pətək",
            id: String = "m1",
        ) = MailMessage(
            id = id,
            to = listOf("eli.k7x2.a07@test.kadrohr.com"),
            subject = subject,
            receivedAt = Instant.parse("2026-09-25T10:00:00Z"),
            text = text,
            html = html,
            read = false,
        )

        private fun case(
            name: String,
            expected: String?,
            text: String = "",
            html: String? = null,
            subject: String = "Pətək",
        ) = Case(name, mail(text = text, html = html, subject = subject), expected)

        @JvmStatic
        fun codeCases(): List<Case> =
            listOf(
                case(
                    "Azerbaijani text mail",
                    "482913",
                    subject = "Təsdiq kodu",
                    text = "Salam Əli,\n\nSizin təsdiq kodunuz: 482913\nKod 10 dəqiqə ərzində etibarlıdır.\n\n© 2026 KadroHR",
                ),
                case(
                    "English text mail",
                    "739201",
                    subject = "Your code",
                    text = "Hi,\nYour verification code is 739201. It expires in 15 minutes.\nKadroHR, 2026",
                ),
                case(
                    "HTML-only mail with head, style and a second number",
                    "552190",
                    html =
                        "<html><head><title>Kod 111111</title><style>.box{width:600000px}</style></head><body>" +
                            "<p>Təsdiq kodunuz:</p>\n<p><strong>552190</strong></p><p>Sifariş №&nbsp;1234</p></body></html>",
                ),
                case(
                    "the number right after the keyword wins on a shared line",
                    "482913",
                    text = "Sifariş 553311 üçün təsdiq kodu: 482913",
                ),
                case(
                    "a number on a keyword line beats a longer one elsewhere",
                    "8841",
                    text = "Hesab nömrəniz: 123456\n$FILLER\nOTP: 8841",
                ),
                case("a year next to the code is ignored", "4821", text = "Your code: 4821\n© 2026 Company"),
                case("a year alone is not a code", null, text = "Kod: 2025"),
                case("an international phone number is not a code", null, text = "Kod göndərildi. Sual üçün zəng: +994 50 1234567"),
                case("a compact international phone number is not a code", null, text = "Bizə zəng edin: +99450 1234567"),
                case("a grouped local phone number is not a code", null, text = "Kod üçün zəng edin: 012 437 1234"),
                case("a phone number with an area code is not a code", null, text = "Kod üçün (012) 4371234 nömrəsinə zəng edin"),
                case("a code survives a phone number on the next line", "482913", text = "Təsdiq kodu: 482913\nƏlaqə: +994 50 123 45 67"),
                case("a mail without numbers has no code", null, text = "Salam, Pətək-ə xoş gəlmisiniz!"),
                case("a company code glued to letters is not a code", null, text = "Şirkət kodu: PTK-4821"),
                case("the real code is found next to a company code", "663120", text = "Şirkət kodu: PTK-4821\nTəsdiq kodu: 663120"),
                case(
                    "digits inside links and e-mail addresses are not codes",
                    null,
                    text =
                        "Kodu buradan daxil edin: https://staging.kadrohr.com/verify/123456?u=77889900" +
                            " və ya yazın: user12345@test.kadrohr.com",
                ),
                case("without a keyword a 6-digit number is preferred", "567890", text = "Ref 1234 and 567890"),
                case("an Azerbaijani suffix after a hyphen is allowed", "482913", text = "Kodunuz 482913-dür."),
                case("decimals are not codes", null, text = "Kod haqqı: 12345.67 AZN"),
                case("thousand separators are not codes", null, text = "Kod haqqı: 1,2345 AZN"),
                case("uppercase ŞİFRƏ is a keyword", "3344", text = "Müştəri nömrəsi 99887766\n$FILLER\nBİRDƏFƏLİK ŞİFRƏ: 3344"),
                case("uppercase TƏSDİQ is a keyword", "3344", text = "Müştəri nömrəsi 99887766\n$FILLER\nTƏSDİQ: 3344"),
                case("the code may be in the subject", "482913", subject = "482913 is your Pətək code", text = "Welcome!"),
                case("a date is not a code", null, text = "Kod 25.09.2026 10:30-da göndərilib"),
                case("three digits are too short", null, text = "Kod: 123"),
                case("nine digits are too long", null, text = "Kod: 123456789"),
                case("an eight digit code is accepted", "12345678", text = "Your one-time code: 12345678"),
                case("the text part is preferred over the HTML part", "111222", text = "Kod: 111222", html = "<p>Kod: 333444</p>"),
                case("inline tags do not glue the code to the keyword", "482913", html = "<p>Kod:<b>482913</b></p>"),
                case(
                    "HTML comments and scripts are ignored",
                    "482913",
                    html = "<!-- kod 999999 --><script>var code = 777777;</script><p>Your number 482913</p>",
                ),
                case(
                    "the HTML part is used when the text part has no code",
                    "905512",
                    text = "Salam!",
                    html = "<p>OTP</p><div>905512</div>",
                ),
            )

        @JvmStatic
        fun linkCases(): List<Case> =
            listOf(
                case(
                    "HTML anchor with an entity-encoded query",
                    "https://staging.kadrohr.com/invite/tok-123?x=1&y=2",
                    html =
                        "<p>Sizi dəvət edirik</p>" +
                            "<a class=\"btn\" href=\"https://staging.kadrohr.com/invite/tok-123?x=1&amp;y=2\">Qəbul et</a>",
                ),
                case(
                    "the first matching anchor wins and unrelated anchors are skipped",
                    "https://staging.kadrohr.com/verify?t=abc",
                    html =
                        "<a href=\"https://kadrohr.com/\">Ana səhifə</a> " +
                            "<a href='https://staging.kadrohr.com/verify?t=abc'>Təsdiqlə</a> " +
                            "<a href=\"https://staging.kadrohr.com/confirm?t=zzz\">x</a>",
                ),
                case(
                    "text URL followed by sentence punctuation",
                    "https://staging.kadrohr.com/invite/abc123",
                    text = "Dəvəti qəbul etmək üçün: https://staging.kadrohr.com/invite/abc123.",
                ),
                case(
                    "an HTML anchor is preferred over a text URL",
                    "https://x.az/invite/from-html",
                    text = "https://x.az/invite/from-text",
                    html = "<a href=\"https://x.az/invite/from-html\">Qəbul et</a>",
                ),
                case(
                    "links that are not about verification are ignored",
                    null,
                    text = "https://kadrohr.com/about https://kadrohr.com/pricing",
                ),
                case(
                    "a non-ASCII dəvət path is percent-encoded",
                    "https://x.az/d%C9%99v%C9%99t/abc",
                    text = "Keçid: https://x.az/dəvət/abc",
                ),
                case("a percent-encoded dəvət path matches", "https://x.az/d%C9%99v%C9%99t/abc", text = "https://x.az/d%C9%99v%C9%99t/abc"),
                case(
                    "matching is case-insensitive",
                    "HTTPS://X.COM/Account/VERIFY?t=1",
                    text = "Open HTTPS://X.COM/Account/VERIFY?t=1 now",
                ),
                case(
                    "non-http schemes are ignored",
                    null,
                    html = "<a href=\"mailto:verify@x.az\">m</a><a href=\"javascript:confirm()\">j</a><a href=\"ftp://x.az/verify\">f</a>",
                ),
                case(
                    "markdown parentheses are not part of the URL",
                    "https://x.az/confirm/abc",
                    text = "[Təsdiqlə](https://x.az/confirm/abc)",
                ),
                case(
                    "a relative href falls back to a text URL",
                    "https://x.az/activate/abc",
                    text = "https://x.az/activate/abc",
                    html = "<a href=\"/invite/abc\">Qəbul et</a>",
                ),
                case(
                    "a tracking redirect that wraps an invite link",
                    "https://click.mail.az/r?u=https%3A%2F%2Fx.az%2Finvite%2Fabc",
                    text = "https://click.mail.az/r?u=https%3A%2F%2Fx.az%2Finvite%2Fabc",
                ),
                case("a stray percent sign is escaped", "https://x.az/verify?d=50%25", text = "https://x.az/verify?d=50%"),
                case(
                    "a link written only in the HTML text is found",
                    "https://x.az/invite/t1",
                    html = "<p>Keçid: https://x.az/invite/t1</p>",
                ),
            )
    }
}

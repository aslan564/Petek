/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.explorer.domain

import az.petek.browser.domain.PageElement
import az.petek.browser.domain.PageSnapshot
import az.petek.explorer.support.FakeSite
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.URI

class PageHeuristicsTest {
    private fun inspect(
        url: String,
        build: FakeSite.PageBuilder.() -> Unit,
    ): PageFacts {
        val builder = FakeSite.PageBuilder().apply(build)
        val snapshot = PageSnapshot(url, "Page", builder.elements, builder.visibleText("Page"))
        return PageHeuristics.inspect(snapshot, HtmlScanner.scan(builder.html("Page")), URI(url))
    }

    @Test
    fun `a login form is classified by its password field and gives a login action with its http path`() {
        val facts =
            inspect("https://site.test/login?next=%2F") {
                form("/login") {
                    hidden("next", "/")
                    field("E-poçt", "email", type = "email", testId = "login-email")
                    field("Parol", "password", type = "password", testId = "login-password")
                    submit("Daxil ol", testId = "login-submit")
                }
                link("Şirkət qeydiyyatı", "/register")
            }

        val form = facts.forms.single()
        form.kind shouldBe ActionKind.LOGIN
        form.purpose shouldBe "login form 'Daxil ol'"
        form.method shouldBe "POST"
        form.actionPath shouldBe "/login"
        form.submitSelector shouldBe "[data-testid=\"login-submit\"]"
        form.fields.map { it.name } shouldContainExactly listOf("email", "password")
        form.fields.map { it.type } shouldContainExactly listOf("email", "password")
        form.fields.map { it.label } shouldContainExactly listOf("E-poçt", "Parol")
        form.fields.first().selector shouldBe "[data-testid=\"login-email\"]"
        val action = facts.actions.single()
        action.kind shouldBe ActionKind.LOGIN
        action.name shouldBe "Daxil ol"
        action.httpMethod shouldBe "POST"
        action.httpPath shouldBe "/login"
        action.provenance shouldBe Provenance.OBSERVED
        facts.links.map { it.href } shouldContainExactly listOf("/register")
    }

    @Test
    fun `sign-up forms with a password are registrations`() {
        val register =
            inspect("https://site.test/register") {
                form("/register") {
                    field("Ad Soyad", "name", testId = "register-name")
                    field("E-poçt", "email", type = "email")
                    field("Telefon", "phone", type = "tel")
                    field("Parol", "password", type = "password")
                    field("Şirkətin adı", "company")
                    submit("Qeydiyyatdan keç", testId = "register-submit")
                }
            }
        val join =
            inspect("https://site.test/join") {
                form("/join") {
                    field("Şirkət kodu", "code")
                    field("E-poçt", "email", type = "email")
                    field("Parol", "password", type = "password")
                    submit("Qoşul", testId = "join-submit")
                }
            }

        register.forms.single().kind shouldBe ActionKind.REGISTER
        join.forms.single().kind shouldBe ActionKind.REGISTER
    }

    @Test
    fun `sign-up and sign-in forms without a password field are never taken for create forms`() {
        val signUp =
            inspect("https://site.test/signup") {
                form("/signup") {
                    field("Ad Soyad", "name")
                    field("E-poçt", "email", type = "email")
                    submit("Qeydiyyatdan keç")
                }
            }
        val join =
            inspect("https://site.test/join") {
                form("/join") {
                    field("Şirkət kodu", "code")
                    field("Ad Soyad", "name")
                    field("E-poçt", "email", type = "email")
                    submit("Qoşul", testId = "join-submit")
                }
            }
        val magicLink =
            inspect("https://site.test/") {
                form("/session") {
                    field("E-poçt", "email", type = "email")
                    submit("Daxil ol")
                }
            }
        val enterData =
            inspect("https://site.test/notes") {
                form("/notes") {
                    field("Qeyd", "note")
                    submit("Məlumatı daxil et")
                }
            }

        signUp.forms.single().kind shouldBe ActionKind.REGISTER
        join.forms.single().kind shouldBe ActionKind.REGISTER
        magicLink.forms.single().kind shouldBe ActionKind.LOGIN
        // "daxil et" (enter data) is not "daxil ol" (sign in): a note form stays a create form.
        enterData.forms.single().kind shouldBe ActionKind.CREATE
    }

    @Test
    fun `a form sent to another site is never a create form, also when only its button says so`() {
        val facts =
            inspect("https://site.test/newsletter") {
                form("https://mailer.example/subscribe") {
                    field("E-poçt", "email", type = "email")
                    submit("Əlavə et", testId = "newsletter-add")
                }
                form("/notes") {
                    field("Qeyd", "note")
                    submit("Yarat", testId = "note-create", formAction = "https://other.example/collect")
                }
                form("javascript:void(0)") {
                    field("Başlıq", "title")
                    submit("Yarat", testId = "spa-create")
                }
            }

        facts.forms.map { it.kind } shouldContainExactly listOf(ActionKind.OTHER, ActionKind.OTHER, ActionKind.CREATE)
        facts.forms.first().purpose shouldContain "another site"
        facts.forms.first().actionPath shouldBe null
        facts.actions.single { it.selector == "[data-testid=\"spa-create\"]" }.kind shouldBe ActionKind.CREATE
    }

    @Test
    fun `what the submit button really sends decides the method and the kind`() {
        val facts =
            inspect("https://site.test/tickets/t1") {
                form("/tickets/t1") {
                    hidden("_method", "delete")
                    field("Səbəb", "reason")
                    submit("Tamamla", testId = "ticket-finish")
                }
                form("/tickets/t1/comments") {
                    textarea("Şərh", "body")
                    submit("Əlavə et", testId = "comment-add")
                    submit("Hamısını sil", testId = "comment-purge", formAction = "/tickets/t1/comments/purge")
                }
                form("/notes") {
                    hidden("_method", "PATCH")
                    field("Qeyd", "note")
                    submit("Yarat", testId = "note-send", formMethod = "post")
                }
            }

        val finish = facts.forms[0]
        finish.method shouldBe "DELETE"
        finish.kind shouldBe ActionKind.DELETE
        facts.actions.single { it.selector == "[data-testid=\"ticket-finish\"]" }.httpMethod shouldBe "DELETE"
        // The first submit button is what submitting sends (Enter, the trial touch); a later button's formaction is not.
        facts.forms[1].kind shouldBe ActionKind.CREATE
        facts.forms[1].actionPath shouldBe "/tickets/{id}/comments"
        facts.forms[2].method shouldBe "PATCH"
    }

    @Test
    fun `an e-mail code form is a verification, never an approval, although its button says Təsdiqlə`() {
        val facts =
            inspect("https://site.test/verify?email=a%40b.c") {
                form("/verify") {
                    hidden("email", "a@b.c")
                    field("Təsdiq kodu", "code", testId = "verify-code")
                    submit("Təsdiqlə", testId = "verify-submit")
                }
                form("/verify/resend") { submit("Kodu yenidən göndər", testId = "verify-resend") }
            }

        facts.forms.map { it.kind } shouldContainExactly listOf(ActionKind.SUBMIT, ActionKind.SUBMIT)
        facts.forms.first().purpose shouldBe "verification form 'Təsdiqlə'"
    }

    @Test
    fun `a create form lists its fields with types, options and requirement`() {
        val facts =
            inspect("https://site.test/tickets") {
                form("/tickets") {
                    field("Başlıq", "title", testId = "ticket-title", required = true)
                    textarea("Təsvir", "description", testId = "ticket-description")
                    select("Departament", "department", listOf("IT", "HR"), testId = "ticket-department")
                    submit("Göndər", testId = "ticket-submit")
                }
                marker("ticket-item")
            }

        val form = facts.forms.single()
        form.kind shouldBe ActionKind.CREATE
        form.actionPath shouldBe "/tickets"
        form.fields.map { it.type } shouldContainExactly listOf("text", "textarea", "select")
        form.fields.first().required shouldBe true
        form.fields.last().options shouldContainExactly listOf("IT", "HR")
        facts.actions.single().kind shouldBe ActionKind.CREATE
        facts.testIds shouldContainExactly listOf("ticket-title", "ticket-description", "ticket-department", "ticket-submit", "ticket-item")
    }

    @Test
    fun `button-only forms of an object page are approvals, rejections, status changes and assignments`() {
        val facts =
            inspect("https://site.test/tickets/t7") {
                form("/tickets/t7/in-progress") { submit("İcraya götür", testId = "ticket-set-in-progress") }
                form("/tickets/t7/assign") {
                    select("İcraçı", "email", listOf("hr@x.az"), testId = "ticket-assignee")
                    submit("Təyin et", testId = "ticket-assign")
                }
                form("/tickets/t7/approve") { submit("Təsdiqlə", testId = "ticket-approve") }
                form("/tickets/t7/reject") { submit("Rədd et", testId = "ticket-reject") }
                form("/logout") { submit("Çıxış", testId = "logout") }
            }

        facts.actions.map { it.kind } shouldContainExactly
            listOf(ActionKind.UPDATE, ActionKind.ASSIGN, ActionKind.APPROVE, ActionKind.REJECT, ActionKind.OTHER)
        facts.actions.map { it.httpPath } shouldContainExactly
            listOf("/tickets/{id}/in-progress", "/tickets/{id}/assign", "/tickets/{id}/approve", "/tickets/{id}/reject", "/logout")
        facts.forms.last().purpose shouldBe "logout form 'Çıxış'"
    }

    @Test
    fun `forms without any visible element are left out when the DOM carries snapshot refs`() {
        val html =
            """
            <form action="/visible" method="post"><input name="a" data-petek-ref="1"><button data-petek-ref="2">Yarat</button></form>
            <form action="/hidden" method="post"><input name="b"><button>Gizli</button></form>
            """.trimIndent()
        val elements =
            listOf(
                PageElement(1, "textbox", "A", "input", null, "", true),
                PageElement(2, "button", "Yarat", "button", null, null, true),
            )
        val facts =
            PageHeuristics.inspect(
                PageSnapshot("https://site.test/x", "X", elements, ""),
                HtmlScanner.scan(html),
                URI("https://site.test/x"),
            )

        facts.forms.map { it.actionPath } shouldContainExactly listOf("/visible")
        facts.forms
            .single()
            .fields
            .single()
            .selector shouldBe "form[action=\"/visible\"] input[name=\"a\"]"
        facts.forms.single().submitSelector shouldBe "role=button[name=\"Yarat\"]"
    }

    @Test
    fun `a page without form elements gets one form from its element list`() {
        val elements =
            listOf(
                PageElement(1, "link", "Ana səhifə", "a", null, null, true),
                PageElement(2, "textbox", "E-poçt", "input", "email", "", true),
                PageElement(3, "textbox", "Parol", "input", null, "******", true),
                PageElement(4, "button", "Daxil ol", "button", null, null, true),
            )
        val facts =
            PageHeuristics.inspect(
                PageSnapshot("https://site.test/", "App", elements, ""),
                ScannedDocument.EMPTY,
                URI("https://site.test/"),
            )

        val form = facts.forms.single()
        form.kind shouldBe ActionKind.LOGIN
        form.fields.map { it.type } shouldContainExactly listOf("text", "password")
        form.fields.map { it.selector } shouldContainExactly listOf("[data-testid=\"email\"]", "role=textbox[name=\"Parol\"]")
        form.submitSelector shouldBe "role=button[name=\"Daxil ol\"]"
        facts.actions.single().name shouldBe "Daxil ol"
    }

    @Test
    fun `buttons outside forms become actions only when their words say what they do`() {
        val html =
            """<button data-testid="ticket-approve" data-petek-ref="1">Təsdiqlə</button><button data-petek-ref="2">Göstər</button>"""
        val elements =
            listOf(
                PageElement(1, "button", "Təsdiqlə", "button", "ticket-approve", null, true),
                PageElement(2, "button", "Göstər", "button", null, null, true),
            )
        val facts =
            PageHeuristics.inspect(
                PageSnapshot("https://site.test/t", "T", elements, ""),
                HtmlScanner.scan(html),
                URI("https://site.test/t"),
            )

        facts.actions.map { it.kind } shouldContainExactly listOf(ActionKind.APPROVE)
        facts.actions.single().selector shouldBe "[data-testid=\"ticket-approve\"]"
    }

    @Test
    fun `unnamed fields and controls and images without alt text are accessibility issues`() {
        val elements =
            listOf(
                PageElement(1, "textbox", "", "input", "search-box", "", true),
                PageElement(2, "button", "", "button", null, null, true),
                PageElement(3, "link", "Elanlar", "a", null, null, true),
            )
        val facts =
            PageHeuristics.inspect(
                PageSnapshot("https://site.test/", "App", elements, ""),
                HtmlScanner.scan("<img src=a.png><img src=b.png alt=''>"),
                URI("https://site.test/"),
            )

        facts.accessibilityIssues shouldHaveSize 3
        facts.accessibilityIssues[0] shouldContain "testid=search-box"
        PageHeuristics.hasUnnamedFields(facts.accessibilityIssues) shouldBe true
        facts.accessibilityIssues[2] shouldBe "1 image(s) without alt text"
    }

    @Test
    fun `leaked errors in the visible text are anomalies, ordinary text is not`() {
        val text =
            """
            Müraciətlər
            Status: undefined
            Total: NaN AZN
            [object Object]
            java.lang.NullPointerException: boom
            at az.kadro.Api.run(Api.kt:42)
            Hər şey qaydasındadır
            """.trimIndent()
        val facts =
            PageHeuristics.inspect(
                PageSnapshot("https://site.test/", "App", emptyList(), text),
                ScannedDocument.EMPTY,
                URI("https://site.test/"),
            )

        facts.anomalies shouldContainExactly
            listOf(
                "Status: undefined",
                "Total: NaN AZN",
                "[object Object]",
                "java.lang.NullPointerException: boom",
                "at az.kadro.Api.run(Api.kt:42)",
            )
        PageHeuristics
            .inspect(
                PageSnapshot("https://site.test/", "App", emptyList(), "Undefinable NaNa\nXəta yoxdur"),
                ScannedDocument.EMPTY,
                URI("https://site.test/"),
            ).anomalies
            .shouldBeEmpty()
    }
}

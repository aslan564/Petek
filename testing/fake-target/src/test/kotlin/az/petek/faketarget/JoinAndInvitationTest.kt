/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget

import az.petek.faketarget.model.UserRole
import az.petek.faketarget.support.FakeTargetFixture
import az.petek.faketarget.support.Invite
import az.petek.faketarget.support.string
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class JoinAndInvitationTest {
    private val fake = FakeTargetFixture()

    @AfterEach
    fun tearDown() = fake.close()

    private suspend fun companyWithDepartments(): Pair<String, String> {
        val owner = fake.registerOwner()
        val company = fake.companyOf(owner.email)
        fake.seed(company.string("id"), listOf("IT", "HR", "Satış"))
        return company.string("id") to company.string("code")
    }

    @Test
    fun `the join form lists the company's departments once the code is known`() =
        runBlocking<Unit> {
            val (_, code) = companyWithDepartments()
            val browser = fake.browser()

            val empty = browser.get("/join")
            listOf("join-company-code", "join-name", "join-email", "join-phone", "join-password", "join-department", "join-submit")
                .forEach { empty.has(it) shouldBe true }
            empty.options("join-department") shouldContainExactly listOf("" to "Departament seçin")
            empty.body shouldContain "/join/departments?code="

            browser.get("/join?code=${code.lowercase()}").options("join-department").map { it.first } shouldContainExactly
                listOf("", "IT", "HR", "Satış")

            val departments = browser.get("/join/departments?code=$code")
            departments.status shouldBe 200
            departments
                .json()["departments"]!!
                .jsonArray
                .map { it.jsonObject.string("name") } shouldContainExactly listOf("IT", "HR", "Satış")
            browser.get("/join/departments?code=PTK-0000").status shouldBe 404
        }

    @Test
    fun `joining with the company code creates an employee of the chosen department`() =
        runBlocking<Unit> {
            val (companyId, code) = companyWithDepartments()
            val member = fake.joinWithCode(code, "new.employee@test.kadrohr.com", "Satış", "Nigar Əliyeva")

            val home = member.browser.get("/")
            home.text("current-user-name") shouldBe "Nigar Əliyeva"
            home.text("current-user-role") shouldBe "employee"
            val user =
                fake.server.store
                    .user("new.employee@test.kadrohr.com")
                    .shouldNotBeNull()
            user.companyId shouldBe companyId
            user.role shouldBe UserRole.EMPLOYEE
            user.department?.name shouldBe "Satış"
        }

    @Test
    fun `an unknown company code is a join-error and keeps the typed fields`() =
        runBlocking<Unit> {
            companyWithDepartments()
            val page =
                fake.browser().submit(
                    "/join",
                    "code" to "PTK-0000",
                    "name" to "Nigar",
                    "email" to "n@test.kadrohr.com",
                    "phone" to "+994500000055",
                    "password" to "member-secret-1",
                    "department" to "IT",
                )
            page.status shouldBe 200
            page.text("join-error") shouldBe "Bu kodla şirkət tapılmadı."
            page.attribute("join-email", "value") shouldBe "n@test.kadrohr.com"
            fake.browser().get("/join?code=PTK-0000").text("join-error") shouldBe "Bu kodla şirkət tapılmadı."
            fake.server.store
                .user("n@test.kadrohr.com") shouldBe null
        }

    @Test
    fun `a department is required and must belong to the company`() =
        runBlocking<Unit> {
            val (_, code) = companyWithDepartments()
            val browser = fake.browser()

            fun form(department: String) =
                arrayOf(
                    "code" to code,
                    "name" to "Nigar",
                    "email" to "n@test.kadrohr.com",
                    "phone" to "+994500000055",
                    "password" to "member-secret-1",
                    "department" to department,
                )
            val missing = browser.submit("/join", *form(""))
            missing.text("join-error") shouldBe "Departament seçin."
            missing.options("join-department").map { it.first } shouldContainExactly listOf("", "IT", "HR", "Satış")
            browser.submit("/join", *form("Marketinq")).text("join-error") shouldBe "Belə departament yoxdur."
        }

    @Test
    fun `a company without departments can still be joined with its code`() =
        runBlocking<Unit> {
            val owner = fake.registerOwner()
            val code = fake.companyOf(owner.email).string("code")
            fake
                .browser()
                .get("/join?code=$code")
                .options("join-department") shouldContainExactly listOf("" to "Departament seçin")

            val member = fake.joinWithCode(code, "early@test.kadrohr.com", department = "")

            member.browser
                .get("/")
                .text("current-user-role") shouldBe "employee"
            val user =
                fake.server.store
                    .user("early@test.kadrohr.com")
                    .shouldNotBeNull()
            user.department shouldBe null
            user.role shouldBe UserRole.EMPLOYEE
        }

    @Test
    fun `an invitee follows the e-mailed link and becomes the invited role in the invited department`() =
        runBlocking<Unit> {
            val owner = fake.registerOwner()
            val companyId = fake.companyOf(owner.email).string("id")
            val seeded = fake.seed(companyId, listOf("IT"), listOf(Invite("m@test.kadrohr.com", "Murad Menecer", "manager", "IT")))
            val link =
                seeded["invites"]!!
                    .jsonArray
                    .single()
                    .jsonObject
                    .string("link")
            link shouldMatch Regex("http://127\\.0\\.0\\.1:\\d+/invite/[A-Za-z]{32}")

            val mail = fake.mailsTo("m@test.kadrohr.com").single()
            mail.string("Subject") shouldBe "Dəvət"
            fake.invitationLink("m@test.kadrohr.com") shouldBe link
            fake.message(mail.string("ID")).string("HTML") shouldContain "href=\"$link\""

            val browser = fake.browser()
            val form = browser.get(link)
            listOf("invite-name", "invite-phone", "invite-password", "invite-submit").forEach { form.has(it) shouldBe true }
            form.attribute("invite-name", "value") shouldBe "Murad Menecer"
            form.attribute("invite-email", "value") shouldBe "m@test.kadrohr.com"

            val accepted = browser.submit(link, "name" to "Murad Məmmədov", "phone" to "+994500000044", "password" to "member-secret-1")
            accepted.location shouldBe "/verify?email=m%40test.kadrohr.com"
            fake.completeVerification(browser, "m@test.kadrohr.com", "+994500000044")

            val home = browser.get("/")
            home.text("current-user-name") shouldBe "Murad Məmmədov"
            home.text("current-user-role") shouldBe "manager"
            val user =
                fake.server.store
                    .user("m@test.kadrohr.com")
                    .shouldNotBeNull()
            user.role shouldBe UserRole.MANAGER
            user.department?.name shouldBe "IT"
            fake.server.store
                .invitations()
                .single()
                .acceptedAt
                .shouldNotBeNull()
        }

    @Test
    fun `an invitation link works only once and unknown tokens are refused`() =
        runBlocking<Unit> {
            val owner = fake.registerOwner()
            fake.seed(fake.companyOf(owner.email).string("id"), listOf("IT"), listOf(Invite("m@test.kadrohr.com", "M", "employee", "IT")))
            val member = fake.acceptInvitation("m@test.kadrohr.com")
            val link = fake.invitationLink("m@test.kadrohr.com")

            val reused = fake.browser().get(link)
            reused.status shouldBe 409
            reused.text("invite-error") shouldBe "Bu dəvət artıq istifadə olunub."
            val unknown = fake.browser().get("/invite/NoSuchToken")
            unknown.status shouldBe 404
            unknown.text("invite-error") shouldStartWith "Dəvət tapılmadı"
            member.browser.get("/").status shouldBe 200
        }

    @Test
    fun `invitation form errors keep the invitee on the form`() =
        runBlocking<Unit> {
            val owner = fake.registerOwner()
            fake.seed(fake.companyOf(owner.email).string("id"), listOf("IT"), listOf(Invite("m@test.kadrohr.com", "M", "employee", "IT")))
            val link = fake.invitationLink("m@test.kadrohr.com")
            val page = fake.browser().submit(link, "name" to "M", "phone" to "+994500000044", "password" to "short")
            page.status shouldBe 200
            page.text("invite-error") shouldBe "Parol ən azı 8 simvol olmalıdır."
            page.attribute("invite-phone", "value") shouldBe "+994500000044"
        }

    @Test
    fun `joining with the code applies a pending invitation's role, so managers can join either way`() =
        runBlocking<Unit> {
            val owner = fake.registerOwner()
            val company = fake.companyOf(owner.email)
            fake.seed(company.string("id"), listOf("IT", "HR"), listOf(Invite("boss@test.kadrohr.com", "Boss", "manager", "HR")))
            fake.joinWithCode(company.string("code"), "boss@test.kadrohr.com", department = "IT")

            val user =
                fake.server.store
                    .user("boss@test.kadrohr.com")
                    .shouldNotBeNull()
            user.role shouldBe UserRole.MANAGER
            user.department?.name shouldBe "HR"
            fake.browser().get(fake.invitationLink("boss@test.kadrohr.com")).status shouldBe 409
        }

    @Test
    fun `the admin can add departments and invite people from the company page`() =
        runBlocking<Unit> {
            val owner = fake.registerOwner()
            owner.browser.submit("/company/departments", "name" to "Maliyyə").text("company-info") shouldContain "Maliyyə"
            val invited =
                owner.browser.submit(
                    "/company/invites",
                    "email" to "fin@test.kadrohr.com",
                    "name" to "Fərid",
                    "role" to "manager",
                    "department" to "Maliyyə",
                )
            invited.status shouldBe 200
            invited.text("company-info") shouldBe "Dəvət göndərildi: fin@test.kadrohr.com"
            fake.acceptInvitation("fin@test.kadrohr.com", "Fərid")
            val user =
                fake.server.store
                    .user("fin@test.kadrohr.com")
                    .shouldNotBeNull()
            user.role shouldBe UserRole.MANAGER
            user.department?.name shouldBe "Maliyyə"

            val employee = fake.joinWithCode(fake.companyOf(owner.email).string("code"), "e@test.kadrohr.com", "Maliyyə")
            employee.browser.submit("/company/departments", "name" to "Hack").status shouldBe 403
            fake.server.store
                .departments(user.companyId)
                .map { it.name } shouldContainExactly listOf("Maliyyə")
        }
}

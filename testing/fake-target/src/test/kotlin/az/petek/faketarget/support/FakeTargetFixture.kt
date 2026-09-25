/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.support

import az.petek.faketarget.FakeTargetConfig
import az.petek.faketarget.FakeTargetServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A logged-in (or at least registered) person of the fake target and their own browser. */
data class Member(
    val name: String,
    val email: String,
    val phone: String,
    val password: String,
    val browser: Browser,
)

/** The usual cast: an admin, one manager each for IT and HR, two IT employees and one HR employee. */
data class Team(
    val companyId: String,
    val code: String,
    val admin: Member,
    val itManager: Member,
    val hrManager: Member,
    val itEmployee: Member,
    val itEmployee2: Member,
    val hrEmployee: Member,
) {
    val everyone: List<Member> get() = listOf(admin, itManager, hrManager, itEmployee, itEmployee2, hrEmployee)
}

/**
 * A started [FakeTargetServer] plus the flows every test needs, driven through the real HTTP surface (pages,
 * Mailpit API, test API) exactly like Pətək would drive them.
 */
class FakeTargetFixture(
    val config: FakeTargetConfig = FakeTargetConfig(),
) : AutoCloseable {
    val server: FakeTargetServer = FakeTargetServer(config).start()
    val http = HttpClient(CIO) { expectSuccess = false }
    private val browsers = mutableListOf<Browser>()
    private var phones = 0

    fun browser(): Browser = Browser(server.baseUrl).also { browsers += it }

    fun web(path: String): String = server.baseUrl.resolve(path).toString()

    fun mailpit(path: String): String = server.mailpitUrl.resolve(path).toString()

    fun nextPhone(): String = "+99450%07d".format(++phones)

    suspend fun testGet(
        path: String,
        token: String? = config.testToken,
    ): Page = http.get(web(path)) { token?.let { header("X-Test-Token", it) } }.toPage()

    suspend fun testPost(
        path: String,
        json: String,
        token: String? = config.testToken,
    ): Page =
        http
            .post(web(path)) {
                token?.let { header("X-Test-Token", it) }
                contentType(ContentType.Application.Json)
                setBody(json)
            }.toPage()

    suspend fun testDelete(
        path: String,
        token: String? = config.testToken,
    ): Page = http.delete(web(path)) { token?.let { header("X-Test-Token", it) } }.toPage()

    /** Mailpit search for messages to [to], newest first. */
    suspend fun mailsTo(to: String): List<JsonObject> =
        http
            .get(mailpit("/api/v1/search")) { parameter("query", "to:\"$to\"") }
            .toPage()
            .json()["messages"]!!
            .jsonArray
            .map { it.jsonObject }

    suspend fun message(id: String): JsonObject = http.get(mailpit("/api/v1/message/$id")).toPage().json()

    /** The code in the newest verification mail to [to]. */
    suspend fun verificationCode(to: String): String {
        val mail = mailsTo(to).first { it.string("Subject") == "Təsdiq kodu" }
        val text = message(mail.string("ID")).string("Text")
        return checkNotNull(Regex("\\b\\d{6}\\b").find(text)) { "no code in '$text'" }.value
    }

    suspend fun invitationLink(to: String): String {
        val mail = mailsTo(to).first { it.string("Subject") == "Dəvət" }
        val text = message(mail.string("ID")).string("Text")
        return checkNotNull(Regex("https?://\\S+/invite/[A-Za-z]+").find(text)) { "no link in '$text'" }.value
    }

    suspend fun otp(phone: String): String = testGet("/test/otp/$phone").json().string("code")

    /** From `/verify?email=…` to a session: e-mail code, then the phone OTP when the fake asks for it. */
    suspend fun completeVerification(
        browser: Browser,
        email: String,
        phone: String,
    ): Page {
        val afterEmail = browser.submit("/verify", "email" to email, "code" to verificationCode(email))
        afterEmail.status shouldBe 303
        if (afterEmail.location!!.startsWith("/verify/phone")) {
            browser.get(afterEmail.location).status shouldBe 200
            val afterPhone = browser.submit("/verify/phone", "email" to email, "code" to otp(phone))
            afterPhone.location shouldBe "/"
            return afterPhone
        }
        afterEmail.location shouldBe "/"
        return afterEmail
    }

    suspend fun registerOwner(
        email: String = "owner@${config.testMailDomain}",
        name: String = "Əli Kərimov",
        company: String = "Pətək Test MMC",
        password: String = "owner-secret-1",
    ): Member {
        val browser = browser()
        val phone = nextPhone()
        val page =
            browser.submit(
                "/register",
                "name" to name,
                "email" to email,
                "phone" to phone,
                "password" to password,
                "company" to company,
            )
        page.location shouldBe "/verify?email=${email.replace("@", "%40")}"
        completeVerification(browser, email, phone)
        return Member(name, email, phone, password, browser)
    }

    suspend fun companyOf(owner: String): JsonObject {
        val page = testGet("/test/companies?owner=$owner")
        page.status shouldBe 200
        return page.json()
    }

    suspend fun seed(
        companyId: String,
        departments: List<String>,
        invites: List<Invite> = emptyList(),
    ): JsonObject {
        val invitesJson =
            invites.joinToString(",") {
                """{"email":"${it.email}","name":"${it.name}","role":"${it.role}","department":${it.department?.let { d ->
                    "\"$d\""
                } ?: "null"}}"""
            }
        val body = """{"company_id":"$companyId","departments":[${departments.joinToString(",") { "\"$it\"" }}],"invites":[$invitesJson]}"""
        val page = testPost("/test/companies/seed", body)
        page.status shouldBe 200
        return page.json()
    }

    suspend fun joinWithCode(
        code: String,
        email: String,
        department: String,
        name: String = email.substringBefore('@'),
        password: String = "member-secret-1",
    ): Member {
        val browser = browser()
        val phone = nextPhone()
        val page =
            browser.submit(
                "/join",
                "code" to code,
                "name" to name,
                "email" to email,
                "phone" to phone,
                "password" to password,
                "department" to department,
            )
        page.status shouldBe 303
        completeVerification(browser, email, phone)
        return Member(name, email, phone, password, browser)
    }

    suspend fun acceptInvitation(
        email: String,
        name: String = email.substringBefore('@'),
        password: String = "member-secret-1",
    ): Member {
        val browser = browser()
        val phone = nextPhone()
        val link = invitationLink(email)
        browser.get(link).status shouldBe 200
        val page = browser.submit(link, "name" to name, "phone" to phone, "password" to password)
        page.status shouldBe 303
        completeVerification(browser, email, phone)
        return Member(name, email, phone, password, browser)
    }

    /** Builds the usual [Team] through owner sign-up, seeding, invitations and company-code joins. */
    suspend fun team(): Team {
        val domain = config.testMailDomain
        val admin = registerOwner()
        val company = companyOf(admin.email)
        val companyId = company.string("id")
        val seeded =
            seed(
                companyId,
                listOf("IT", "HR"),
                listOf(
                    Invite("it.manager@$domain", "İlkin Menecer", "manager", "IT"),
                    Invite("hr.manager@$domain", "Həsən Menecer", "manager", "HR"),
                    Invite("hr.employee@$domain", "Hicran İşçi", "employee", "HR"),
                ),
            )
        seeded.string("code") shouldNotBe ""
        val code = company.string("code")
        return Team(
            companyId = companyId,
            code = code,
            admin = admin,
            itManager = acceptInvitation("it.manager@$domain", "İlkin Menecer"),
            hrManager = acceptInvitation("hr.manager@$domain", "Həsən Menecer"),
            itEmployee = joinWithCode(code, "it.employee@$domain", "IT", "İsa İşçi"),
            itEmployee2 = joinWithCode(code, "it.employee2@$domain", "IT", "İlham İşçi"),
            hrEmployee = acceptInvitation("hr.employee@$domain", "Hicran İşçi"),
        )
    }

    override fun close() {
        browsers.forEach { it.close() }
        http.close()
        server.close()
    }
}

data class Invite(
    val email: String,
    val name: String,
    val role: String,
    val department: String?,
)

fun JsonObject.string(key: String): String = checkNotNull(this[key]) { "missing '$key' in $this" }.jsonPrimitive.content

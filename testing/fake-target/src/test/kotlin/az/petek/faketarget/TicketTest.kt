/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget

import az.petek.faketarget.model.TicketStatus
import az.petek.faketarget.support.FakeTargetFixture
import az.petek.faketarget.support.Member
import az.petek.faketarget.support.Page
import az.petek.faketarget.support.Team
import az.petek.faketarget.support.string
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class TicketTest {
    private val fake = FakeTargetFixture()

    @AfterEach
    fun tearDown() = fake.close()

    private val actionIds = listOf("ticket-set-in-progress", "ticket-assign", "ticket-approve", "ticket-reject")

    private suspend fun createTicket(
        author: Member,
        title: String = "Noutbuk işləmir",
        department: String = "IT",
    ): String {
        val created = author.browser.submit("/tickets", "title" to title, "description" to "Ekran qaralır", "department" to department)
        created.status shouldBe 303
        return created.location!!.substringAfterLast('/')
    }

    private fun Page.visibleActions(): List<String> = actionIds.filter { has(it) }

    private suspend fun oracleTicket(id: String) = fake.testGet("/test/tickets/$id").json()

    private suspend fun Member.api(
        action: String,
        id: String,
        json: String? = null,
    ): Page = browser.api(HttpMethod.Post, "/api/tickets/$id/$action", json)

    @Test
    fun `a ticket goes open, in progress, assigned and approved and every status change is in its history`() =
        runBlocking<Unit> {
            val team = fake.team()
            val form = team.itEmployee.browser.get("/tickets")
            listOf("ticket-create", "ticket-title", "ticket-description", "ticket-department", "ticket-submit").forEach {
                form.has(it) shouldBe true
            }
            form.options("ticket-department").map { it.first } shouldContainExactly listOf("", "IT", "HR")

            val id = createTicket(team.itEmployee)
            fake.testGet("/test/tickets/latest?by=${team.itEmployee.email}").json().string("id") shouldBe id
            team.itEmployee.browser
                .get("/tickets")
                .attributes("ticket-item", "data-id") shouldContainExactly listOf(id)
            val detail = team.itEmployee.browser.get("/tickets/$id")
            detail.text("ticket-status") shouldBe "open"
            detail.text("ticket-heading") shouldBe "Noutbuk işləmir"

            val manager = team.itManager.browser
            manager.submitAndFollow("/tickets/$id/in-progress").text("ticket-status") shouldBe "in_progress"
            oracleTicket(id).string("status") shouldBe "in_progress"

            val assignPage = manager.get("/tickets/$id")
            assignPage.options("ticket-assignee").map { it.first } shouldContainAll team.everyone.map { it.email }
            assignPage.options("ticket-assignee").first { it.first == team.hrManager.email }.second shouldContain "HR"
            val assigned = manager.submitAndFollow("/tickets/$id/assign", "email" to team.hrManager.email)
            assigned.text("ticket-assignee-name") shouldContain team.hrManager.email
            oracleTicket(id)["assignee"]!!.jsonObject.string("email") shouldBe team.hrManager.email

            manager.submitAndFollow("/tickets/$id/approve").text("ticket-status") shouldBe "approved"

            val ticket = oracleTicket(id)
            ticket.string("status") shouldBe "approved"
            ticket.string("department") shouldBe "IT"
            ticket.string("created_by") shouldBe team.itEmployee.email
            val history = ticket["history"]!!.jsonArray.map { it.jsonObject }
            history.map { it.string("from") to it.string("to") } shouldContainExactly
                listOf("open" to "in_progress", "in_progress" to "approved")
            history.map { it.string("by") } shouldContainExactly listOf(team.itManager.email, team.itManager.email)
            team.itEmployee.browser
                .get("/tickets/$id")
                .count("ticket-history-item") shouldBe 2
        }

    @Test
    fun `the author is notified of status changes and the assignee of the assignment`() =
        runBlocking<Unit> {
            val team = fake.team()
            val id = createTicket(team.itEmployee)
            fake.server.store
                .notifications(team.itManager.email)
                .single()
                .objectId shouldBe id
            fake.server.store
                .notifications(team.hrManager.email)
                .shouldBeEmpty()

            team.itManager.api("in-progress", id).status shouldBe 200
            team.itManager.api("assign", id, """{"email":"${team.hrManager.email}"}""").status shouldBe 200
            team.hrManager.api("reject", id).status shouldBe 200

            fake.server.store
                .notifications(team.itEmployee.email)
                .map { it.text } shouldContainExactly
                listOf("Müraciətin statusu: İcrada — Noutbuk işləmir", "Müraciətin statusu: Rədd edilib — Noutbuk işləmir")
            fake.server.store
                .notifications(team.hrManager.email)
                .map { it.type.key } shouldContainExactly listOf("ticket_assigned")
        }

    @Test
    fun `only the department's managers, the assigned manager and the admin see the action buttons`() =
        runBlocking<Unit> {
            val team = fake.team()
            val id = createTicket(team.itEmployee)
            team.admin.browser
                .get("/tickets/$id")
                .visibleActions() shouldContainExactly actionIds
            team.itManager.browser
                .get("/tickets/$id")
                .visibleActions() shouldContainExactly actionIds
            team.hrManager.browser
                .get("/tickets/$id")
                .visibleActions()
                .shouldBeEmpty()
            listOf(team.itEmployee, team.itEmployee2, team.hrEmployee).forEach { employee ->
                val page = employee.browser.get("/tickets/$id")
                page.status shouldBe 200
                page.visibleActions().shouldBeEmpty()
                page.has("ticket-assignee") shouldBe false
            }

            team.itManager.api("assign", id, """{"email":"${team.hrManager.email}"}""").status shouldBe 200
            team.hrManager.browser
                .get("/tickets/$id")
                .visibleActions() shouldContainExactly actionIds
        }

    @Test
    fun `the JSON API answers 401 without a session, 403 when not allowed, 404 for strangers and 409 once decided`() =
        runBlocking<Unit> {
            val team = fake.team()
            val id = createTicket(team.itEmployee)
            val anonymous = fake.browser()

            val noSession = anonymous.api(HttpMethod.Post, "/api/tickets/$id/approve")
            noSession.status shouldBe 401
            noSession.json().string("error") shouldBe "not_logged_in"
            team.itEmployee2.api("approve", id).status shouldBe 403
            team.itEmployee2.api("in-progress", id).status shouldBe 403
            team.itEmployee2.api("assign", id, """{"email":"${team.itEmployee2.email}"}""").status shouldBe 403
            team.hrManager.api("approve", id).status shouldBe 403
            team.itManager.api("approve", "t999").status shouldBe 404
            fake.registerOwner(email = "stranger@test.kadrohr.com", company = "Başqa").api("approve", id).status shouldBe 404

            val approved = team.itManager.api("approve", id)
            approved.status shouldBe 200
            approved.json().string("status") shouldBe "approved"
            val again = team.admin.api("approve", id)
            again.status shouldBe 409
            again.json().string("error") shouldBe "ticket_already_decided"
            team.admin.api("reject", id).status shouldBe 409
            team.admin.api("in-progress", id).status shouldBe 409
            team.admin.api("assign", id, """{"email":"${team.hrManager.email}"}""").status shouldBe 409
            team.itEmployee2.api("approve", id).status shouldBe 403

            team.itManager.browser
                .api(HttpMethod.Get, "/api/tickets/$id")
                .json()
                .string("status") shouldBe "approved"
            team.itManager.browser
                .api(HttpMethod.Get, "/api/me")
                .json()
                .string("role") shouldBe "manager"
        }

    @Test
    fun `assigning validates the assignee and accepts JSON or a form`() =
        runBlocking<Unit> {
            val team = fake.team()
            val id = createTicket(team.itEmployee)
            team.itManager.api("assign", id, """{"email":""}""").status shouldBe 400
            team.itManager.api("assign", id, "not json").status shouldBe 400
            team.itManager
                .api("assign", id, """{"email":"nobody@test.kadrohr.com"}""")
                .json()
                .string("error") shouldBe "unknown_assignee"
            team.itManager.browser
                .submit("/api/tickets/$id/assign", "email" to team.itEmployee2.email.uppercase())
                .json()["assignee"]!!
                .jsonObject
                .string("email") shouldBe team.itEmployee2.email
            fake.server.store
                .ticket(id)
                .shouldNotBeNull()
                .status shouldBe TicketStatus.OPEN
        }

    @Test
    fun `failed actions on the detail page render ticket-error`() =
        runBlocking<Unit> {
            val team = fake.team()
            val id = createTicket(team.itEmployee)
            val forbidden = team.itEmployee2.browser.submit("/tickets/$id/approve")
            forbidden.status shouldBe 403
            forbidden.text("ticket-error") shouldBe "Bu əməliyyat üçün icazəniz yoxdur."
            forbidden.text("ticket-status") shouldBe "open"

            team.itManager.browser
                .submit("/tickets/$id/in-progress")
                .status shouldBe 303
            team.itManager.browser
                .submit("/tickets/$id/in-progress")
                .text("ticket-error") shouldBe "Müraciət artıq icradadır."
            team.itManager.browser
                .submit("/tickets/$id/reject")
                .status shouldBe 303
            val late = team.admin.browser.submit("/tickets/$id/approve")
            late.status shouldBe 409
            late.text("ticket-error") shouldBe "Bu müraciət artıq qərarlaşdırılıb."
            late.text("ticket-status") shouldBe "rejected"
            team.admin.browser
                .submit("/tickets/$id/assign", "email" to "")
                .text("ticket-error") shouldBe "Bu müraciət artıq qərarlaşdırılıb."
            team.admin.browser
                .submit("/tickets/t999/approve")
                .status shouldBe 404
        }

    @Test
    fun `ticket creation needs a title and a department of the company`() =
        runBlocking<Unit> {
            val team = fake.team()
            val browser = team.itEmployee.browser
            browser
                .submit("/tickets", "title" to "", "description" to "x", "department" to "IT")
                .text("ticket-create-error") shouldBe "Başlığı daxil edin (ən çox 200 simvol)."
            val noDepartment = browser.submit("/tickets", "title" to "Printer", "description" to "x", "department" to "")
            noDepartment.status shouldBe 400
            noDepartment.text("ticket-create-error") shouldBe "Departament seçin."
            noDepartment.attribute("ticket-title", "value") shouldBe "Printer"
            browser
                .submit("/tickets", "title" to "Printer", "description" to "x", "department" to "Marketinq")
                .text("ticket-create-error") shouldBe "Belə departament yoxdur."
            fake.server.store
                .tickets()
                .shouldBeEmpty()
            fake.testGet("/test/tickets/latest?by=${team.itEmployee.email}").status shouldBe 404
        }

    @Test
    fun `tickets of another company are not found`() =
        runBlocking<Unit> {
            val team = fake.team()
            val id = createTicket(team.itEmployee)
            val stranger = fake.registerOwner(email = "stranger@test.kadrohr.com", company = "Başqa")
            stranger.browser.get("/tickets/$id").status shouldBe 404
            stranger.browser
                .get("/tickets")
                .count("ticket-item") shouldBe 0
        }

    @Test
    fun `of twenty concurrent approvals exactly one succeeds`() =
        runBlocking<Unit> {
            val team = fake.team()
            val id = createTicket(team.itEmployee)
            team.itManager.api("assign", id, """{"email":"${team.hrManager.email}"}""").status shouldBe 200
            val deciders = listOf(team.admin, team.itManager, team.hrManager)

            val statuses =
                withContext(Dispatchers.IO) {
                    (0 until 20).map { i -> async { deciders[i % deciders.size].api("approve", id).status } }.awaitAll()
                }

            statuses.count { it == 200 } shouldBe 1
            statuses.count { it == 409 } shouldBe 19
            val history = oracleTicket(id)["history"]!!.jsonArray
            history shouldHaveSize 1
            history.single().jsonObject.string("to") shouldBe "approved"
        }

    @Test
    fun `with the race bug two concurrent approvals both succeed`() =
        runBlocking<Unit> {
            FakeTargetFixture(FakeTargetConfig(bugs = setOf(FakeBug.RACE_DOUBLE_APPROVE))).use { buggy ->
                val team = buggy.team()
                val created = team.itEmployee.browser.submit("/tickets", "title" to "Yarış", "description" to "", "department" to "IT")
                val id = created.location!!.substringAfterLast('/')
                team.itManager.api("in-progress", id).status shouldBe 200
                team.itManager.api("assign", id, """{"email":"${team.hrManager.email}"}""").status shouldBe 200

                val statuses =
                    withContext(Dispatchers.IO) {
                        listOf(team.itManager, team.hrManager).map { async { it.api("approve", id).status } }.awaitAll()
                    }

                statuses shouldContainExactly listOf(200, 200)
                val history =
                    buggy
                        .testGet("/test/tickets/$id")
                        .json()["history"]!!
                        .jsonArray
                        .map { it.jsonObject }
                history.map { it.string("from") to it.string("to") } shouldContainExactly
                    listOf("open" to "in_progress", "in_progress" to "approved", "in_progress" to "approved")
                history.drop(1).map { it.string("by") }.toSet() shouldBe setOf(team.itManager.email, team.hrManager.email)
                team.admin.api("approve", id).status shouldBe 409
            }
        }

    @Test
    fun `with the employee bug employees see the approve button and may approve`() =
        runBlocking<Unit> {
            FakeTargetFixture(FakeTargetConfig(bugs = setOf(FakeBug.EMPLOYEE_CAN_APPROVE))).use { buggy ->
                val team = buggy.team()
                val created = team.itEmployee.browser.submit("/tickets", "title" to "Bug", "description" to "", "department" to "IT")
                val id = created.location!!.substringAfterLast('/')

                team.hrEmployee.browser
                    .get("/tickets/$id")
                    .visibleActions() shouldContainExactly listOf("ticket-approve")
                team.hrEmployee.api("reject", id).status shouldBe 403
                team.hrEmployee.api("in-progress", id).status shouldBe 403
                team.hrEmployee.api("approve", id).status shouldBe 200
                buggy.testGet("/test/tickets/$id").json().string("status") shouldBe "approved"
                team.itEmployee2.api("approve", id).status shouldBe 409
            }
        }

    @Test
    fun `with the wrong-status bug in progress reports success but the ticket stays open`() =
        runBlocking<Unit> {
            FakeTargetFixture(FakeTargetConfig(bugs = setOf(FakeBug.WRONG_TICKET_STATUS))).use { buggy ->
                val team = buggy.team()
                val created = team.itEmployee.browser.submit("/tickets", "title" to "Bug", "description" to "", "department" to "IT")
                val id = created.location!!.substringAfterLast('/')

                val response = team.itManager.api("in-progress", id)
                response.status shouldBe 200
                response.json().string("status") shouldBe "open"
                team.itManager.browser
                    .submitAndFollow("/tickets/$id/in-progress")
                    .text("ticket-status") shouldBe "open"
                val ticket = buggy.testGet("/test/tickets/$id").json()
                ticket.string("status") shouldBe "open"
                ticket["history"]!!.jsonArray.shouldBeEmpty()
                ticket["assignee"] shouldBe JsonNull
                buggy.server.store
                    .notifications(team.itEmployee.email)
                    .shouldBeEmpty()
                team.itManager.api("approve", id).status shouldBe 200
            }
        }

    @Test
    fun `an unassigned manager of another department cannot act even through the API`() =
        runBlocking<Unit> {
            val team: Team = fake.team()
            val id = createTicket(team.itEmployee, department = "HR")
            team.itManager.api("in-progress", id).status shouldBe 403
            team.hrManager.api("in-progress", id).status shouldBe 200
        }
}

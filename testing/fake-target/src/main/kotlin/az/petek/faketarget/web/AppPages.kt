/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.faketarget.web

import az.petek.faketarget.model.Announcement
import az.petek.faketarget.model.Department
import az.petek.faketarget.model.Notification
import az.petek.faketarget.model.Ticket
import az.petek.faketarget.model.User
import az.petek.faketarget.model.UserRole
import az.petek.faketarget.service.CompanyOverview
import az.petek.faketarget.service.TicketPermissions
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.article
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.tr
import kotlinx.html.ul
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Pages behind the login. Every one of them carries the session header and the live notification panel.

internal fun HTML.homePage(
    chrome: Chrome,
    openTickets: Int,
) = page("Ana səhifə", chrome) {
    val user = chrome.user
    h1 { +"Xoş gəldiniz, ${user.name}!" }
    dl {
        dt { +"Şirkət" }
        dd { +chrome.companyName }
        dt { +"Rol" }
        dd { +user.role.label }
        dt { +"Departament" }
        dd { +(user.department?.name ?: "—") }
    }
    p { +"Açıq və icradakı müraciətlər: $openTickets" }
    ul {
        li { a(href = "/announcements") { +"Elanlara bax" } }
        li { a(href = "/tickets") { +"Müraciət yarat və ya bax" } }
    }
}

internal fun HTML.companyPage(
    chrome: Chrome,
    overview: CompanyOverview,
    error: String? = null,
    info: String? = null,
) = page("Şirkət", chrome) {
    h1 {
        testId("company-name")
        +overview.company.name
    }
    p {
        +"Şirkət kodu: "
        code {
            testId("company-code")
            +overview.company.code
        }
        +" — işçilər bu kodla /join səhifəsində qoşulur."
    }
    alert("company-error", error)
    notice("company-info", info)
    h2 { +"Departamentlər" }
    ul {
        testId("company-departments")
        overview.departments.forEach { department ->
            li {
                testId("company-department")
                dataId(department.id)
                +department.name
            }
        }
    }
    form(action = "/company/departments", method = FormMethod.post) {
        inputField("Yeni departament", "company-department-name", "name")
        submitButton("company-department-submit", "Əlavə et")
    }
    h2 { +"Dəvət göndər" }
    form(action = "/company/invites", method = FormMethod.post) {
        inputField("E-poçt", "company-invite-email", "email", InputType.email)
        inputField("Ad Soyad", "company-invite-name", "name")
        selectField("Rol", "company-invite-role", "role", listOf(UserRole.EMPLOYEE, UserRole.MANAGER).map { it.key to it.label })
        selectField(
            label = "Departament",
            id = "company-invite-department",
            name = "department",
            options = overview.departments.map { it.name to it.name },
            placeholder = "—",
        )
        submitButton("company-invite-submit", "Dəvət et")
    }
    h2 { +"İşçilər (${overview.members.size})" }
    table {
        tr {
            th { +"Ad" }
            th { +"E-poçt" }
            th { +"Rol" }
            th { +"Departament" }
        }
        overview.members.forEach { member ->
            tr {
                testId("company-member")
                dataId(member.id)
                td { +member.name }
                td { +member.email }
                td { +member.role.label }
                td { +(member.department?.name ?: "—") }
            }
        }
    }
    h2 { +"Dəvətlər (${overview.invitations.size})" }
    table {
        tr {
            th { +"E-poçt" }
            th { +"Rol" }
            th { +"Vəziyyət" }
        }
        overview.invitations.forEach { invitation ->
            tr {
                td { +invitation.email }
                td { +invitation.role.label }
                td { +(if (invitation.acceptedAt == null) "Gözləyir" else "Qəbul edilib") }
            }
        }
    }
}

internal fun HTML.announcementsPage(
    chrome: Chrome,
    announcements: List<Announcement>,
    error: String? = null,
    title: String = "",
    body: String = "",
) = page("Elanlar", chrome) {
    h1 { +"Elanlar" }
    if (chrome.user.role == UserRole.ADMIN) {
        section {
            testId("announcement-create")
            h2 { +"Yeni elan" }
            alert("announcement-error", error)
            form(action = "/announcements", method = FormMethod.post) {
                inputField("Başlıq", "announcement-title", "title", value = title)
                textAreaField("Mətn", "announcement-body", "body", body)
                submitButton("announcement-submit", "Dərc et")
            }
        }
    }
    if (announcements.isEmpty()) p("empty") { +"Hələ elan yoxdur." }
    div {
        testId("announcement-list")
        announcements.forEach { announcementItem(it, link = true) }
    }
}

internal fun HTML.announcementPage(
    chrome: Chrome,
    announcement: Announcement,
    info: String? = null,
) = page(announcement.title, chrome) {
    p { a(href = "/announcements") { +"← Bütün elanlar" } }
    notice("announcement-info", info)
    announcementItem(announcement, link = false)
}

private fun FlowContent.announcementItem(
    announcement: Announcement,
    link: Boolean,
) {
    article {
        testId("announcement-item")
        dataId(announcement.id)
        h3 {
            if (link) a(href = "/announcements/${announcement.id}") { +announcement.title } else +announcement.title
        }
        p {
            testId("announcement-body-text")
            +announcement.body
        }
        p("empty") { +"${announcement.createdBy} · ${format(announcement.createdAt)}" }
    }
}

internal fun HTML.ticketsPage(
    chrome: Chrome,
    tickets: List<Ticket>,
    departments: List<Department>,
    error: String? = null,
    form: TicketForm = TicketForm(),
) = page("Müraciətlər", chrome) {
    h1 { +"Müraciətlər" }
    section {
        testId("ticket-create")
        h2 { +"Yeni müraciət" }
        alert("ticket-create-error", error)
        form(action = "/tickets", method = FormMethod.post) {
            inputField("Başlıq", "ticket-title", "title", value = form.title)
            textAreaField("Təsvir", "ticket-description", "description", form.description)
            selectField(
                label = "Departament",
                id = "ticket-department",
                name = "department",
                options = departments.map { it.name to it.name },
                selected = form.department,
                placeholder = "Departament seçin",
            )
            submitButton("ticket-submit", "Göndər")
        }
    }
    h2 { +"Bütün müraciətlər" }
    if (tickets.isEmpty()) p("empty") { +"Hələ müraciət yoxdur." }
    ul {
        testId("ticket-list")
        tickets.forEach { ticket ->
            li {
                testId("ticket-item")
                dataId(ticket.id)
                a(href = "/tickets/${ticket.id}") { +ticket.title }
                +" · ${ticket.department.name} · "
                span("status") { +ticket.status.key }
            }
        }
    }
}

internal data class TicketForm(
    val title: String = "",
    val description: String = "",
    val department: String = "",
)

internal data class TicketDetail(
    val ticket: Ticket,
    val permissions: TicketPermissions,
    val assignees: List<User>,
    val names: Map<String, String>,
)

internal fun HTML.ticketPage(
    chrome: Chrome,
    detail: TicketDetail,
    error: String? = null,
) = page(detail.ticket.title, chrome) {
    val ticket = detail.ticket
    p { a(href = "/tickets") { +"← Bütün müraciətlər" } }
    h1 {
        testId("ticket-heading")
        dataId(ticket.id)
        +ticket.title
    }
    p {
        testId("ticket-description-text")
        +ticket.description
    }
    dl {
        dt { +"Status" }
        dd {
            span("status") {
                testId("ticket-status")
                attributes["data-status"] = ticket.status.key
                +ticket.status.key
            }
            +" (${ticket.status.label})"
        }
        dt { +"Departament" }
        dd { +ticket.department.name }
        dt { +"Müəllif" }
        dd { +detail.names.nameOf(ticket.createdBy) }
        dt { +"İcraçı" }
        dd {
            span {
                testId("ticket-assignee-name")
                +(ticket.assignee?.let(detail.names::nameOf) ?: "Təyin edilməyib")
            }
        }
    }
    alert("ticket-error", error)
    ticketActions(detail)
    h2 { +"Tarixçə" }
    table {
        testId("ticket-history")
        tr {
            th { +"Əvvəl" }
            th { +"Sonra" }
            th { +"Kim" }
            th { +"Vaxt" }
        }
        ticket.history.forEach { entry ->
            tr {
                testId("ticket-history-item")
                td { +entry.from.key }
                td { +entry.to.key }
                td { +detail.names.nameOf(entry.by) }
                td { +format(entry.at) }
            }
        }
    }
}

/** Only the buttons the user may use are rendered (docs/TARGET_CONTRACT.md, ticket detail). */
private fun FlowContent.ticketActions(detail: TicketDetail) {
    val ticket = detail.ticket
    val allowed = detail.permissions
    if (!(allowed.startProgress || allowed.assign || allowed.approve || allowed.reject)) return
    div("actions") {
        if (allowed.startProgress) {
            form(action = "/tickets/${ticket.id}/in-progress", method = FormMethod.post) {
                submitButton("ticket-set-in-progress", "İcraya götür")
            }
        }
        if (allowed.assign) {
            form(action = "/tickets/${ticket.id}/assign", method = FormMethod.post) {
                selectField(
                    label = "İcraçı",
                    id = "ticket-assignee",
                    name = "email",
                    options = detail.assignees.map { it.email to assigneeLabel(it) },
                    selected = ticket.assignee,
                    placeholder = "İcraçı seçin",
                )
                submitButton("ticket-assign", "Təyin et", "secondary")
            }
        }
        if (allowed.approve) {
            form(action = "/tickets/${ticket.id}/approve", method = FormMethod.post) {
                submitButton("ticket-approve", "Təsdiqlə")
            }
        }
        if (allowed.reject) {
            form(action = "/tickets/${ticket.id}/reject", method = FormMethod.post) {
                submitButton("ticket-reject", "Rədd et", "danger")
            }
        }
    }
}

private fun assigneeLabel(user: User): String = "${user.name} — ${user.role.label}" + (user.department?.let { ", ${it.name}" } ?: "")

internal fun HTML.notificationsPage(
    chrome: Chrome,
    notifications: List<Notification>,
) = page("Bildirişlər", chrome) {
    h1 { +"Bildirişlər" }
    if (notifications.isEmpty()) p("empty") { +"Bildiriş yoxdur." }
    ul {
        testId("notification-page-list")
        notifications.forEach { notification ->
            li {
                testId("notification-entry")
                dataId(notification.objectId)
                a(href = notification.link) { +notification.text }
                +" · ${format(notification.createdAt)}"
            }
        }
    }
}

internal fun HTML.errorPage(
    chrome: Chrome?,
    title: String,
    message: String,
) = page(title, chrome) {
    h1 { +title }
    alert("page-error", message)
    p { a(href = "/") { +"Ana səhifəyə qayıt" } }
}

private fun Map<String, String>.nameOf(email: String): String = this[email]?.let { "$it ($email)" } ?: email

private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

private fun format(instant: Instant): String = TIMESTAMP.format(instant)

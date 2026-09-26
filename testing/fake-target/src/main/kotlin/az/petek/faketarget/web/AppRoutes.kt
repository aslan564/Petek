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

import az.petek.faketarget.model.Ticket
import az.petek.faketarget.model.User
import az.petek.faketarget.model.UserRole
import az.petek.faketarget.service.AccountService
import az.petek.faketarget.service.AnnouncementService
import az.petek.faketarget.service.CompanyService
import az.petek.faketarget.service.Failure
import az.petek.faketarget.service.InviteRequest
import az.petek.faketarget.service.NotificationService
import az.petek.faketarget.service.Outcome
import az.petek.faketarget.service.TicketService
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Pages behind the login: home, company (admin), announcements, tickets and notifications. */
internal class AppRoutes(
    private val accounts: AccountService,
    private val companies: CompanyService,
    private val announcements: AnnouncementService,
    private val tickets: TicketService,
    private val notifications: NotificationService,
) {
    fun install(route: Route) {
        route.homeRoutes()
        route.companyRoutes()
        route.announcementRoutes()
        route.ticketRoutes()
    }

    /**
     * The logged-in user, or null after redirecting to the login page. A page (GET) is resumed after the login; a form
     * post is not replayed, so it simply goes to the login page.
     */
    suspend fun currentUserOrLogin(call: ApplicationCall): User? {
        val user = accounts.userBySession(SessionCookie.read(call))
        if (user == null) {
            val resume = call.request.httpMethod == HttpMethod.Get
            call.seeOther(if (resume) "/login?next=${call.request.uri.encodeURLParameter()}" else "/login")
        }
        return user
    }

    fun chrome(user: User): Chrome = Chrome(user, companies.company(user).name, notifications.panel(user))

    /** The session header of the caller, or null for a visitor without a session. */
    fun chromeOf(call: ApplicationCall): Chrome? = accounts.userBySession(SessionCookie.read(call))?.let(::chrome)

    private fun Route.homeRoutes() {
        get("/") {
            val user = currentUserOrLogin(call) ?: return@get
            val open = tickets.list(user).count { !it.status.isDecided }
            call.respondHtml { homePage(chrome(user), open) }
        }
        get("/notifications") {
            val user = currentUserOrLogin(call) ?: return@get
            notifications.openList(user)
            call.respondHtml { notificationsPage(chrome(user), notifications.all(user)) }
        }
    }

    private fun Route.companyRoutes() {
        get("/company") { withAdmin { user -> showCompany(user) } }
        post("/company/departments") {
            withAdmin { user ->
                when (val outcome = companies.addDepartment(user, call.receiveParameters()["name"].orEmpty())) {
                    is Outcome.Ok -> showCompany(user, info = "Departament əlavə olundu: ${outcome.value.name}")
                    is Outcome.Failed -> showCompany(user, error = outcome.failure.message, status = outcome.failure.status)
                }
            }
        }
        post("/company/invites") {
            withAdmin { user ->
                val params = call.receiveParameters()
                val request =
                    InviteRequest(
                        email = params["email"].orEmpty(),
                        name = params["name"].orEmpty(),
                        role = params["role"].orEmpty(),
                        department = params["department"],
                    )
                when (val outcome = companies.invite(user, request, call.publicBaseUrl())) {
                    is Outcome.Ok -> showCompany(user, info = "Dəvət göndərildi: ${outcome.value.email}")
                    is Outcome.Failed -> showCompany(user, error = outcome.failure.message, status = outcome.failure.status)
                }
            }
        }
    }

    private suspend fun RoutingContext.showCompany(
        user: User,
        error: String? = null,
        info: String? = null,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) {
        when (val overview = companies.overview(user)) {
            is Outcome.Ok -> call.respondHtml(status) { companyPage(chrome(user), overview.value, error, info) }
            is Outcome.Failed -> call.respondFailure(user, overview.failure)
        }
    }

    private fun Route.announcementRoutes() {
        get("/announcements") {
            val user = currentUserOrLogin(call) ?: return@get
            val list = announcements.openList(user)
            call.respondHtml { announcementsPage(chrome(user), list) }
        }
        post("/announcements") {
            val user = currentUserOrLogin(call) ?: return@post
            val params = call.receiveParameters()
            val title = params["title"].orEmpty()
            val body = params["body"].orEmpty()
            when (val outcome = announcements.create(user, title, body)) {
                is Outcome.Ok -> {
                    call.seeOther("/announcements/${outcome.value.id}")
                }

                is Outcome.Failed -> {
                    if (outcome.failure == Failure.FORBIDDEN) {
                        call.respondFailure(user, outcome.failure)
                    } else {
                        val list = announcements.openList(user)
                        call.respondHtml(
                            outcome.failure.status,
                        ) { announcementsPage(chrome(user), list, outcome.failure.message, title, body) }
                    }
                }
            }
        }
        get("/announcements/{id}") {
            val user = currentUserOrLogin(call) ?: return@get
            when (val outcome = announcements.open(user, call.parameters["id"].orEmpty())) {
                is Outcome.Ok -> call.respondHtml { announcementPage(chrome(user), outcome.value) }
                is Outcome.Failed -> call.respondFailure(user, outcome.failure)
            }
        }
    }

    private fun Route.ticketRoutes() {
        get("/tickets") {
            val user = currentUserOrLogin(call) ?: return@get
            call.respondHtml { ticketsPage(chrome(user), tickets.list(user), companies.departments(user)) }
        }
        post("/tickets") {
            val user = currentUserOrLogin(call) ?: return@post
            val params = call.receiveParameters()
            val form = TicketForm(params["title"].orEmpty(), params["description"].orEmpty(), params["department"].orEmpty())
            when (val outcome = tickets.create(user, form.title, form.description, form.department)) {
                is Outcome.Ok -> {
                    call.seeOther("/tickets/${outcome.value.id}")
                }

                is Outcome.Failed -> {
                    call.respondHtml(outcome.failure.status) {
                        ticketsPage(chrome(user), tickets.list(user), companies.departments(user), outcome.failure.message, form)
                    }
                }
            }
        }
        get("/tickets/{id}") {
            val user = currentUserOrLogin(call) ?: return@get
            when (val outcome = tickets.find(user, call.parameters["id"].orEmpty())) {
                is Outcome.Ok -> call.respondHtml { ticketPage(chrome(user), detail(user, outcome.value)) }
                is Outcome.Failed -> call.respondFailure(user, outcome.failure)
            }
        }
        post("/tickets/{id}/in-progress") { ticketAction { user, id -> tickets.startProgress(user, id) } }
        post("/tickets/{id}/assign") {
            ticketAction { user, id -> tickets.assign(user, id, call.receiveParameters()["email"].orEmpty()) }
        }
        post("/tickets/{id}/approve") { ticketAction { user, id -> tickets.approve(user, id) } }
        post("/tickets/{id}/reject") { ticketAction { user, id -> tickets.reject(user, id) } }
    }

    /** Runs a ticket action from the detail page: back to the page on success, the page with `ticket-error` otherwise. */
    private suspend fun RoutingContext.ticketAction(action: suspend (User, String) -> Outcome<Ticket>) {
        val user = currentUserOrLogin(call) ?: return
        val id = call.parameters["id"].orEmpty()
        when (val outcome = action(user, id)) {
            is Outcome.Ok -> {
                call.seeOther("/tickets/$id")
            }

            is Outcome.Failed -> {
                val ticket = tickets.find(user, id)
                if (ticket is Outcome.Ok) {
                    call.respondHtml(
                        outcome.failure.status,
                    ) { ticketPage(chrome(user), detail(user, ticket.value), outcome.failure.message) }
                } else {
                    call.respondFailure(user, outcome.failure)
                }
            }
        }
    }

    private fun detail(
        user: User,
        ticket: Ticket,
    ): TicketDetail {
        val members = tickets.assignees(user)
        return TicketDetail(ticket, tickets.permissions(user, ticket), members, members.associate { it.email to it.name })
    }

    private suspend fun RoutingContext.withAdmin(block: suspend RoutingContext.(User) -> Unit) {
        val user = currentUserOrLogin(call) ?: return
        if (user.role != UserRole.ADMIN) return call.respondFailure(user, Failure.FORBIDDEN)
        block(user)
    }

    private suspend fun ApplicationCall.respondFailure(
        user: User?,
        failure: Failure,
    ) {
        val title =
            when (failure.status) {
                HttpStatusCode.NotFound -> "Tapılmadı"
                HttpStatusCode.Forbidden -> "İcazə yoxdur"
                else -> "Xəta"
            }
        respondHtml(failure.status) { errorPage(user?.let(::chrome), title, failure.message) }
    }
}

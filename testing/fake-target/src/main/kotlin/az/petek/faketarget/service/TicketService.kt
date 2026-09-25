/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.service

import az.petek.faketarget.FakeBug
import az.petek.faketarget.FakeTargetConfig
import az.petek.faketarget.model.NotificationType
import az.petek.faketarget.model.Ticket
import az.petek.faketarget.model.TicketHistoryEntry
import az.petek.faketarget.model.TicketStatus
import az.petek.faketarget.model.User
import az.petek.faketarget.model.UserRole
import az.petek.faketarget.store.FakeTargetStore
import az.petek.faketarget.store.StoreState
import java.time.Clock
import java.time.Instant

/** Which ticket actions a user may take; the detail page renders exactly these buttons. */
internal data class TicketPermissions(
    val startProgress: Boolean,
    val assign: Boolean,
    val approve: Boolean,
    val reject: Boolean,
)

/**
 * Tickets and their workflow `open -> in_progress -> approved | rejected`.
 *
 * Who may act: the admin, the managers of the ticket's department and the manager the ticket is assigned to (so the
 * default campaign's race between the IT manager and the HR manager it was assigned to is a real race). Employees
 * may only create tickets. Checks run in the order 404 (not in the user's company), 403, 409, and a decision is
 * atomic: of many concurrent approvals exactly one succeeds, unless [FakeBug.RACE_DOUBLE_APPROVE] is on.
 */
internal class TicketService(
    private val store: FakeTargetStore,
    private val config: FakeTargetConfig,
    private val notifications: NotificationService,
    private val clock: Clock,
) {
    private val race = RaceWindow(config.raceWindow)

    fun create(
        author: User,
        title: String,
        description: String,
        department: String,
    ): Outcome<Ticket> {
        val cleanTitle = Inputs.validTitle(title) ?: return Failure.TITLE_REQUIRED.failed()
        if (department.isBlank()) return Failure.DEPARTMENT_REQUIRED.failed()
        val now = clock.instant()
        val outcome =
            store.transaction {
                val chosen = department(author.companyId, department) ?: return@transaction Failure.UNKNOWN_DEPARTMENT.failed()
                val ticket =
                    Ticket(
                        id = nextId("t"),
                        companyId = author.companyId,
                        title = cleanTitle,
                        description = description.trim(),
                        department = chosen,
                        status = TicketStatus.OPEN,
                        assignee = null,
                        createdBy = author.email,
                        createdAt = now,
                        history = emptyList(),
                    )
                tickets[ticket.id] = ticket
                val managers = membersOf(author.companyId).filter { it.role == UserRole.MANAGER && it.department?.id == chosen.id }
                (ticket to (managers.map { it.email } - author.email)).ok()
            }
        return when (outcome) {
            is Outcome.Failed -> {
                outcome
            }

            is Outcome.Ok -> {
                val (ticket, managers) = outcome.value
                notify(ticket, managers, NotificationType.TICKET_CREATED, "Yeni müraciət: ${ticket.title}")
                ticket.ok()
            }
        }
    }

    /** The company's tickets, newest first; every member may see them. */
    fun list(user: User): List<Ticket> = store.transaction { tickets.values.filter { it.companyId == user.companyId }.asReversed() }

    fun find(
        user: User,
        id: String,
    ): Outcome<Ticket> = store.transaction { ticketOf(user, id)?.ok() ?: Failure.TICKET_NOT_FOUND.failed() }

    /** People a ticket can be assigned to: every member of the company, by name. */
    fun assignees(user: User): List<User> = store.transaction { membersOf(user.companyId).sortedBy { it.name } }

    fun permissions(
        user: User,
        ticket: Ticket,
    ): TicketPermissions {
        val manage = canManage(user, ticket)
        return TicketPermissions(startProgress = manage, assign = manage, approve = canApprove(user, ticket), reject = manage)
    }

    fun startProgress(
        user: User,
        id: String,
    ): Outcome<Ticket> {
        val now = clock.instant()
        val outcome =
            store.transaction {
                val ticket = ticketOf(user, id) ?: return@transaction Failure.TICKET_NOT_FOUND.failed()
                when {
                    !canManage(user, ticket) -> Failure.FORBIDDEN.failed()

                    ticket.status.isDecided -> Failure.TICKET_ALREADY_DECIDED.failed()

                    ticket.status == TicketStatus.IN_PROGRESS -> Failure.TICKET_ALREADY_IN_PROGRESS.failed()

                    // The deliberate defect: report success, change nothing.
                    config.has(FakeBug.WRONG_TICKET_STATUS) -> (ticket to false).ok()

                    else -> (changeStatus(ticket, ticket.status, TicketStatus.IN_PROGRESS, user.email, now) to true).ok()
                }
            }
        return when (outcome) {
            is Outcome.Failed -> {
                outcome
            }

            is Outcome.Ok -> {
                val (ticket, changed) = outcome.value
                if (changed) notifyStatusChange(ticket, user)
                ticket.ok()
            }
        }
    }

    fun assign(
        user: User,
        id: String,
        assigneeEmail: String,
    ): Outcome<Ticket> {
        val outcome =
            store.transaction {
                val ticket = ticketOf(user, id) ?: return@transaction Failure.TICKET_NOT_FOUND.failed()
                when {
                    !canManage(user, ticket) -> {
                        Failure.FORBIDDEN.failed()
                    }

                    ticket.status.isDecided -> {
                        Failure.TICKET_ALREADY_DECIDED.failed()
                    }

                    assigneeEmail.isBlank() -> {
                        Failure.ASSIGNEE_REQUIRED.failed()
                    }

                    else -> {
                        val assignee =
                            users[Inputs.email(assigneeEmail)]?.takeIf { it.companyId == user.companyId }
                                ?: return@transaction Failure.UNKNOWN_ASSIGNEE.failed()
                        ticket.copy(assignee = assignee.email).also { tickets[it.id] = it }.ok()
                    }
                }
            }
        if (outcome is Outcome.Ok) {
            val ticket = outcome.value
            val assignee = listOfNotNull(ticket.assignee) - user.email
            notify(ticket, assignee, NotificationType.TICKET_ASSIGNED, "Sizə müraciət təyin edildi: ${ticket.title}")
        }
        return outcome
    }

    suspend fun approve(
        user: User,
        id: String,
    ): Outcome<Ticket> = decide(user, id, TicketStatus.APPROVED)

    suspend fun reject(
        user: User,
        id: String,
    ): Outcome<Ticket> = decide(user, id, TicketStatus.REJECTED)

    private suspend fun decide(
        user: User,
        id: String,
        target: TicketStatus,
    ): Outcome<Ticket> {
        val outcome =
            if (config.has(FakeBug.RACE_DOUBLE_APPROVE)) {
                racyDecide(user, id, target)
            } else {
                store.transaction {
                    when (val checked = decisionCheck(user, id, target)) {
                        is Outcome.Failed -> checked
                        is Outcome.Ok -> changeStatus(checked.value, checked.value.status, target, user.email, clock.instant()).ok()
                    }
                }
            }
        if (outcome is Outcome.Ok) notifyStatusChange(outcome.value, user)
        return outcome
    }

    /** The deliberate check-then-act race: check under the lock, wait for a partner, then write without re-checking. */
    private suspend fun racyDecide(
        user: User,
        id: String,
        target: TicketStatus,
    ): Outcome<Ticket> {
        val checked = store.transaction { decisionCheck(user, id, target) }
        if (checked !is Outcome.Ok) return checked
        race.await(id)
        return store.transaction {
            val current = tickets[id] ?: return@transaction Failure.TICKET_NOT_FOUND.failed()
            // "from" is the stale status this caller saw, the tell-tale sign of a lost update.
            changeStatus(current, checked.value.status, target, user.email, clock.instant()).ok()
        }
    }

    private fun StoreState.decisionCheck(
        user: User,
        id: String,
        target: TicketStatus,
    ): Outcome<Ticket> {
        val ticket = ticketOf(user, id) ?: return Failure.TICKET_NOT_FOUND.failed()
        val allowed = if (target == TicketStatus.APPROVED) canApprove(user, ticket) else canManage(user, ticket)
        return when {
            !allowed -> Failure.FORBIDDEN.failed()
            ticket.status.isDecided -> Failure.TICKET_ALREADY_DECIDED.failed()
            else -> ticket.ok()
        }
    }

    private fun StoreState.ticketOf(
        user: User,
        id: String,
    ): Ticket? = tickets[id]?.takeIf { it.companyId == user.companyId }

    private fun StoreState.changeStatus(
        ticket: Ticket,
        from: TicketStatus,
        to: TicketStatus,
        by: String,
        now: Instant,
    ): Ticket = ticket.copy(status = to, history = ticket.history + TicketHistoryEntry(from, to, by, now)).also { tickets[it.id] = it }

    private fun canManage(
        user: User,
        ticket: Ticket,
    ): Boolean =
        user.companyId == ticket.companyId &&
            when (user.role) {
                UserRole.ADMIN -> true
                UserRole.MANAGER -> user.department?.id == ticket.department.id || user.email == ticket.assignee
                UserRole.EMPLOYEE -> false
            }

    private fun canApprove(
        user: User,
        ticket: Ticket,
    ): Boolean =
        canManage(user, ticket) ||
            (config.has(FakeBug.EMPLOYEE_CAN_APPROVE) && user.role == UserRole.EMPLOYEE && user.companyId == ticket.companyId)

    /** Tells the ticket's author about a status change made by someone else. */
    private fun notifyStatusChange(
        ticket: Ticket,
        actor: User,
    ) {
        val text = "Müraciətin statusu: ${ticket.status.label} — ${ticket.title}"
        notify(ticket, listOf(ticket.createdBy) - actor.email, NotificationType.TICKET_STATUS, text)
    }

    private fun notify(
        ticket: Ticket,
        recipients: List<String>,
        type: NotificationType,
        text: String,
    ) {
        if (recipients.isEmpty()) return
        notifications.notify(ticket.companyId, recipients, type, ticket.id, text, "/tickets/${ticket.id}")
    }
}

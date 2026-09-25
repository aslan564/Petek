/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.store

import az.petek.faketarget.model.Announcement
import az.petek.faketarget.model.Company
import az.petek.faketarget.model.Department
import az.petek.faketarget.model.Invitation
import az.petek.faketarget.model.Notification
import az.petek.faketarget.model.Receipt
import az.petek.faketarget.model.Ticket
import az.petek.faketarget.model.User

/**
 * In-memory state of one fake target. Every change runs inside one lock ([transaction]), so multi-step operations
 * such as "check the ticket is undecided, then approve it" are atomic.
 *
 * The public members are read-only snapshots for test assertions (lists are oldest first) and never expose
 * credentials, codes or session tokens. Each collection is offered as a property and as a function
 * (`store.users` / `store.users()`), so assertions can use whichever reads better.
 */
class FakeTargetStore internal constructor() {
    private val lock = Any()
    private val state = StoreState()

    internal fun <T> transaction(block: StoreState.() -> T): T = synchronized(lock) { state.block() }

    val companies: List<Company> get() = companies()
    val users: List<User> get() = users()
    val invitations: List<Invitation> get() = invitations()
    val announcements: List<Announcement> get() = announcements()
    val notifications: List<Notification> get() = notifications()
    val tickets: List<Ticket> get() = tickets()

    fun companies(): List<Company> = transaction { this.companies.values.toList() }

    fun company(id: String): Company? = transaction { this.companies[id] }

    fun departments(companyId: String): List<Department> = transaction { departmentsOf(companyId) }

    fun users(): List<User> = transaction { this.users.values.toList() }

    fun user(email: String): User? = transaction { this.users[email.trim().lowercase()] }

    fun invitations(): List<Invitation> = transaction { this.invitations.values.toList() }

    fun announcements(): List<Announcement> = transaction { this.announcements.values.toList() }

    fun announcement(id: String): Announcement? = transaction { this.announcements[id] }

    /** Who read the announcement and when (first read only). */
    fun receipts(announcementId: String): List<Receipt> =
        transaction { this.receipts.values.filter { it.announcementId == announcementId } }

    /** All notifications, or only those of [recipient]. */
    fun notifications(recipient: String? = null): List<Notification> =
        transaction {
            val wanted = recipient?.trim()?.lowercase()
            this.notifications.values.filter { wanted == null || it.recipient == wanted }
        }

    fun tickets(): List<Ticket> = transaction { this.tickets.values.toList() }

    fun ticket(id: String): Ticket? = transaction { this.tickets[id] }
}

/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

package az.petek.faketarget.service

import az.petek.faketarget.model.Announcement
import az.petek.faketarget.model.Company
import az.petek.faketarget.model.Notification
import az.petek.faketarget.model.Receipt
import az.petek.faketarget.model.Ticket
import az.petek.faketarget.store.FakeTargetStore
import az.petek.faketarget.store.PhoneOtp
import az.petek.faketarget.store.StoreState

/**
 * Read side of the test API. Companies are visible whatever their flag (so the oracle can check `is_test` before a
 * teardown); everything inside a company is only served for test companies (`403` otherwise).
 */
internal class TestQueries(
    private val store: FakeTargetStore,
) {
    fun otp(phone: String): Outcome<PhoneOtp> =
        store.transaction {
            val otp = latestPhoneOtps[Inputs.phoneDigits(phone)] ?: return@transaction Failure.OTP_NOT_FOUND.failed()
            guard(users[otp.email]?.companyId) { otp }
        }

    fun companyByOwner(email: String): Outcome<Company> =
        store.transaction {
            val owner = Inputs.email(email)
            companies.values.firstOrNull { it.ownerEmail == owner }?.ok() ?: Failure.COMPANY_NOT_FOUND.failed()
        }

    fun company(id: String): Outcome<Company> = store.transaction { companies[id]?.ok() ?: Failure.COMPANY_NOT_FOUND.failed() }

    fun latestAnnouncementBy(email: String): Outcome<Announcement> =
        store.transaction {
            val author = Inputs.email(email)
            val latest =
                announcements.values.lastOrNull { it.createdBy == author } ?: return@transaction Failure.ANNOUNCEMENT_NOT_FOUND.failed()
            guard(latest.companyId) { latest }
        }

    fun announcement(id: String): Outcome<Announcement> =
        store.transaction {
            val announcement = announcements[id] ?: return@transaction Failure.ANNOUNCEMENT_NOT_FOUND.failed()
            guard(announcement.companyId) { announcement }
        }

    fun receipts(announcementId: String): Outcome<List<Receipt>> =
        store.transaction {
            val announcement = announcements[announcementId] ?: return@transaction Failure.ANNOUNCEMENT_NOT_FOUND.failed()
            guard(announcement.companyId) { receipts.values.filter { it.announcementId == announcementId } }
        }

    fun latestTicketBy(email: String): Outcome<Ticket> =
        store.transaction {
            val author = Inputs.email(email)
            val latest = tickets.values.lastOrNull { it.createdBy == author } ?: return@transaction Failure.TICKET_NOT_FOUND.failed()
            guard(latest.companyId) { latest }
        }

    fun ticket(id: String): Outcome<Ticket> =
        store.transaction {
            val ticket = tickets[id] ?: return@transaction Failure.TICKET_NOT_FOUND.failed()
            guard(ticket.companyId) { ticket }
        }

    /** Newest first. */
    fun notifications(email: String): Outcome<List<Notification>> =
        store.transaction {
            val user = users[Inputs.email(email)] ?: return@transaction Failure.USER_NOT_FOUND.failed()
            guard(user.companyId) { notifications.values.filter { it.recipient == user.email }.asReversed() }
        }

    private fun <T> StoreState.guard(
        companyId: String?,
        value: () -> T,
    ): Outcome<T> =
        when (companyId?.let { companies[it] }?.isTest) {
            true -> value().ok()
            false -> Failure.NOT_A_TEST_COMPANY.failed()
            null -> Failure.COMPANY_NOT_FOUND.failed()
        }
}

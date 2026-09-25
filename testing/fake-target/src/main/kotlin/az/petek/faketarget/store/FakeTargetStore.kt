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
 * such as "check the ticket is undecided, then approve it" are atomic. The public functions return immutable
 * snapshots for test assertions; they never expose credentials, codes or session tokens.
 */
class FakeTargetStore internal constructor() {
    private val lock = Any()
    private val state = StoreState()

    internal fun <T> transaction(block: StoreState.() -> T): T = synchronized(lock) { state.block() }

    fun companies(): List<Company> = transaction { companies.values.toList() }

    fun company(id: String): Company? = transaction { companies[id] }

    fun departments(companyId: String): List<Department> = transaction { departmentsOf(companyId) }

    fun users(): List<User> = transaction { users.values.toList() }

    fun user(email: String): User? = transaction { users[email.trim().lowercase()] }

    fun invitations(): List<Invitation> = transaction { invitations.values.toList() }

    /** Oldest first. */
    fun announcements(): List<Announcement> = transaction { announcements.values.toList() }

    fun announcement(id: String): Announcement? = transaction { announcements[id] }

    fun receipts(announcementId: String): List<Receipt> = transaction { receipts.values.filter { it.announcementId == announcementId } }

    /** Oldest first; all notifications, or only those of [recipient]. */
    fun notifications(recipient: String? = null): List<Notification> =
        transaction {
            val wanted = recipient?.trim()?.lowercase()
            notifications.values.filter { wanted == null || it.recipient == wanted }
        }

    /** Oldest first. */
    fun tickets(): List<Ticket> = transaction { tickets.values.toList() }

    fun ticket(id: String): Ticket? = transaction { tickets[id] }
}

package az.petek.identity.domain

import az.petek.core.ids.AgentId
import az.petek.core.ids.RunTag
import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role
import kotlin.random.Random

/**
 * The registry generator of docs/PLAN.md "Kimlik reyestri". For a valid spec (see [IdentitySpecValidator]):
 * - `a01` is the admin ([RegistrationMode.OWNER], no department), then the managers, then the employees.
 * - Non-admins are dealt to departments in one round-robin: manager `i` gets `departments[i % d]` (one manager per
 *   department when the counts match) and employees continue the rotation, so every department ends up within
 *   ±1 of the others, for employees and for head count.
 * - Names: the spec's names first, then the catalog ([NameAllocator]).
 * - E-mail `<ascii first name>.<run tag>.<agent id>@<mail domain>`, unique because the agent id is.
 * - Registration modes: members of each department are shuffled, departments are interleaved and the first
 *   `inviteCount` join by invitation, the rest by company code, so every department gets a mix when both
 *   counts reach the number of departments.
 *
 * Names, roles, departments and registration modes depend only on the spec (its seed), so every run of a campaign
 * tests the same people. E-mails, passwords and phones also depend on the run tag, so concurrent or leftover runs
 * never collide on the target.
 */
class DefaultIdentityRegistryGenerator(
    nameCatalog: NameCatalog,
    private val passwordDeriver: PasswordDeriver,
) : IdentityRegistryGenerator {
    private val nameAllocator = NameAllocator(nameCatalog)
    private val validator = IdentitySpecValidator(nameAllocator)

    override fun generate(
        spec: IdentitySpec,
        runTag: RunTag,
    ): IdentityPlan {
        val valid = validator.validate(spec)
        val seats = seats(spec, valid.departments)
        val names = nameAllocator.allocate(valid.names, spec.testers, spec.seed)
        val registrations = registrationModes(seats, valid.departments, spec)
        val phones = phoneNumbers(spec.testers, spec.seed, runTag)
        val identities =
            seats.mapIndexed { i, seat ->
                Identity(
                    agentId = seat.agentId,
                    displayName = names[i].displayName,
                    email = email(names[i].firstName, runTag, seat.agentId, valid.mailDomain),
                    password = passwordDeriver.derive(runTag, seat.agentId),
                    phone = phones[i],
                    role = seat.role,
                    department = seat.department,
                    registration = registrations.getValue(seat.agentId),
                )
            }
        return IdentityPlan(runTag, identities)
    }

    private fun seats(
        spec: IdentitySpec,
        departments: List<String>,
    ): List<Seat> {
        val admin = Seat(AgentId.of(1), Role.ADMIN, department = null)
        val others =
            (0 until spec.managers + spec.employees).map { j ->
                val role = if (j < spec.managers) Role.MANAGER else Role.EMPLOYEE
                Seat(AgentId.of(j + 2), role, departments[j % departments.size])
            }
        return listOf(admin) + others
    }

    private fun registrationModes(
        seats: List<Seat>,
        departments: List<String>,
        spec: IdentitySpec,
    ): Map<AgentId, RegistrationMode> {
        val random = Random(spec.seed xor REGISTRATION_SALT)
        val members = seats.filter { it.role != Role.ADMIN }.groupBy { it.department }
        val groups = departments.shuffled(random).map { members[it].orEmpty().shuffled(random) }
        val rounds = groups.maxOfOrNull { it.size } ?: 0
        val order = (0 until rounds).flatMap { round -> groups.mapNotNull { it.getOrNull(round) } }
        val joiners =
            order.withIndex().associate { (i, seat) ->
                seat.agentId to if (i < spec.inviteCount) RegistrationMode.INVITE else RegistrationMode.COMPANY_CODE
            }
        return joiners + seats.filter { it.role == Role.ADMIN }.associate { it.agentId to RegistrationMode.OWNER }
    }

    private fun phoneNumbers(
        count: Int,
        seed: Long,
        runTag: RunTag,
    ): List<String> {
        val random = Random(seed * PHONE_SEED_MULTIPLIER + runTag.value.hashCode())
        val phones = LinkedHashSet<String>(count)
        while (phones.size < count) {
            val subscriber = random.nextInt(UNALLOCATED_SUBSCRIBERS).toString().padStart(SUBSCRIBER_DIGITS, '0')
            phones += PHONE_PREFIX + subscriber
        }
        return phones.toList()
    }

    private fun email(
        firstName: String,
        runTag: RunTag,
        agentId: AgentId,
        mailDomain: String,
    ): String = "${AsciiSlug.of(firstName)}.${runTag.value}.${agentId.value}@$mailDomain"

    private data class Seat(
        val agentId: AgentId,
        val role: Role,
        val department: String?,
    )

    private companion object {
        const val PHONE_PREFIX = "+99450"
        const val SUBSCRIBER_DIGITS = 7

        /**
         * Subscriber numbers 0000000..1999999. Azerbaijani mobile subscriber numbers start with 2..9, so these are
         * outside the allocated ranges and can never reach a real person, even if an SMS were sent by mistake.
         */
        const val UNALLOCATED_SUBSCRIBERS = 2_000_000

        /** Keeps the registration shuffle independent of the name choice, which also draws from the seed. */
        const val REGISTRATION_SALT = 0x5245_4749_5354_4552L
        const val PHONE_SEED_MULTIPLIER = 31L
    }
}

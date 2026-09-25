package az.petek.campaign.domain

import az.petek.core.model.RegistrationMode
import az.petek.core.model.Role

/**
 * Who performs a step. Grammar (whitespace-insensitive):
 * ```
 * expression := selector ( "|" selector )*          // also a YAML list of selectors
 * selector   := role [ "[" filter "]" ]
 * role       := admin | manager | employee
 * filter     := "*" | <department> | criterion ("," criterion)*
 * criterion  := dept=<department> | reg=invite|company_code | n=<1-based index>
 * ```
 * Examples: `admin`, `manager[IT]`, `employee[*]`, `employee[dept=IT, n=1]`, `employee[*] | manager[*]`.
 */
data class ActorExpression(
    val selectors: List<ActorSelector>,
    val raw: String,
)

data class ActorSelector(
    val role: Role,
    val department: String? = null,
    val registration: RegistrationMode? = null,
    /** 1-based index among the testers matching role/department/registration; null = all of them. */
    val nth: Int? = null,
)

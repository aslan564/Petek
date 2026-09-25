package az.petek.core.model

/** Tester roles on the target system. Shared by campaign quotas, identity registry and actor selection. */
enum class Role(
    val key: String,
) {
    ADMIN("admin"),
    MANAGER("manager"),
    EMPLOYEE("employee"),
    ;

    companion object {
        fun fromKey(key: String): Role? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** How a tester gets into the company: the admin creates it; others join by invitation or by company code. */
enum class RegistrationMode(
    val key: String,
) {
    OWNER("owner"),
    INVITE("invite"),
    COMPANY_CODE("company_code"),
    ;

    companion object {
        fun fromKey(key: String): RegistrationMode? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

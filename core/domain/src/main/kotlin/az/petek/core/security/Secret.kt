package az.petek.core.security

/** Wraps sensitive text (passwords, tokens) so it never leaks through `toString`, logs or data class printing. */
class Secret(
    private val value: String,
) {
    fun reveal(): String = value

    val isBlank: Boolean get() = value.isBlank()

    override fun toString(): String = "Secret(***)"

    override fun equals(other: Any?): Boolean = other is Secret && other.value == value

    override fun hashCode(): Int = value.hashCode()
}

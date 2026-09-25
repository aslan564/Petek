package az.petek.core.error

/** Base type for failures the harness understands and reports with a clear reason. */
open class PetekException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

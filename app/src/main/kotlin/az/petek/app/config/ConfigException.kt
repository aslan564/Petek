package az.petek.app.config

import az.petek.core.error.PetekException
import java.nio.file.Path

/**
 * The configuration cannot be used. [problems] lists every problem found (not just the first), so the user fixes
 * `.env` in one go. Messages name variables, never their values.
 */
open class ConfigException(
    val problems: List<String>,
    headline: String = "Invalid configuration",
) : PetekException(headline + ":\n" + problems.joinToString("\n") { "  - $it" })

/** A `.env` file with malformed lines; [problems] carry the line numbers. */
class EnvFileException(
    val file: Path?,
    problems: List<String>,
) : ConfigException(problems, headline = "Cannot read ${file ?: ".env"}")

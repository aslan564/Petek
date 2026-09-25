package az.petek.faketarget

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Settings of [main], read from environment variables so they match Pətək's `.env`. */
internal data class FakeTargetOptions(
    val config: FakeTargetConfig,
    val port: Int,
    val mailPort: Int,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String>): FakeTargetOptions {
            fun value(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            val defaults = FakeTargetConfig()
            val config =
                FakeTargetConfig(
                    testToken = value("PETEK_TEST_TOKEN") ?: defaults.testToken,
                    testMailDomain = value("PETEK_MAIL_DOMAIN") ?: defaults.testMailDomain,
                    requirePhoneOtp = value("FAKE_TARGET_PHONE_OTP")?.let(::parseBoolean) ?: defaults.requirePhoneOtp,
                    notificationDelay =
                        value("FAKE_TARGET_NOTIFICATION_DELAY_MS")?.let { parseMillis("FAKE_TARGET_NOTIFICATION_DELAY_MS", it) }
                            ?: defaults.notificationDelay,
                    bugs = value("FAKE_TARGET_BUGS")?.let(::parseBugs) ?: emptySet(),
                    raceWindow =
                        value("FAKE_TARGET_RACE_WINDOW_MS")?.let { parseMillis("FAKE_TARGET_RACE_WINDOW_MS", it) }
                            ?: defaults.raceWindow,
                )
            return FakeTargetOptions(
                config = config,
                port = value("FAKE_TARGET_PORT")?.let { parsePort("FAKE_TARGET_PORT", it) } ?: DEFAULT_PORT,
                mailPort = value("FAKE_TARGET_MAIL_PORT")?.let { parsePort("FAKE_TARGET_MAIL_PORT", it) } ?: DEFAULT_MAIL_PORT,
            )
        }

        private const val DEFAULT_PORT = 18080
        private const val DEFAULT_MAIL_PORT = 18025

        private fun parseBoolean(raw: String): Boolean =
            requireNotNull(raw.lowercase().toBooleanStrictOrNull()) { "FAKE_TARGET_PHONE_OTP must be true or false, was '$raw'" }

        private fun parseMillis(
            name: String,
            raw: String,
        ): Duration {
            val millis = requireNotNull(raw.toLongOrNull()?.takeIf { it >= 0 }) { "$name must be >= 0, was '$raw'" }
            return millis.milliseconds
        }

        private fun parsePort(
            name: String,
            raw: String,
        ): Int = requireNotNull(raw.toIntOrNull()?.takeIf { it in 0..65_535 }) { "$name must be a port number, was '$raw'" }

        private fun parseBugs(raw: String): Set<FakeBug> =
            raw
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map { name ->
                    FakeBug.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?: throw IllegalArgumentException(
                            "Unknown FAKE_TARGET_BUGS entry '$name'; known: ${FakeBug.entries.joinToString()}",
                        )
                }.toSet()
    }
}

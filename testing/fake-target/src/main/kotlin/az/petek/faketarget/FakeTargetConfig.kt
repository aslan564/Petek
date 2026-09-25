package az.petek.faketarget

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How a [FakeTargetServer] behaves. The defaults give a correct target that matches docs/TARGET_CONTRACT.md;
 * [bugs] switches on deliberate defects for tests that must prove Pətək notices them.
 */
data class FakeTargetConfig(
    /** Expected `X-Test-Token` for `/test/...`; a missing or different token gets `401`. */
    val testToken: String = "dev-token",
    /** A company whose owner e-mail ends with `@<testMailDomain>` is `is_test`; only those may be seeded or deleted. */
    val testMailDomain: String = "test.kadrohr.com",
    /** When true, e-mail verification is followed by the phone OTP step (`/verify/phone`, code via `/test/otp/{phone}`). */
    val requirePhoneOtp: Boolean = true,
    /** Delay between creating an announcement and fanning out its notifications (simulates a slow queue). */
    val notificationDelay: Duration = 0.seconds,
    val bugs: Set<FakeBug> = emptySet(),
    /** With [FakeBug.RACE_DOUBLE_APPROVE]: how long a decision waits for a concurrent one before it writes. */
    val raceWindow: Duration = 2.seconds,
) {
    init {
        require(testToken.isNotBlank()) { "testToken must not be blank" }
        require(testMailDomain.isNotBlank() && '@' !in testMailDomain) {
            "testMailDomain must be a bare domain such as test.kadrohr.com"
        }
        require(!notificationDelay.isNegative()) { "notificationDelay must not be negative" }
        require(raceWindow.isPositive()) { "raceWindow must be positive" }
    }

    internal fun has(bug: FakeBug): Boolean = bug in bugs

    /** Masks the test token so the config can be logged. */
    override fun toString(): String =
        "FakeTargetConfig(testToken=***, testMailDomain=$testMailDomain, requirePhoneOtp=$requirePhoneOtp, " +
            "notificationDelay=$notificationDelay, bugs=$bugs, raceWindow=$raceWindow)"
}

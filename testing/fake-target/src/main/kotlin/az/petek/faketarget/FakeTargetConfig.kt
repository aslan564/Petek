/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

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
    val testMailDomain: String = "test.portal.example",
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
            "testMailDomain must be a bare domain such as test.portal.example"
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

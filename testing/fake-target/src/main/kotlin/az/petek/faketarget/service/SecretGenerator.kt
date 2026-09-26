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

package az.petek.faketarget.service

import java.security.SecureRandom

/** Random codes and tokens. Tests never predict them; they read codes through Mailpit or `/test/otp`. */
internal class SecretGenerator(
    val random: SecureRandom = SecureRandom(),
) {
    /** Six digits without a leading zero, e.g. `482913`. */
    fun sixDigitCode(): String = (100_000 + random.nextInt(900_000)).toString()

    /** Four digits for company codes (`PTK-4821`). */
    fun fourDigits(): String = (1_000 + random.nextInt(9_000)).toString()

    /** Letters only, so an invitation mail never contains a digit run that looks like a verification code. */
    fun letterToken(length: Int = 32): String = random(LETTERS, length)

    fun sessionToken(): String = random(ALPHANUMERIC, 43)

    /** Mailpit-style message id. */
    fun mailId(): String = random(ALPHANUMERIC, 22)

    private fun random(
        alphabet: String,
        length: Int,
    ): String = buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }

    private companion object {
        const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        const val ALPHANUMERIC = LETTERS + "0123456789"
    }
}
